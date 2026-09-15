package pl.detailing.crm.leads.similar

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.appointment.LeadQuoteSyncService
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemSource
import pl.detailing.crm.leads.infrastructure.LeadServiceItemStatus
import pl.detailing.crm.leads.infrastructure.LeadServicePriceSource
import pl.detailing.crm.leads.update.LeadServiceItemsService
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.service.taxonomy.serviceNameKey
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * „Sugerowane usługi": z treści maila dobiera pozycje CENNIKA i podsuwa je na leadzie
 * jako wiersze SUGGESTED, gotowe do jednego kliknięcia [Akceptuj] / [Odrzuć].
 *
 * ZAKAZ HALUCYNACJI JEST STRUKTURALNY, NIE PROSZONY. Model nie emituje ani nazw, ani
 * cen — wybiera numery pozycji z podanego cennika ([LeadServiceIntentService]). Cena
 * bierze się z jednego z trzech miejsc, nigdy od modelu:
 *  - usługa o stałej cenie → cena z cennika (CATALOG),
 *  - usługa z wyceną niestandardową → cena TEJ usługi z „Podobnego zlecenia" (HISTORY),
 *    najnowsza wizyta wygrywa,
 *  - brak historii → cena pusta (PENDING), a interfejs wymusi kwotę przy akceptacji.
 *
 * Liczone RAZ, w tle, po rozstrzygnięciu auta ([LeadSimilarPrecomputeListener]) —
 * tym samym wyzwalaczem co „Podobne zlecenia", z których czerpie ceny.
 */
