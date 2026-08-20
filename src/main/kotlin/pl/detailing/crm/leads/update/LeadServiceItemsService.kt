package pl.detailing.crm.leads.update

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
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
    private val eventPublisher: ApplicationEventPublisher
) {

    /** Replaces the whole list; returns the new total (grosze). */
    @Transactional
    fun replaceItems(lead: LeadEntity, inputs: List<LeadServiceItemInput>): Long {
        itemRepository.deleteByLeadId(lead.id)

        var total = 0L
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
                    quantity = input.quantity
                )
            )
            total += price * input.quantity
        }

        lead.estimatedValue = total
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)

        eventPublisher.publishEvent(
            LeadChangedEvent(
                source = this,
                studioId = StudioId(lead.studioId),
                leadId = LeadId(lead.id)
            )
        )
        return total
    }
}
