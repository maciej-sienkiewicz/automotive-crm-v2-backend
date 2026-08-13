package pl.detailing.crm.ksef.revenue.send

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import java.time.LocalDate
import java.util.UUID

class KsefRevenueDispatchServiceTest {

    private val sender = mockk<KsefInvoiceSender>()
    private val repository = mockk<KsefRevenueInvoiceRepository>()
    private val service = KsefRevenueDispatchService(sender, repository)

    private fun invoice(status: KsefRevenueStatus = KsefRevenueStatus.PENDING) = KsefRevenueInvoiceEntity(
        studioId = UUID.randomUUID(),
        source = RevenueSource.CRM,
        ksefStatus = status,
        invoiceNumber = "FV/2026/0001",
        issueDate = LocalDate.of(2026, 8, 13),
        invoiceXml = "<Faktura/>"
    )

    init {
        every { repository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `przyjecie faktury zapisuje numer KSeF i UPO`() {
        every { sender.send(any(), any()) } returns
            KsefSendOutcome.Accepted("1234563218-20260813-XYZ-01", "<Upo/>", "hash==")

        val result = service.dispatch(invoice())

        assertEquals(KsefRevenueStatus.ACCEPTED, result.ksefStatus)
        assertEquals("1234563218-20260813-XYZ-01", result.ksefNumber)
        assertEquals("<Upo/>", result.upoXml)
        assertEquals("hash==", result.invoiceHash)
        assertNotNull(result.acceptedAt)
        assertEquals(1, result.sendAttempts)
    }

    @Test
    fun `odrzucenie przez walidacje konczy w REJECTED bez retry`() {
        every { sender.send(any(), any()) } returns KsefSendOutcome.Rejected("KSeF HTTP 400: zly XML")

        val result = service.dispatch(invoice())

        assertEquals(KsefRevenueStatus.REJECTED, result.ksefStatus)
        assertEquals("KSeF HTTP 400: zly XML", result.lastSendError)
    }

    @Test
    fun `blad lacznosci kieruje do kolejki retry z oknem offline24`() {
        every { sender.send(any(), any()) } returns KsefSendOutcome.TransientFailure("timeout")

        val result = service.dispatch(invoice())

        assertEquals(KsefRevenueStatus.QUEUED_RETRY, result.ksefStatus)
        assertNotNull(result.firstQueuedAt)

        // Kolejna nieudana próba nie przesuwa początku okna offline24
        val firstQueuedAt = result.firstQueuedAt
        service.dispatch(result)
        assertEquals(firstQueuedAt, result.firstQueuedAt)
        assertEquals(2, result.sendAttempts)
    }

    @Test
    fun `faktura ACCEPTED nigdy nie jest wysylana ponownie (idempotencja)`() {
        val accepted = invoice(KsefRevenueStatus.ACCEPTED)
        val result = service.dispatch(accepted)

        assertEquals(KsefRevenueStatus.ACCEPTED, result.ksefStatus)
        verify(exactly = 0) { sender.send(any(), any()) }
    }

    @Test
    fun `faktura SUBMITTED nie jest wysylana ponownie, tylko dokanczana`() {
        val submitted = invoice(KsefRevenueStatus.SUBMITTED).apply {
            sessionReference = "SES-1"
            invoiceReference = "INV-1"
        }
        every { sender.checkStatus(any(), "SES-1", "INV-1") } returns
            KsefStatusOutcome.Accepted("1234563218-20260813-FIN-01", "<Upo/>", null)

        val dispatched = service.dispatch(submitted)
        verify(exactly = 0) { sender.send(any(), any()) }
        assertEquals(KsefRevenueStatus.SUBMITTED, dispatched.ksefStatus)

        val completed = service.complete(submitted)
        assertEquals(KsefRevenueStatus.ACCEPTED, completed.ksefStatus)
        assertEquals("1234563218-20260813-FIN-01", completed.ksefNumber)
    }

    @Test
    fun `odrzucenie w trakcie przetwarzania sesji konczy w REJECTED`() {
        val submitted = invoice(KsefRevenueStatus.SUBMITTED).apply {
            sessionReference = "SES-1"
            invoiceReference = "INV-1"
        }
        every { sender.checkStatus(any(), any(), any()) } returns
            KsefStatusOutcome.Rejected("KSeF odrzucil fakture (kod 445)")

        val completed = service.complete(submitted)
        assertEquals(KsefRevenueStatus.REJECTED, completed.ksefStatus)
    }
}
