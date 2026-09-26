package pl.detailing.crm.visit.settlement

import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import java.util.UUID

/**
 * Która faktura KSeF wizyty wciąż obowiązuje.
 *
 * Jedna reguła dla poprawki rozliczenia i dla widoku wizyty. Zgłoszenie z produkcji:
 * wizytę rozliczoną fakturą poprawiono na paragon za gotówkę, a nagłówek wizyty dalej
 * proponował „Podgląd faktury” — bo widok wizyty miał własną, starszą regułę, w której
 * faktura wyzerowana korektą albo anulowana wciąż była „fakturą wizyty”.
 */
internal object LiveRevenueInvoices {

    val DEAD = setOf(KsefRevenueStatus.REJECTED, KsefRevenueStatus.CANCELLED)

    /** Faktury VAT wizyty, które nie są anulowane, odrzucone ani wyzerowane korektą. */
    fun of(invoices: List<KsefRevenueInvoiceEntity>): List<KsefRevenueInvoiceEntity> {
        val corrections = invoices.filter { it.invoiceType == RevenueInvoiceType.KOR && it.ksefStatus !in DEAD }
        return invoices.filter { invoice ->
            invoice.invoiceType == RevenueInvoiceType.VAT &&
                invoice.source == RevenueSource.CRM &&
                invoice.ksefStatus !in DEAD &&
                // Faktura wyzerowana korektą przestała obowiązywać — zostaje tylko w historii.
                invoice.totalGross + corrections.filter { it.originalInvoiceId == invoice.id }.sumOf { it.totalGross } != 0L
        }
    }

    /**
     * Faktura do podglądu z nagłówka wizyty albo `null`, gdy żadna nie obowiązuje.
     *
     * Najpierw faktura przypięta do obowiązującego dokumentu finansowego, potem najnowsza
     * obowiązująca (np. faktura do paragonu, która dokumentu nie ma). Faktura odrzucona
     * przez KSeF zostaje do podglądu tylko wtedy, gdy obowiązujący dokument wciąż na nią
     * wskazuje — użytkownik musi zobaczyć powód odrzucenia. Nie ma ślepego „weź pierwszą
     * lepszą”: po poprawce taka reguła wskazywała fakturę, której już nie ma.
     */
    fun previewInvoiceId(invoices: List<KsefRevenueInvoiceEntity>, linkedInvoiceId: UUID?): UUID? {
        val live = of(invoices)
        return (
            live.firstOrNull { it.id == linkedInvoiceId }
                ?: live.lastOrNull()
                ?: invoices.firstOrNull { it.id == linkedInvoiceId && it.ksefStatus == KsefRevenueStatus.REJECTED }
            )?.id
    }
}
