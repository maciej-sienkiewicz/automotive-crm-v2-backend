package pl.detailing.crm.ksef.revenue.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.revenue.domain.DuplicateStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository

/**
 * Heurystyka podwójnego fakturowania: faktura EXTERNAL (wystawiona poza CRM,
 * pobrana pullem) vs faktura CRM o tym samym NIP-ie nabywcy, identycznej kwocie
 * brutto i dacie wystawienia w oknie ±[MATCH_WINDOW_DAYS] dni.
 *
 * System wyłącznie OZNACZA parę jako SUSPECTED — obie faktury są prawnie wiążące
 * i żadna nie jest automatycznie scalana ani ukrywana. Decyzję (CONFIRMED_DUPLICATE
 * → wykluczenie ze statystyk + korekta do zera po stronie klienta, albo DISMISSED)
 * podejmuje użytkownik przez API. Rozstrzygnięte pary nie są oznaczane ponownie.
 */
@Service
class RevenueDuplicateDetector(
    private val repository: KsefRevenueInvoiceRepository
) {
    private val log = LoggerFactory.getLogger(RevenueDuplicateDetector::class.java)

    companion object {
        const val MATCH_WINDOW_DAYS = 7L
    }

    /**
     * Sprawdza nowo pobraną fakturę EXTERNAL przeciwko fakturom CRM.
     * @return true, gdy oznaczono podejrzaną parę
     */
    fun checkExternalInvoice(external: KsefRevenueInvoiceEntity): Boolean {
        if (external.invoiceType == RevenueInvoiceType.KOR) return false
        if (external.duplicateStatus != DuplicateStatus.NONE) return false
        val buyerNip = external.buyerNip?.takeIf { it.isNotBlank() } ?: return false
        if (external.totalGross == 0L) return false

        val match = repository.findDuplicateCandidates(
            studioId = external.studioId,
            buyerNip = buyerNip,
            totalGross = external.totalGross,
            dateFrom = external.issueDate.minusDays(MATCH_WINDOW_DAYS),
            dateTo = external.issueDate.plusDays(MATCH_WINDOW_DAYS)
        ).firstOrNull { it.id != external.id && it.ksefNumber != external.ksefNumber }
            ?: return false

        external.duplicateStatus = DuplicateStatus.SUSPECTED
        external.duplicateOfId = match.id
        match.duplicateStatus = DuplicateStatus.SUSPECTED
        match.duplicateOfId = external.id
        repository.save(external)
        repository.save(match)

        log.warn(
            "Podejrzenie podwójnego fakturowania: CRM {} ({}) vs EXTERNAL {} ({}) — " +
                "nabywca NIP={}, brutto={} gr",
            match.invoiceNumber, match.ksefNumber,
            external.invoiceNumber, external.ksefNumber,
            buyerNip, external.totalGross
        )
        return true
    }
}
