package pl.detailing.crm.visitcard.upsell

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.PendingOperation
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.consent.SmsConsentService
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestStatus
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.VisitCardTokenService
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.time.Instant
import java.util.UUID

/**
 * Handles the customer's "add suggested services" action from the public Visit Card.
 *
 * Flow:
 *  1. Selected SUGGESTED suggestions are turned into PENDING service items on the
 *     visit (same domain path as an employee-proposed scope change).
 *  2. A consent SMS is sent: "Odpisz TAK, żeby do rezerwacji dodać usługi: …".
 *  3. An [SmsConsentRequestEntity] is persisted so the existing inbound-reply
 *     webhook ([SmsConsentService.processInboundReply]) approves the pending items
 *     when the customer replies "TAK" — the upsell flow plugs into the proven
 *     2-way consent mechanism instead of inventing a parallel one.
 *
 * Suggestions move SUGGESTED → REQUESTED here; they become CONFIRMED via
 * [UpsellConsentConfirmedListener] when the "TAK" reply is processed.
 */
@Service
class RequestUpsellServicesHandler(
    private val tokenService: VisitCardTokenService,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val suggestionRepository: VisitUpsellSuggestionRepository,
    private val smsConsentRequestRepository: SmsConsentRequestRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(token: String, request: RequestUpsellServicesRequest): RequestUpsellServicesResponse {
        val tokenEntity = tokenService.findByToken(token)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")

        val visitId = VisitId(tokenEntity.visitId)
        val studioId = StudioId(tokenEntity.studioId)

        val requestedIds = request.suggestionIds
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .toSet()
        if (requestedIds.isEmpty()) {
            throw ValidationException("Nie wybrano żadnych usług")
        }

        val allSuggestions = suggestionRepository.findAllByVisitIdAndStudioId(visitId.value, studioId.value)
        val selected = allSuggestions.filter {
            it.id in requestedIds && it.status == UpsellSuggestionStatus.SUGGESTED
        }
        if (selected.isEmpty()) {
            throw ValidationException("Wybrane usługi nie są już dostępne do dodania")
        }

        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")
        visitEntity.serviceItems.size // force-load within transaction

        val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, studioId.value)
        val phone = customer?.phone?.takeIf { it.isNotBlank() }
            ?: throw ValidationException(
                "Nie możemy wysłać SMS-a z potwierdzeniem — brak numeru telefonu. Skontaktuj się z serwisem."
            )

        // 1. Pending service items — the exact snapshot frozen at suggestion time.
        val pendingItems = selected.map { suggestion ->
            VisitServiceItem.createPending(
                serviceId = ServiceId(suggestion.serviceId),
                serviceName = suggestion.serviceName,
                basePriceNet = Money.fromCents(suggestion.basePriceNet),
                vatRate = VatRate.fromInt(suggestion.vatRate),
                adjustmentType = suggestion.adjustmentType,
                adjustmentValue = suggestion.adjustmentValue,
                customNote = suggestion.note
            )
        }

        val updatedVisit = visitEntity.toDomain().saveServicesChanges(
            added = pendingItems,
            updated = emptyList(),
            deletedIds = emptyList(),
            updatedBy = SmsConsentService.CUSTOMER_SMS_USER_ID
        )
        visitRepository.save(VisitEntity.fromDomain(updatedVisit))

        // 2. Mark suggestions as requested and link them to the created pending items.
        val now = Instant.now()
        selected.forEachIndexed { index, suggestion ->
            suggestion.status = UpsellSuggestionStatus.REQUESTED
            suggestion.serviceItemId = pendingItems[index].id.value
            suggestion.requestedAt = now
        }
        suggestionRepository.saveAll(selected)

        // 3. Consent SMS + tracking record so the inbound "TAK" approves the items.
        val normalizedPhone = normalizePolishPhone(phone)
        val message = buildConsentSms(selected.map { it.serviceName to it.finalPriceGross })

        val proposedTotalGross = updatedVisit.serviceItems
            .filter { it.pendingOperation != PendingOperation.DELETE }
            .sumOf { it.finalPriceGross.amountInCents }

        var smsSent = false
        var errorMessage: String? = null
        try {
            val result = communicationGateway.sendTransactionalSms(studioId.value, normalizedPhone, message)
            smsSent = result.success
            errorMessage = result.errorMessage

            if (result.success) {
                smsConsentRequestRepository.supersedePendingByVisitId(visitId.value)
                smsConsentRequestRepository.save(
                    SmsConsentRequestEntity(
                        id = UUID.randomUUID(),
                        visitId = visitId.value,
                        studioId = studioId.value,
                        customerPhone = normalizedPhone,
                        totalPriceGross = proposedTotalGross,
                        status = SmsConsentRequestStatus.PENDING,
                        externalMessageId = result.externalMessageId,
                        createdAt = now,
                        respondedAt = null
                    )
                )
            }
        } catch (e: InsufficientSmsCreditsException) {
            logger.warn("Upsell consent SMS blocked — no credits [studio={} visit={}]", studioId, visitId)
            errorMessage = "Brak kredytów SMS"
        }

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = visitId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.VISIT_CARD_UPSELL_SMS,
                recipientAddress = normalizedPhone,
                subject = null,
                bodyContent = message,
                success = smsSent,
                errorMessage = errorMessage
            )
        )

        logger.info(
            "Upsell requested from Visit Card | visit={} suggestions={} smsSent={}",
            visitId, selected.map { it.id }, smsSent
        )

        val responseMessage = if (smsSent) {
            "Wysłaliśmy SMS na numer ${maskPhone(normalizedPhone)}. " +
                "Odpisz TAK, aby potwierdzić dodanie usług do rezerwacji."
        } else {
            "Zapisaliśmy Twój wybór, ale nie udało się wysłać SMS-a z potwierdzeniem. " +
                "Serwis skontaktuje się z Tobą w sprawie potwierdzenia."
        }

        val suggestions = suggestionRepository
            .findAllByVisitIdAndStudioId(visitId.value, studioId.value)
            .map { it.toPublicDto() }

        return RequestUpsellServicesResponse(
            smsSent = smsSent,
            message = responseMessage,
            suggestions = suggestions
        )
    }

    companion object {
        /**
         * Builds the consent SMS mandated by the business:
         * "Odpisz TAK, żeby do rezerwacji dodać usługi: XXX (w cenie XXX PLN), YYY (w cenie YYY PLN)"
         */
        internal fun buildConsentSms(services: List<Pair<String, Long>>): String {
            val list = services.joinToString(", ") { (name, grossCents) ->
                "$name (w cenie ${formatPln(grossCents)} PLN)"
            }
            return "Odpisz TAK, żeby do rezerwacji dodać usługi: $list"
        }

        private fun formatPln(cents: Long): String = "%d.%02d".format(cents / 100, cents % 100)

        /** +48100200300 → +48*****0300 — enough for the customer to recognise their number. */
        internal fun maskPhone(phone: String): String =
            if (phone.length <= 4) phone
            else phone.take(3) + "*".repeat(phone.length - 7) + phone.takeLast(4)
    }
}