@Service
class LeadServiceSuggestionService(
    private val leadRepository: LeadRepository,
    private val itemRepository: LeadServiceItemRepository,
    private val serviceRepository: ServiceRepository,
    private val intentService: LeadServiceIntentService,
    private val matchesRepository: LeadSimilarMatchesRepository,
    private val visitRepository: SimilarVisitReadRepository,
    private val itemsService: LeadServiceItemsService,
    private val quoteSync: LeadQuoteSyncService,
    private val eventPublisher: ApplicationEventPublisher,
    /** Zdjęte podpowiedzi nie mają prawa podpowiadać cen — patrz [historicalPriceByNameKey]. */
    private val feedbackRepository: VisitMatchFeedbackRepository? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Przelicza sugestie leada od nowa. Kasuje poprzednie SUGGESTED (odrzucenia i tak
     * są twarde, akceptacje mają już status ACCEPTED i tu ich nie ruszamy), po czym
     * podsuwa świeży zestaw. [force] przepytuje model z pominięciem dziennika intencji
     * — po dopisaniu brakującej usługi do cennika to jedyny sposób, by ją zasugerować.
     */
    @Transactional
    fun recompute(studioId: StudioId, leadId: UUID, force: Boolean) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value) ?: return

        // Czysta karta na sugestie; ręczne i już zaakceptowane pozycje zostają.
        itemRepository.deleteByLeadIdAndStatusAndSource(
            leadId, LeadServiceItemStatus.SUGGESTED, LeadServiceItemSource.AI
        )

        val intent = intentService.intentFor(studioId, leadId, lead.initialMessage, force, lead.threadId)

        // „Prawie trafienie": cennik ma tę samą OPERACJĘ na INNEJ CZĘŚCI auta.
        // Zamiast pewnie brzmiącej pozycji CATALOG (599,99 zł za DRZWI przy pytaniu
        // o FOTEL) powstaje pozycja BEZ CENY z notatką — interfejs wymusi kwotę,
        // a właściciel widzi, DLACZEGO automat nie podał liczby.
        if (intent != null && intent.status == ServiceIntentStatus.CATALOG_NEAR_MISS) {
            val alreadySuggested = itemRepository.findByLeadIdOrderByCreatedAtAsc(leadId).isNotEmpty()
            if (!alreadySuggested) {
                itemRepository.save(
                    LeadServiceItemEntity(
                        id = UUID.randomUUID(),
                        studioId = studioId.value,
                        leadId = leadId,
                        serviceId = null,
                        name = NEAR_MISS_ITEM_NAME,
                        priceGross = null,
                        note = NEAR_MISS_NOTE,
                        quantity = 1,
                        status = LeadServiceItemStatus.SUGGESTED,
                        source = LeadServiceItemSource.AI,
                        priceSource = LeadServicePriceSource.PENDING
                    )
                )
            }
            itemsService.recomputeEstimatedValue(lead)
            publishChanged(lead)
            return
        }

        // Awaria modelu (null) albo robota spoza cennika/brak usługi → bez sugestii.
        // Przy NOT_IN_CATALOG to świadome: nie podsuwamy cen innej roboty.
        if (intent == null || intent.status != ServiceIntentStatus.MATCHED || intent.matchedServiceIds.isEmpty()) {
            itemsService.recomputeEstimatedValue(lead)
            publishChanged(lead)
            return
        }

        // Nie sugeruj tego, co już jest na liście (ręczne albo wcześniej zaakceptowane).
        val alreadyOnLead = itemRepository.findByLeadIdOrderByCreatedAtAsc(leadId)
            .mapNotNull { it.serviceId }
            .toSet()

        val services = serviceRepository
            .findAllByIdInAndStudioId(intent.matchedServiceIds, studioId.value)
            .filter { it.isActive && !it.isPackage && it.id !in alreadyOnLead }
        if (services.isEmpty()) {
            itemsService.recomputeEstimatedValue(lead)
            publishChanged(lead)
            return
        }

        val historicalPrice = historicalPriceByNameKey(studioId, leadId)

        services.forEach { service ->
            val (price, priceSource) = priceFor(service, historicalPrice)
            itemRepository.save(
                LeadServiceItemEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    leadId = leadId,
                    serviceId = service.id,
                    name = service.name.take(200),
                    priceGross = price,
                    priceNet = if (priceSource == LeadServicePriceSource.CATALOG) service.basePriceNet else null,
                    vatRate = service.vatRate,
                    note = null,
                    quantity = 1,
                    status = LeadServiceItemStatus.SUGGESTED,
                    source = LeadServiceItemSource.AI,
                    priceSource = priceSource
                )
            )
        }

        itemsService.recomputeEstimatedValue(lead)
        publishChanged(lead)
        log.info("[LEAD_SUGGEST] Lead {} — podsunięto {} usług", leadId, services.size)
    }

    /**
     * [Akceptuj] jedną sugestię. Usługa z wyceną niestandardową bez ceny MUSI dostać
     * kwotę — albo z [priceGross] w żądaniu, albo odmawiamy (interfejs wymusi ją inline).
     * Zaakceptowana pozycja staje się częścią wyceny i synchronizuje się z rezerwacją.
     */
    @Transactional
    fun accept(studioId: StudioId, leadId: UUID, itemId: UUID, priceGross: Long?, userName: String) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        val item = itemRepository.findByLeadIdAndIdAndStatus(leadId, itemId, LeadServiceItemStatus.SUGGESTED)
            ?: throw NotFoundException("Nie znaleziono sugestii")

        if (priceGross != null) {
            if (priceGross < 0) throw ValidationException("Cena nie może być ujemna")
            item.priceGross = priceGross
            item.priceSource = LeadServicePriceSource.MANUAL
        }
        if (item.priceGross == null) {
            // Zakaz halucynacji dochodzi do skutku właśnie tu: pozycja bez ceny nie
            // przechodzi do wyceny, dopóki człowiek nie poda kwoty.
            throw ValidationException("Podaj kwotę dla usługi „${item.name}”")
        }

        item.status = LeadServiceItemStatus.ACCEPTED
        itemRepository.save(item)
        itemsService.recomputeEstimatedValue(lead)
        quoteSync.pushToAppointment(lead)
        publishChanged(lead)
        log.info("[LEAD_SUGGEST] Lead {} — {} zaakceptował sugestię {}", leadId, userName, item.name)
    }

    /** [Odrzuć] — kasuje sugestię twardo (decyzja właściciela produktu). */
    @Transactional
    fun reject(studioId: StudioId, leadId: UUID, itemId: UUID) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        val item = itemRepository.findByLeadIdAndIdAndStatus(leadId, itemId, LeadServiceItemStatus.SUGGESTED)
            ?: throw NotFoundException("Nie znaleziono sugestii")

        itemRepository.delete(item)
        itemsService.recomputeEstimatedValue(lead)
        publishChanged(lead)
    }

    /**
     * „Stwórz rezerwację" traktuje wszystkie NIEodrzucone sugestie jako zaakceptowane.
     * Blokuje, gdy któraś czeka na kwotę: zwraca ich nazwy, żeby interfejs wymusił
     * kwoty PRZED założeniem rezerwacji, a nie wpuścił pozycji bez ceny.
     *
     * @return liczba sugestii przeniesionych do wyceny.
     */
    @Transactional
    fun acceptAllForBooking(studioId: StudioId, leadId: UUID): Int {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        val suggestions = itemRepository.findByLeadIdAndStatus(leadId, LeadServiceItemStatus.SUGGESTED)
        if (suggestions.isEmpty()) return 0

        val pending = suggestions.filter { it.priceGross == null }
        if (pending.isNotEmpty()) {
            throw PendingSuggestionPriceException(pending.map { it.name })
        }

        suggestions.forEach { it.status = LeadServiceItemStatus.ACCEPTED }
        itemRepository.saveAll(suggestions)
        itemsService.recomputeEstimatedValue(lead)
        quoteSync.pushToAppointment(lead)
        publishChanged(lead)
        return suggestions.size
    }

    /**
     * Cena TEJ usługi z historii: MEDIANA po wizytach z „Podobnych zleceń" leada,
     * które zawierają pozycję o tym samym name_key. Czytana na żywo z wizyt (a nie
     * z kopii w sygnaturze), więc zawsze prawdziwa i bez okna przestemplowania.
     *
     * Cztery poprawki względem pierwotnej wersji (docs/similar-visits-redesign.md §1.10):
     *  - MEDIANA zamiast pojedynczej najnowszej obserwacji — jedna wizyta nie jest ceną;
     *  - kwota pozycji liczona regułą [pl.detailing.crm.visit.domain.Visit.effectiveGrossAmount]
     *    (PENDING/ADD i REJECTED wypadają, PENDING/EDIT bierze snapshot) — kwota
     *    sugerowana pochodzi z tej samej reguły, co kwota zlecenia na ekranie;
     *  - cena 0 zł nie jest ceną i nie przechodzi (dotąd szła do wyceny i rezerwacji);
     *  - podpowiedzi ZDJĘTE „X-em" na tym leadzie nie podpowiadają cen.
     */
    private fun historicalPriceByNameKey(studioId: StudioId, leadId: UUID): Map<String, Long> {
        val dismissed = feedbackRepository?.findByLeadId(leadId).orEmpty().map { it.visitId }.toSet()
        val visitIds = matchesRepository.findById(leadId).orElse(null)
            ?.parsedMatches()
            ?.map { it.visitId }
            ?.filterNot { it in dismissed }
            ?: emptyList()
        if (visitIds.isEmpty()) return emptyMap()

        return visitRepository.findByStudioIdAndIdIn(studioId.value, visitIds)
            .flatMap { visit ->
                val domain = visit.toDomain()
                domain.serviceItems
                    .filter { it.serviceName.isNotBlank() }
                    .mapNotNull { item ->
                        domain.effectiveGrossAmount(item)?.amountInCents
                            ?.takeIf { it > 0 }
                            ?.let { serviceNameKey(item.serviceName) to it }
                    }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, prices) -> median(prices) }
    }

    private fun median(prices: List<Long>): Long {
        val sorted = prices.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun priceFor(
        service: ServiceEntity,
        historicalPrice: Map<String, Long>
    ): Pair<Long?, LeadServicePriceSource> = when {
        // Stała cena z cennika — prosto.
        !service.requireManualPrice -> service.basePriceGross to LeadServicePriceSource.CATALOG
        // Wycena niestandardowa: cena TYLKO z historii, nigdy zmyślona.
        else -> historicalPrice[serviceNameKey(service.name)]
            ?.let { it to LeadServicePriceSource.HISTORY }
            ?: (null to LeadServicePriceSource.PENDING)
    }

    private fun publishChanged(lead: LeadEntity) {
        eventPublisher.publishEvent(
            LeadChangedEvent(source = this, studioId = StudioId(lead.studioId), leadId = LeadId(lead.id))
        )
    }

    companion object {
        /** Pozycja-zaślepka przy CATALOG_NEAR_MISS — interfejs wymusi kwotę przy akceptacji. */
        const val NEAR_MISS_ITEM_NAME = "Wycena indywidualna"
        const val NEAR_MISS_NOTE =
            "Klient pyta o robotę podobną do pozycji cennika, ale na innej części auta — " +
                "automat nie podał ceny, żeby nie podpowiedzieć kwoty innej roboty."
    }
}

/** Rezerwacji nie wolno założyć, dopóki sugestie z tej listy czekają na kwotę. */
class PendingSuggestionPriceException(val serviceNames: List<String>) :
    RuntimeException("Sugestie bez ceny: ${serviceNames.joinToString(", ")}")
