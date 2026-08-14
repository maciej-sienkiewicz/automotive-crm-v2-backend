package pl.detailing.crm.ksef.revenue.issue

import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import java.time.LocalDate

/**
 * Nadaje numery dokumentów przychodowych w formacie {PREFIX}/{rok}/{seq:04d}
 * (np. FV/2026/0007, FK/2026/0001).
 *
 * Zakresem unikalności jest **NIP sprzedawcy**, nie studio. KSeF rejestruje
 * faktury w kontekście NIP-u i odrzuca kodem 440 („Duplikat faktury") dokument
 * o numerze już zarejestrowanym pod tym NIP-em — niezależnie od tego, które
 * studio w CRM go wystawiło. Numeracja per studio powodowała kolizje wszędzie
 * tam, gdzie kilka studiów dzieli jeden NIP.
 *
 * Sekwencja to MAX+1, a nie COUNT+1: numer raz przydzielony nigdy nie wraca,
 * nawet jeśli dokument został odrzucony przez KSeF albo usunięty z bazy.
 * Ciągłość numeracji (brak luk) nie jest wymagana przez przepisy — wymagana
 * jest jednoznaczna identyfikacja faktury (art. 106e ust. 1 pkt 2 ustawy o VAT).
 *
 * Ostateczną gwarancją unikalności jest indeks
 * `ux_ksef_revenue_invoices_seller_number` — przy wyścigu dwóch równoległych
 * wystawień zapis pada, a handler ponawia próbę z kolejnym numerem.
 */
@Component
class RevenueInvoiceNumberGenerator(
    private val invoiceRepository: KsefRevenueInvoiceRepository
) {

    fun next(sellerNip: String, prefix: String, issueDate: LocalDate): String {
        val year = issueDate.year
        val pattern = "^$prefix/$year/[0-9]+${'$'}"
        val maxSequence = invoiceRepository.findMaxNumberSequence(sellerNip, pattern)
        return "$prefix/$year/${(maxSequence + 1).toString().padStart(4, '0')}"
    }
}
