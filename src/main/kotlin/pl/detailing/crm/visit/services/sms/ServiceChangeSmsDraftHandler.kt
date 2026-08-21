package pl.detailing.crm.visit.services.sms

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.PendingOperation
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.consent.ServiceChangesSummary
import pl.detailing.crm.smscampaigns.consent.SmsConsentService
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.services.ServicesChangePlanner
import pl.detailing.crm.visit.services.ServicesChangesPayload

/**
 * Propozycja treści SMS-a pokazywana w CRM-ie przed zapisaniem zmian.
 *
 * [message] jest w pełni edytowalna przez użytkownika. [fixedSuffix] to fraza doklejana
 * przy wysyłce po stronie serwera — zwracamy ją tylko po to, żeby CRM mógł pokazać
 * pełny podgląd wiadomości; edycja tej części nie ma znaczenia, bo i tak nie jest
 * brana z żądania.
 */
data class ServiceChangeSmsDraftResponse(
    val message: String,
    val fixedSuffix: String,
    val totalGrossBefore: Long,
    val totalGrossAfter: Long,
    /** false = model nie odpowiedział i treść pochodzi z szablonu awaryjnego. */
    val aiGenerated: Boolean
)

/**
 * Wczytuje wizytę i wylicza skutki proponowanych zmian. Wydzielone z handlera,
 * żeby transakcja (i połączenie do bazy) kończyła się przed wywołaniem LLM-a.
 */
@Service
class ServiceChangeSmsContextLoader(
    private val visitRepository: VisitRepository,
    private val planner: ServicesChangePlanner
) {

    /** Kontekst dla modelu razem z kwotami pokazywanymi w CRM-ie. */
    data class LoadedContext(
        val context: ServiceChangeSmsContext,
        val totalGrossBefore: Long,
        val totalGrossAfter: Long
    )

    @Transactional(readOnly = true)
    fun load(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        payload: ServicesChangesPayload
    ): LoadedContext {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        visitEntity.serviceItems.size // wymuszenie załadowania kolekcji lazy
        val visit = visitEntity.toDomain()

        val plan = planner.plan(visit, payload)
        val projected = planner.project(visit, plan, userId)

        val totalGrossBefore = visit.calculateTotalGross().amountInCents
        val totalGrossAfter = projected.serviceItems
            .filter { it.pendingOperation != PendingOperation.DELETE }
            .sumOf { it.finalPriceGross.amountInCents }

        return LoadedContext(
            context = ServiceChangeSmsContext(
                added = plan.added.map { SmsServiceChangeLine(it.serviceName, it.finalPriceGross.amountInCents) },
                removed = plan.deleted.map { SmsServiceChangeLine(it.serviceName, it.finalPriceGross.amountInCents) },
                priceChanged = plan.updated.map { it.toChangeLine() },
                totalGrossBeforeCents = totalGrossBefore,
                totalGrossAfterCents = totalGrossAfter
            ),
            totalGrossBefore = totalGrossBefore,
            totalGrossAfter = totalGrossAfter
        )
    }

    /** Cena sprzed edycji siedzi w snapshocie potwierdzonego stanu pozycji. */
    private fun VisitServiceItem.toChangeLine() = SmsServiceChangeLine(
        serviceName = serviceName,
        grossCents = finalPriceGross.amountInCents,
        previousGrossCents = confirmedSnapshot?.finalPriceGross?.amountInCents
    )
}

/**
 * Składa propozycję SMS-a: liczy skutki zmian (bez zapisu) i prosi LLM o krótkie
 * podsumowanie dla klienta. Ceny liczą się tym samym kodem, co przy zapisie
 * ([ServicesChangePlanner]), więc kwota w SMS-ie zgadza się z tym, co zostanie zapisane.
 */
@Service
class ServiceChangeSmsDraftHandler(
    private val contextLoader: ServiceChangeSmsContextLoader,
    private val generator: ServiceChangeSmsGenerator
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ServiceChangeSmsDraftHandler::class.java)
    }

    suspend fun handle(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        payload: ServicesChangesPayload
    ): ServiceChangeSmsDraftResponse {
        val loaded = contextLoader.load(visitId, studioId, userId, payload)

        val message = try {
            generator.generate(loaded.context)
        } catch (e: Exception) {
            logger.warn("Nie udało się wygenerować treści SMS dla wizyty {}: {}", visitId, e.message)
            return ServiceChangeSmsDraftResponse(
                message = SmsConsentService.buildFallbackBody(
                    loaded.context.toChangesSummary(),
                    loaded.totalGrossAfter
                ),
                fixedSuffix = SmsConsentService.CONSENT_CALL_TO_ACTION,
                totalGrossBefore = loaded.totalGrossBefore,
                totalGrossAfter = loaded.totalGrossAfter,
                aiGenerated = false
            )
        }

        return ServiceChangeSmsDraftResponse(
            message = message,
            fixedSuffix = SmsConsentService.CONSENT_CALL_TO_ACTION,
            totalGrossBefore = loaded.totalGrossBefore,
            totalGrossAfter = loaded.totalGrossAfter,
            aiGenerated = true
        )
    }

    private fun ServiceChangeSmsContext.toChangesSummary() = ServiceChangesSummary(
        addedNames = added.map { it.serviceName },
        removedNames = removed.map { it.serviceName },
        priceChangedNames = priceChanged.map { it.serviceName }
    )
}
