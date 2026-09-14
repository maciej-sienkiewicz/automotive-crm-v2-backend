package pl.detailing.crm.costs

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import java.util.UUID

/**
 * Stosowanie reguł auto-przypisania kosztów (dostawca po NIP → kategoria).
 *
 * Wcześniej reguły były stosowane wyłącznie z [CostCategoryController] — przy
 * tworzeniu reguły (backfill istniejących faktur) i przy ręcznym „Zastosuj wszystkie".
 * Pobranie NOWEJ faktury z KSeF nie odpalało niczego, więc reguła dodana wcześniej
 * nie przypisywała faktur, które przyszły później. Ten serwis jest wspólnym punktem:
 * woła go kontroler oraz [pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler] po
 * pobraniu i uzupełnieniu faktur.
 *
 * Dopasowanie NIP idzie po samych cyfrach: reguła trzyma cyfry, a faktura NIP w
 * formacie z KSeF — bez normalizacji faktura z prefiksem „PL" nigdy by się nie
 * dopasowała.
 */
@Service
class SupplierAutoRuleService(
    private val autoRuleRepository: SupplierAutoRuleRepository,
    private val invoiceRepository: KsefInvoiceRepository,
    private val invoiceItemRepository: KsefInvoiceItemRepository,
    private val assignmentRepository: CostItemAssignmentRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val NON_DIGITS = Regex("\\D")

        /** NIP zredukowany do samych cyfr — pusty string, gdy null/brak cyfr. */
        fun normalizeNip(nip: String?): String = nip?.replace(NON_DIGITS, "") ?: ""
    }

    /**
     * Przypisuje nieprzypisane pozycje wszystkich faktur pasujących do [rule]
     * (po cyfrach NIP) do jej kategorii. Już przypisane pomija (ręczne nadpisania
     * zostają). Zwraca liczbę nowo przypisanych pozycji.
     */
    fun applyRule(rule: SupplierAutoRuleEntity, studioId: UUID): Int {
        val invoices = invoiceRepository.findBySellerNipDigits(studioId, rule.sellerNip)
        return assignItems(invoices, rule.categoryId, studioId)
    }

    /** Stosuje wszystkie reguły studia do bieżących nieprzypisanych pozycji. */
    fun applyAllRules(studioId: UUID): Int =
        autoRuleRepository.findByStudioId(studioId).sumOf { applyRule(it, studioId) }

    /**
     * Po pobraniu/uzupełnieniu faktur: dla każdej reguły, której NIP pojawił się
     * wśród podanych faktur, przypisuje ich pozycje. Idempotentne. Zwraca liczbę
     * nowo przypisanych pozycji.
     */
    fun applyRulesForInvoices(studioId: UUID, invoices: List<KsefInvoiceEntity>): Int {
        if (invoices.isEmpty()) return 0
        val freshNips = invoices.mapNotNull { normalizeNip(it.sellerNip).ifBlank { null } }.toSet()
        if (freshNips.isEmpty()) return 0

        val matchingRules = autoRuleRepository.findByStudioId(studioId).filter { it.sellerNip in freshNips }
        if (matchingRules.isEmpty()) return 0

        val count = matchingRules.sumOf { applyRule(it, studioId) }
        if (count > 0) {
            log.info(
                "Auto-assigned {} cost item(s) via {} supplier rule(s) after fetch — studio={}",
                count, matchingRules.size, studioId
            )
        }
        return count
    }

    private fun assignItems(invoices: List<KsefInvoiceEntity>, categoryId: UUID, studioId: UUID): Int {
        if (invoices.isEmpty()) return 0
        val items = invoiceItemRepository.findByInvoiceIdIn(invoices.map { it.id })
        if (items.isEmpty()) return 0

        val alreadyAssigned = assignmentRepository.findByStudioId(studioId).map { it.ksefItemId }.toSet()
        var count = 0
        items.forEach { item ->
            if (item.id !in alreadyAssigned) {
                assignmentRepository.save(
                    CostItemAssignmentEntity(
                        categoryId = categoryId,
                        ksefItemId = item.id,
                        invoiceId = item.invoiceId,
                        studioId = studioId
                    )
                )
                count++
            }
        }
        return count
    }
}
