package pl.detailing.crm.leads.appointment

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VatRate
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Trzyma wycenę leada i listę usług powiązanej rezerwacji w jednym stanie.
 *
 * To jest jedna lista opisana w dwóch tabelach, nie dwie listy. Usługę dopisaną
 * w rezerwacji dzień po jej założeniu ustalono w tej samej rozmowie co resztę —
 * a lead pokazywał wtedy wycenę sprzed tej rozmowy i cicho zaniżał wartość
 * zamkniętego zapytania. Tak samo w drugą stronę: poprawka wyceny na leadzie,
 * która nie doszła do kalendarza, to kwota uzgodniona z klientem i niewidoczna
 * dla tego, kto będzie auto przyjmował.
 *
 * PĘTLI NIE MA Z BUDOWY. Obie metody piszą wyłącznie po DRUGIEJ stronie i robią to
 * repozytorium, nie przez handler tamtej strony — więc żadna nie może wywołać
 * drugiej. To celowo tańsze niż flaga „to jest zapis z synchronizacji”, którą
 * trzeba by przekazywać przez każdą warstwę i której pierwsze przeoczenie daje
 * nieskończoną wymianę zapisów między dwoma modułami.
 */
@Service
class LeadQuoteSyncService(
    private val leadRepository: LeadRepository,
    private val leadItemRepository: LeadServiceItemRepository,
    private val appointmentRepository: AppointmentRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(LeadQuoteSyncService::class.java)

    /**
     * Rezerwacja → lead. Wołane po każdym zapisie listy usług rezerwacji.
     * Rezerwacja jest tu stanem wiodącym: to ona pojedzie do przyjęcia pojazdu.
     */
    @Transactional
    fun pullFromAppointment(appointment: AppointmentEntity) {
        val lead = leadRepository.findByAppointmentId(appointment.id) ?: return

        leadItemRepository.deleteByLeadId(lead.id)
        var total = 0L
        appointment.lineItems.forEach { item ->
            leadItemRepository.save(
                LeadServiceItemEntity(
                    id = UUID.randomUUID(),
                    studioId = lead.studioId,
                    leadId = lead.id,
                    serviceId = item.serviceId,
                    name = item.serviceName.take(200),
                    // Kwoty PO rabacie: wycena leada zawsze trzymała cenę uzgodnioną,
                    // a nie katalogową — rabat jest szczegółem rezerwacji, nie oferty.
                    priceGross = item.finalPriceGross,
                    priceNet = item.finalPriceNet,
                    vatRate = item.vatRate,
                    note = item.customNote?.trim()?.takeIf { it.isNotBlank() }?.take(500),
                    quantity = 1
                )
            )
            total += item.finalPriceGross
        }

        lead.estimatedValue = total
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        publishChanged(lead)

        log.info(
            "[LEADS] Quote synced from appointment: leadId={}, appointmentId={}, items={}, total={}",
            lead.id, appointment.id, appointment.lineItems.size, total
        )
    }

    /** Lead → rezerwacja. Wołane po każdej podmianie wyceny leada. */
    @Transactional
    fun pushToAppointment(lead: LeadEntity) {
        val appointmentId = lead.appointmentId ?: return
        val appointment = appointmentRepository.findByIdAndStudioId(appointmentId, lead.studioId) ?: return
        // Rezerwacja przerobiona na wizytę albo odwołana ma już własne życie —
        // wycena leada nie ma prawa ruszać listy, po której ktoś wydał auto.
        if (appointment.status == AppointmentStatus.CONVERTED || appointment.status == AppointmentStatus.CANCELLED) return

        val items = leadItemRepository.findByLeadIdOrderByCreatedAtAsc(lead.id)
        // Stawka spoza słownika nie ma jak trafić do wiersza rezerwacji. Przerywamy
        // całą synchronizację, zamiast podmieniać ją na domyślną: rezerwacja z cichaczem
        // zmienioną stawką VAT to gorszy błąd niż rezerwacja niezsynchronizowana.
        val rates = items.associate { it.id to vatRateOrNull(it.vatRate ?: DEFAULT_VAT_RATE) }
        if (rates.values.any { it == null }) {
            log.warn(
                "[LEADS] Quote sync skipped: leadId={} has an unknown VAT rate on at least one item",
                lead.id
            )
            return
        }

        val lineItems = items.flatMap { item ->
            val vatRate = rates.getValue(item.id)!!
            val net = resolveNet(vatRate, item.priceGross, item.priceNet)
            // Pozycja leada zna ilość, wiersz rezerwacji nie — rozwijamy ją na wiersze.
            // Suma zostaje ta sama co do grosza, a każdy wiersz da się osobno opisać.
            List(item.quantity.coerceIn(1, MAX_EXPANDED_ROWS)) { _ ->
                AppointmentLineItem.create(
                    serviceId = item.serviceId?.let { ServiceId(it) },
                    serviceName = item.name,
                    basePriceNet = Money(net),
                    vatRate = vatRate,
                    // Rabat jest już wliczony w kwotę leada, więc wiersz rezerwacji
                    // dostaje ją jako cenę bazową bez korekty. Rozbicie „cena katalogowa
                    // + rabat" nie przetrwało zapisu na leadzie i zmyślanie go tutaj
                    // pokazywałoby rabat, którego nikt nie udzielił.
                    adjustmentType = AdjustmentType.PERCENT,
                    adjustmentValue = 0L,
                    customNote = item.note,
                    basePriceGross = Money(item.priceGross)
                )
            }
        }

        appointment.lineItems.clear()
        appointment.lineItems.addAll(lineItems.map { AppointmentLineItemEntity.fromDomain(it, appointment) })
        appointment.updatedAt = Instant.now()
        appointmentRepository.save(appointment)

        log.info(
            "[LEADS] Quote synced to appointment: leadId={}, appointmentId={}, items={}",
            lead.id, appointmentId, lineItems.size
        )
    }

    private fun publishChanged(lead: LeadEntity) {
        eventPublisher.publishEvent(
            LeadChangedEvent(
                source = this,
                studioId = StudioId(lead.studioId),
                leadId = LeadId(lead.id)
            )
        )
    }

    private companion object {
        /** Stawka dla pozycji sprzed V75, które znały wyłącznie brutto. */
        const val DEFAULT_VAT_RATE = 23

        /** Zapora przed ilością z literówki — ta sama, co po stronie interfejsu. */
        const val MAX_EXPANDED_ROWS = 50

        /** Stawka ze słownika albo null — bez wyjątku, bo to nie jest miejsce na awarię. */
        fun vatRateOrNull(rate: Int): VatRate? = VatRate.entries.find { it.rate == rate }

        /**
         * Netto wiersza rezerwacji. Zapisane netto leada bierzemy tylko wtedy, gdy
         * zgadza się z brutto co do grosza — inaczej wiersz nie przeszedłby kontroli
         * spójności w [AppointmentLineItem] i cała edycja padłaby wyjątkiem zamiast
         * zapisać się z groszem różnicy. Brutto jest wartością wiodącą, więc to netto
         * ustępuje, a nie odwrotnie.
         */
        fun resolveNet(vatRate: VatRate, grossCents: Long, storedNet: Long?): Long {
            if (storedNet != null &&
                abs(vatRate.calculateGrossAmount(Money(storedNet)).amountInCents - grossCents) <= 1
            ) {
                return storedNet
            }
            val multiplier = if (vatRate == VatRate.VAT_ZW) 1.0 else 1.0 + vatRate.rate.toDouble() / 100.0
            return Math.round(grossCents / multiplier)
        }
    }
}
