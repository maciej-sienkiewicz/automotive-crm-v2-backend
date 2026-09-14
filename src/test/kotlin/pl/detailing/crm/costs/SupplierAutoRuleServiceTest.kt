package pl.detailing.crm.costs

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import java.util.UUID

/**
 * Reguła auto-przypisania kosztów. Testy pinują to, na czym oparł się zgłoszony błąd
 * („dodałam regułę wczoraj, dziś faktura się nie przypisała"): reguła stosuje się do
 * NOWO pobranych faktur, dopasowanie idzie po samych cyfrach NIP, a już przypisane
 * pozycje są pomijane.
 */
class SupplierAutoRuleServiceTest {

    private val autoRuleRepository = mockk<SupplierAutoRuleRepository>()
    private val invoiceRepository = mockk<KsefInvoiceRepository>()
    private val invoiceItemRepository = mockk<KsefInvoiceItemRepository>()
    private val assignmentRepository = mockk<CostItemAssignmentRepository>()
    private val service = SupplierAutoRuleService(
        autoRuleRepository, invoiceRepository, invoiceItemRepository, assignmentRepository
    )

    private val studioId = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()

    private fun rule(nip: String) = SupplierAutoRuleEntity(
        studioId = studioId, sellerNip = nip, sellerName = "Brawix", categoryId = categoryId
    )

    private fun invoice(id: UUID, nip: String?) = mockk<KsefInvoiceEntity> {
        every { this@mockk.id } returns id
        every { sellerNip } returns nip
    }

    private fun item(id: UUID, invoiceId: UUID) = mockk<KsefInvoiceItemEntity> {
        every { this@mockk.id } returns id
        every { this@mockk.invoiceId } returns invoiceId
    }

    @Test
    fun `applyRule przypisuje nieprzypisane pozycje pasujacych faktur do kategorii`() {
        val invId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        every { invoiceRepository.findBySellerNipDigits(studioId, "5223003098") } returns listOf(invoice(invId, "5223003098"))
        every { invoiceItemRepository.findByInvoiceIdIn(listOf(invId)) } returns listOf(item(itemId, invId))
        every { assignmentRepository.findByStudioId(studioId) } returns emptyList()
        val saved = slot<CostItemAssignmentEntity>()
        every { assignmentRepository.save(capture(saved)) } answers { firstArg() }

        val count = service.applyRule(rule("5223003098"), studioId)

        assertEquals(1, count)
        assertEquals(categoryId, saved.captured.categoryId)
        assertEquals(itemId, saved.captured.ksefItemId)
        assertEquals(invId, saved.captured.invoiceId)
        assertEquals(studioId, saved.captured.studioId)
    }

    @Test
    fun `applyRule pomija pozycje juz przypisane`() {
        val invId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        every { invoiceRepository.findBySellerNipDigits(studioId, "5223003098") } returns listOf(invoice(invId, "5223003098"))
        every { invoiceItemRepository.findByInvoiceIdIn(listOf(invId)) } returns listOf(item(itemId, invId))
        every { assignmentRepository.findByStudioId(studioId) } returns listOf(
            CostItemAssignmentEntity(categoryId = UUID.randomUUID(), ksefItemId = itemId, invoiceId = invId, studioId = studioId)
        )

        val count = service.applyRule(rule("5223003098"), studioId)

        assertEquals(0, count)
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    @Test
    fun `applyRulesForInvoices dopasowuje regule po samych cyfrach NIP mimo prefiksu PL`() {
        val invId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        // Faktura z KSeF trzyma NIP z prefiksem, reguła — same cyfry.
        val fresh = invoice(invId, "PL5223003098")
        every { autoRuleRepository.findByStudioId(studioId) } returns listOf(rule("5223003098"))
        every { invoiceRepository.findBySellerNipDigits(studioId, "5223003098") } returns listOf(fresh)
        every { invoiceItemRepository.findByInvoiceIdIn(listOf(invId)) } returns listOf(item(itemId, invId))
        every { assignmentRepository.findByStudioId(studioId) } returns emptyList()
        every { assignmentRepository.save(any()) } answers { firstArg() }

        val count = service.applyRulesForInvoices(studioId, listOf(fresh))

        assertEquals(1, count)
        verify(exactly = 1) { assignmentRepository.save(any()) }
    }

    @Test
    fun `applyRulesForInvoices nic nie robi gdy zaden NIP nie ma reguly`() {
        val fresh = invoice(UUID.randomUUID(), "9999999999")
        every { autoRuleRepository.findByStudioId(studioId) } returns listOf(rule("5223003098"))

        val count = service.applyRulesForInvoices(studioId, listOf(fresh))

        assertEquals(0, count)
        verify(exactly = 0) { invoiceRepository.findBySellerNipDigits(any(), any()) }
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    @Test
    fun `normalizeNip zostawia same cyfry`() {
        assertEquals("5223003098", SupplierAutoRuleService.normalizeNip("PL 522-300-30-98"))
        assertEquals("5223003098", SupplierAutoRuleService.normalizeNip("5223003098"))
        assertEquals("", SupplierAutoRuleService.normalizeNip(null))
        assertEquals("", SupplierAutoRuleService.normalizeNip("brak"))
    }
}
