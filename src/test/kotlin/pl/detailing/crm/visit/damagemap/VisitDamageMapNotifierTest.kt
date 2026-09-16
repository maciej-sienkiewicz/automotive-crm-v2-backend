package pl.detailing.crm.visit.damagemap

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import java.util.UUID

/**
 * Powiadomienie o zmianie mapy uszkodzeń nie może obiecywać rzeczy, których nie
 * dostarcza: SMS nie ma załącznika, a e-mail go nie ma, gdy generowanie PDF-a padło.
 * Klient czyta to jako informację o stanie swojego samochodu.
 */
class VisitDamageMapNotifierTest {

    private val gateway: OutboundCommunicationGateway = mockk()
    private val log: CommunicationLogService = mockk(relaxed = true)
    private val notifier = VisitDamageMapNotifier(gateway, log)

    private val pdf = byteArrayOf(1, 2, 3)

    private fun request(
        email: String? = "jan@example.com",
        phone: String? = "534920205",
        pdfBytes: ByteArray? = pdf,
        messageBody: String? = null,
        pointsBefore: Int = 2,
        pointsAfter: Int = 4
    ) = DamageMapNotificationRequest(
        studioId = StudioId(UUID.randomUUID()),
        visitId = VisitId(UUID.randomUUID()),
        visitNumber = "WIZ/2026/09/001",
        customerId = CustomerId(UUID.randomUUID()),
        customerFirstName = "Jan",
        recipientEmail = email,
        recipientPhone = phone,
        vehicleLabel = "Porsche 911 (WA12345)",
        pointsBefore = pointsBefore,
        pointsAfter = pointsAfter,
        pdfBytes = pdfBytes,
        messageBody = messageBody
    )

    private fun stubEmail(success: Boolean = true) {
        every { gateway.sendEmail(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            if (success) EmailDeliveryResult.success("msg-1") else EmailDeliveryResult.failure("SMTP down")
    }

    private fun stubSms(success: Boolean = true) {
        every { gateway.sendTransactionalSms(any(), any(), any(), any(), any(), any()) } returns
            if (success) SmsDeliveryResult.success("sms-1") else SmsDeliveryResult.failure("Brak kredytów SMS")
    }

    @Test
    fun `mail z zalacznikiem wystarcza - SMS nie idzie drugi raz o tej samej rysie`() {
        stubEmail()

        val result = notifier.notifyCustomer(request())

        assertTrue(result.emailSent)
        assertFalse(result.smsSent)
        verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any(), any(), any()) }

        val attachments = slot<List<EmailAttachment>>()
        verify {
            gateway.sendEmail(
                any(), any(), "jan@example.com", any(), any(),
                capture(attachments), any(), any(), any(), any()
            )
        }
        assertEquals(1, attachments.captured.size)
        assertEquals("application/pdf", attachments.captured.single().contentType)
    }

    @Test
    fun `brak adresu e-mail spada na SMS`() {
        stubSms()

        val result = notifier.notifyCustomer(request(email = null))

        assertFalse(result.emailSent)
        assertTrue(result.smsSent)
        val body = slot<String>()
        verify { gateway.sendTransactionalSms(any(), "+48534920205", capture(body), any(), any(), any()) }
        // SMS-em załącznika nie wyślemy, więc treść nie może go obiecywać.
        assertFalse(body.captured.contains("zalacznik", ignoreCase = true))
        assertTrue(body.captured.contains("Nowych oznaczen: 2"))
    }

    @Test
    fun `nieudany mail probuje jeszcze SMS-em`() {
        stubEmail(success = false)
        stubSms()

        val result = notifier.notifyCustomer(request())

        assertFalse(result.emailSent)
        assertTrue(result.smsSent)
        assertTrue(result.message.contains("SMS o aktualizacji mapy uszkodzeń wysłany"))
    }

    @Test
    fun `mail bez wygenerowanego PDF-a nie obiecuje zalacznika`() {
        // Punkty zapisują się nawet wtedy, gdy generowanie pliku padnie — mail o tym
        // wychodzi, ale nie może kłamać, że dokument jest w środku.
        stubEmail()

        notifier.notifyCustomer(request(pdfBytes = null))

        val body = slot<String>()
        val attachments = slot<List<EmailAttachment>>()
        verify {
            gateway.sendEmail(
                any(), any(), any(), any(), capture(body),
                capture(attachments), any(), any(), any(), any()
            )
        }
        assertTrue(attachments.captured.isEmpty())
        assertFalse(body.captured.contains("w załączniku"))
        assertTrue(body.captured.contains("przy odbiorze pojazdu"))
    }

    @Test
    fun `tresc od operatora idzie doslownie, a zdanie o zalaczniku doklejamy sami`() {
        stubEmail()

        notifier.notifyCustomer(request(messageBody = "Doszla rysa na drzwiach kierowcy"))

        val body = slot<String>()
        verify {
            gateway.sendEmail(any(), any(), any(), any(), capture(body), any(), any(), any(), any(), any())
        }
        assertTrue(body.captured.startsWith("Doszla rysa na drzwiach kierowcy"))
        assertTrue(body.captured.contains("w załączniku"))
    }

    @Test
    fun `brak adresu i numeru konczy sie jasnym komunikatem, nie cisza`() {
        val result = notifier.notifyCustomer(request(email = null, phone = null))

        assertFalse(result.emailSent)
        assertFalse(result.smsSent)
        assertTrue(result.message.contains("ani adresu e-mail, ani numeru telefonu"))
        verify(exactly = 0) { log.record(any()) }
    }

    @Test
    fun `kazda proba wysylki ladnie w historii komunikacji, takze nieudana`() {
        // Bez wpisu „nie doszło" studio nie ma skąd wiedzieć, że klient nie wie.
        stubEmail(success = false)
        stubSms(success = false)

        notifier.notifyCustomer(request())

        val entries = mutableListOf<RecordCommunicationCommand>()
        verify(exactly = 2) { log.record(capture(entries)) }
        assertEquals(
            listOf(CommunicationChannel.EMAIL, CommunicationChannel.SMS),
            entries.map { it.channel }
        )
        assertTrue(entries.all { !it.success })
    }
}
