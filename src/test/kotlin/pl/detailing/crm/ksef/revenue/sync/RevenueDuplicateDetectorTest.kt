package pl.detailing.crm.ksef.revenue.sync

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.ksef.revenue.domain.DuplicateStatus
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import java.time.LocalDate
import java.util.UUID

class RevenueDuplicateDetectorTest {

    private val repository = mockk<KsefRevenueInvoiceRepository>()
    private val detector = RevenueDuplicateDetector(repository)
    private val studioId = UUID.randomUUID()

    private fun external(
        buyerNip: String? = "5213017228",
        gross: Long = 123_000,
        type: RevenueInvoiceType = RevenueInvoiceType.VAT
    ) = KsefRevenueInvoiceEntity(
        studioId = studioId,
        source = RevenueSource.EXTERNAL,
        ksefStatus = KsefRevenueStatus.ACCEPTED,
        invoiceNumber = "ZEW/1/2026",
        ksefNumber = "1234563218-20260810-EXT-01",
        invoiceType = type,
        issueDate = LocalDate.of(2026, 8, 10),
        buyerNip = buyerNip,
        totalGross = gross
    )

    private fun crmInvoice() = KsefRevenueInvoiceEntity(
        studioId = studioId,
        source = RevenueSource.CRM,
        ksefStatus = KsefRevenueStatus.ACCEPTED,
        invoiceNumber = "FV/2026/0001",
        ksefNumber = "1234563218-20260808-CRM-01",
        issueDate = LocalDate.of(2026, 8, 8),
        buyerNip = "5213017228",
        totalGross = 123_000
    )

    @Test
    fun `oznacza pare SUSPECTED gdy faktura CRM pasuje`() {
        val ext = external()
        val crm = crmInvoice()
        every {
            repository.findDuplicateCandidates(studioId, "5213017228", 123_000, any(), any())
        } returns listOf(crm)
        every { repository.save(any()) } answers { firstArg() }

        assertTrue(detector.checkExternalInvoice(ext))

        assertEquals(DuplicateStatus.SUSPECTED, ext.duplicateStatus)
        assertEquals(DuplicateStatus.SUSPECTED, crm.duplicateStatus)
        assertEquals(crm.id, ext.duplicateOfId)
        assertEquals(ext.id, crm.duplicateOfId)
        verify(exactly = 2) { repository.save(any()) }
    }

    @Test
    fun `brak dopasowania nie oznacza niczego`() {
        every { repository.findDuplicateCandidates(any(), any(), any(), any(), any()) } returns emptyList()
        val ext = external()
        assertFalse(detector.checkExternalInvoice(ext))
        assertEquals(DuplicateStatus.NONE, ext.duplicateStatus)
    }

    @Test
    fun `korekty i faktury B2C sa pomijane`() {
        assertFalse(detector.checkExternalInvoice(external(type = RevenueInvoiceType.KOR)))
        assertFalse(detector.checkExternalInvoice(external(buyerNip = null)))
        verify(exactly = 0) { repository.findDuplicateCandidates(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ta sama faktura (ten sam numer KSeF) nie paruje sie sama ze soba`() {
        val ext = external()
        val sameKsef = crmInvoice().also { it.ksefNumber = ext.ksefNumber }
        every {
            repository.findDuplicateCandidates(any(), any(), any(), any(), any())
        } returns listOf(sameKsef)

        assertFalse(detector.checkExternalInvoice(ext))
    }
}
