package pl.detailing.crm.visitcard

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import java.time.Instant

/**
 * The card is one link for reservation and visit. `blockReason` is what keeps the
 * `/card-link/send` endpoints from delivering it twice unless the employee says "again".
 */
class VisitCardSendStatusServiceTest {

    private val service = VisitCardSendStatusService(mockk<CommunicationLogJpaRepository>())
    private val sentAt = Instant.parse("2026-09-03T08:15:00Z") // 10:15 in Warsaw (CEST)

    @Test
    fun `never sent - nothing blocks, on any channel`() {
        val none = VisitCardSendStatus(null, null)
        listOf(null, VisitCardDeliveryChannel.SMS, VisitCardDeliveryChannel.EMAIL, VisitCardDeliveryChannel.BOTH).forEach {
            assertNull(service.blockReason(none, it, resend = false), "$it")
        }
    }

    @Test
    fun `sent by sms - a second sms is blocked with the moment in Warsaw time`() {
        val reason = service.blockReason(VisitCardSendStatus(null, sentAt), VisitCardDeliveryChannel.SMS, resend = false)
        assertNotNull(reason)
        assertTrue(reason!!.contains("03.09.2026 10:15"), reason)
        assertTrue(reason.contains("Potwierdź ponowną wysyłkę"), reason)
    }

    @Test
    fun `sent by sms - an e-mail is a different channel, not a duplicate`() {
        assertNull(service.blockReason(VisitCardSendStatus(null, sentAt), VisitCardDeliveryChannel.EMAIL, resend = false))
    }

    @Test
    fun `sent by e-mail - an sms is allowed, a second e-mail is not`() {
        val status = VisitCardSendStatus(sentAt, null)
        assertNull(service.blockReason(status, VisitCardDeliveryChannel.SMS, resend = false))
        assertNotNull(service.blockReason(status, VisitCardDeliveryChannel.EMAIL, resend = false))
    }

    @Test
    fun `studio default channel (no override) is blocked once the card went out on any channel`() {
        assertNotNull(service.blockReason(VisitCardSendStatus(null, sentAt), null, resend = false))
        assertNotNull(service.blockReason(VisitCardSendStatus(sentAt, null), null, resend = false))
        assertNotNull(service.blockReason(VisitCardSendStatus(sentAt, sentAt), VisitCardDeliveryChannel.BOTH, resend = false))
    }

    @Test
    fun `an explicit resend is never blocked`() {
        val status = VisitCardSendStatus(sentAt, sentAt)
        listOf(null, VisitCardDeliveryChannel.SMS, VisitCardDeliveryChannel.EMAIL, VisitCardDeliveryChannel.BOTH).forEach {
            assertNull(service.blockReason(status, it, resend = true), "$it")
        }
    }

    @Test
    fun `NONE channel never blocks because nothing would be sent anyway`() {
        assertEquals(null, service.blockReason(VisitCardSendStatus(sentAt, sentAt), VisitCardDeliveryChannel.NONE, resend = false))
    }
}
