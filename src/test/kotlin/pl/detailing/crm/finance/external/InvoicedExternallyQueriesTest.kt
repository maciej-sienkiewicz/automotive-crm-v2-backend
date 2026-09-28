package pl.detailing.crm.finance.external

import io.mockk.mockk
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query
import pl.detailing.crm.finance.duplicates.DocumentDuplicateLinkRepository
import pl.detailing.crm.finance.income.IncomeDocumentsRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import kotlin.reflect.KClass

/**
 * Sprzedaż fakturowana przez księgowość (invoiced_externally) nie może wrócić do sum
 * przychodu: przychód niesie faktura księgowości pobrana z KSeF, a oba zapisy naraz to
 * ta sama sprzedaż dwa razy — dokładnie to, na co skarżył się biznes. Test pilnuje
 * każdego zapytania, które sumuje albo pokazuje przychód z dokumentów CRM.
 */
class InvoicedExternallyQueriesTest {

    private fun query(type: KClass<*>, method: String): String =
        type.java.methods.first { it.name == method }.getAnnotation(Query::class.java).value

    @Test
    fun `kafle finansow - przychod, naleznosci i przeterminowane pomijaja sprzedaz ksiegowosci`() {
        assertTrue("d.invoicedExternally = false" in query(FinancialDocumentRepository::class, "sumNet"))
        assertTrue("d.invoicedExternally = false" in query(FinancialDocumentRepository::class, "countOverdue"))
    }

    @Test
    fun `lista przychodow pokazuje fakture ksiegowosci z KSeF, a nie zapis platnosci`() {
        val finance = IncomeDocumentsRepository(mockk<EntityManager>()).selectColumns.substringAfter("UNION ALL")
        assertTrue("d.invoiced_externally = FALSE" in finance)
    }

    @Test
    fun `automat duplikatow nie paruje sprzedazy ksiegowosci z faktura z KSeF`() {
        assertTrue(
            "d.invoiced_externally = FALSE" in query(DocumentDuplicateLinkRepository::class, "findRevenueVsFinancialCandidates")
        )
    }

    @Test
    fun `raport form platnosci liczy zapis platnosci - faktura ksiegowosci do niego nie wchodzi`() {
        assertFalse("invoiced_externally" in query(FinancialDocumentRepository::class, "findPaidIncomeForReport"))
    }
}
