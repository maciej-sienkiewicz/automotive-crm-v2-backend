package pl.detailing.crm.leads.update

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
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * @param serviceId Pozycja z katalogu; null oznacza pozycję wpisaną ręcznie.
 * @param priceGross Nadpisanie ceny w groszach; null bierze cenę z katalogu.
 * @param priceNet Netto w groszach — zapamiętywane tylko po to, żeby edytor wyceny
 *   wrócił do tej samej liczby, którą wpisał człowiek. Suma leada liczy się z brutto.
 * @param vatRate Stawka VAT w procentach; -1 to „zwolniony".
 * @param note Uwaga do pozycji.
 */
data class LeadServiceItemInput(
    val serviceId: UUID?,
    val name: String?,
    val priceGross: Long?,
    val quantity: Int = 1,
    val priceNet: Long? = null,
    val vatRate: Int? = null,
    val note: String? = null
)

/**
 * Owns the priced service list of a lead. Prices are copied (frozen) from the
 * catalogue at assignment time, and the lead's estimated value is the recomputed sum —
 * later price-list edits never move an already-quoted lead.
 */
@Service
class LeadServiceItemsService(
    private val itemRepository: LeadServiceItemRepository,
    private val leadRepository: LeadRepository,
    private val serviceRepository: ServiceRepository,
    private val quoteSync: LeadQuoteSyncService,
    private val eventPublisher: ApplicationEventPublisher
) {

    /**
     * Replaces the human-managed list; returns the new total (grosze).
     *
     * Dotyka WYŁĄCZNIE pozycji NIE-sugerowanych. Żywe sugestie AI (status=SUGGESTED)
     * mają własny cykl życia (accept/reject/refresh) i ludzki zapis edytora nie ma
     * prawa ich skasować przy okazji.
     */
    @Transactional
    fun replaceItems(lead: LeadEntity, inputs: List<LeadServiceItemInput>): Long {
        itemRepository.deleteByLeadIdAndStatusNot(lead.id, LeadServiceItemStatus.SUGGESTED)

        inputs.forEach { input ->
            if (input.quantity < 1) throw ValidationException("Ilość musi być większa od zera")

            val catalogEntry = input.serviceId?.let {
                serviceRepository.findByIdAndStudioId(it, lead.studioId)
                    ?: throw ValidationException("Usługa nie istnieje w katalogu")
            }
            val name = input.name?.takeIf { it.isNotBlank() }
                ?: catalogEntry?.name
                ?: throw ValidationException("Pozycja bez nazwy — wybierz usługę lub wpisz nazwę")
            val price = input.priceGross
                ?: catalogEntry?.basePriceGross
                ?: throw ValidationException("Pozycja „$name” nie ma ceny")
            if (price < 0) throw ValidationException("Cena nie może być ujemna")

            itemRepository.save(
                LeadServiceItemEntity(
                    id = UUID.randomUUID(),
                    studioId = lead.studioId,
                    leadId = lead.id,
                    serviceId = catalogEntry?.id,
                    name = name.take(200),
                    priceGross = price,
                    priceNet = input.priceNet?.takeIf { it >= 0 },
                    vatRate = input.vatRate,
                    note = input.note?.trim()?.takeIf { it.isNotBlank() }?.take(500),
                    quantity = input.quantity,
                    status = LeadServiceItemStatus.ACCEPTED,
                    source = LeadServiceItemSource.MANUAL,
                    priceSource = if (input.priceGross != null) LeadServicePriceSource.MANUAL else LeadServicePriceSource.CATALOG
                )
            )
        }

        val total = recomputeEstimatedValue(lead)

        // Lead z terminem ma tę samą listę usług w kalendarzu — poprawka wyceny,
        // która tam nie dojdzie, to kwota uzgodniona z klientem i niewidoczna dla
        // tego, kto będzie auto przyjmował.
        quoteSync.pushToAppointment(lead)

        eventPublisher.publishEvent(
            LeadChangedEvent(
                source = this,
                studioId = StudioId(lead.studioId),
                leadId = LeadId(lead.id)
            )
        )
        return total
    }

    /**
     * Przelicza i zapisuje potencjał leada: suma brutto WSZYSTKICH pozycji —
     * ręcznych i sugerowanych naraz. Sugestia bez ceny (czeka na kwotę) liczy się
     * jako zero, żeby nie zawyżać potencjału liczbą, której jeszcze nie ma.
     *
     * To jest jedyne miejsce liczące estimated_value — analityka czyta wyłącznie tę
     * zdenormalizowaną kolumnę, więc „wliczaj ręczne I sugerowane" domyka się tutaj.
     */
    @Transactional
    fun recomputeEstimatedValue(lead: LeadEntity): Long {
        val total = itemRepository.findByLeadIdOrderByCreatedAtAsc(lead.id)
            .sumOf { (it.priceGross ?: 0L) * it.quantity }
        lead.estimatedValue = total
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        return total
    }
}
