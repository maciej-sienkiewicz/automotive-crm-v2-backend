package pl.detailing.crm.ksef.revenue.issue

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import java.time.LocalDate

class RevenueInvoiceNumberGeneratorTest {

    private val repository = mockk<KsefRevenueInvoiceRepository>()
    private val generator = RevenueInvoiceNumberGenerator(repository)

    @Test
    fun `nadaje kolejny numer po najwyzszym w serii`() {
        every { repository.findMaxNumberSequence(any(), any()) } returns 7
        assertEquals(
            "FV/2026/0008",
            generator.next("1238394445", "FV", LocalDate.of(2026, 8, 14))
        )
    }

    @Test
    fun `pierwsza faktura w roku dostaje numer 0001`() {
        every { repository.findMaxNumberSequence(any(), any()) } returns 0
        assertEquals("FV/2026/0001", generator.next("1238394445", "FV", LocalDate.of(2026, 1, 2)))
    }

    @Test
    fun `numer powyzej 9999 nie jest obcinany`() {
        every { repository.findMaxNumberSequence(any(), any()) } returns 9999
        assertEquals("FV/2026/10000", generator.next("1238394445", "FV", LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `zakresem sekwencji jest NIP sprzedawcy, prefiks i rok`() {
        val nip = slot<String>()
        val pattern = slot<String>()
        every { repository.findMaxNumberSequence(capture(nip), capture(pattern)) } returns 0

        generator.next("1238394445", "FK", LocalDate.of(2027, 3, 1))

        assertEquals("1238394445", nip.captured)
        assertEquals("^FK/2027/[0-9]+${'$'}", pattern.captured)
    }

    @Test
    fun `wzorzec dopasowuje wylacznie wlasna serie`() {
        val pattern = slot<String>()
        every { repository.findMaxNumberSequence(any(), capture(pattern)) } returns 0
        generator.next("1238394445", "FV", LocalDate.of(2026, 5, 5))

        val regex = Regex(pattern.captured)
        assertTrue(regex.matches("FV/2026/0007"))
        assertTrue(regex.matches("FV/2026/10000"))
        assertFalse(regex.matches("FK/2026/0007"), "inny prefiks to inna seria")
        assertFalse(regex.matches("FV/2025/0007"), "inny rok to inna seria")
        assertFalse(regex.matches("FV/2026/0007-D2"), "numer po deduplikacji nie należy do serii")
        assertFalse(regex.matches("2026/FV/0007"))
    }

    /**
     * Hibernate parsuje `:nazwa` jako parametr nazwany, więc postgresowe `wartość::typ`
     * zostaje po cichu popsute i wywala się dopiero w bazie — patrz
     * IncomeDocumentsRepositorySqlTest.
     */
    @Test
    fun `natywne zapytanie numeracji nie uzywa skladni rzutowania dwukropkiem`() {
        val sql = KsefRevenueInvoiceRepository::class.java
            .getMethod("findMaxNumberSequence", String::class.java, String::class.java)
            .getAnnotation(Query::class.java)
            .value

        assertFalse(sql.contains("::"), "Rzutowania muszą używać CAST(... AS ...)")
        assertTrue(sql.contains("seller_nip"), "Sekwencja musi być liczona w zakresie NIP-u sprzedawcy")
        assertFalse(sql.contains("studio_id"), "Zakresem numeracji nie jest studio — KSeF pilnuje NIP-u")
    }
}
