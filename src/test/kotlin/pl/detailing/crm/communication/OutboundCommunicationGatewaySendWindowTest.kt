package pl.detailing.crm.communication

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.queue.OutboundMessageDraft
import pl.detailing.crm.communication.queue.OutboundMessageEntity
import pl.detailing.crm.communication.queue.OutboundMessageQueue
import pl.detailing.crm.communication.queue.OutboundMessageStatus
import pl.detailing.crm.communication.queue.QueuedOutboundMessage
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.communication.whitelist.RecipientWhitelist
import pl.detailing.crm.communication.whitelist.RecipientWhitelistProperties
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscampaigns.sendername.SmsSenderNameResolver
import pl.detailing.crm.smscredits.SmsCreditService
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Okno wysyłki w bramce: poza 12:00–18:00 wiadomość do klienta nie idzie do dostawcy,
 * tylko do kolejki — i wraca na najbliższe otwarcie. Wyjątek jest jawny (IMMEDIATE),
 * a zablokowana wiadomość (brak modułu, brak zgody) nie trafia do kolejki wcale.
 */
class OutboundCommunicationGatewaySendWindowTest {

    private val smsProvider: SmsProvider = mockk()
    private val emailProvider: EmailProvider = mockk()
    private val consentChecker: MarketingConsentChecker = mockk()
    private val capabilityService: CapabilityService = mockk { every { hasCapability(any(), any()) } returns true }
    private val smsCreditService: SmsCreditService = mockk(relaxed = true) { every { tryDeductCredit(any()) } returns true }
    private val senderNameResolver: SmsSenderNameResolver = mockk { every { resolve(any<UUID>()) } returns null }
    private val redirectService: CommunicationRedirectService = mockk { every { activeFor(any()) } returns null }
    private val queue: OutboundMessageQueue = mockk()

    private val studioId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private fun warsaw(hour: Int, minute: Int, day: Int = 15): Instant =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(SendWindow.DEFAULT.zone).toInstant()

    private fun gatewayAt(now: Instant) = OutboundCommunicationGateway(
        smsProvider, emailProvider, consentChecker, smsCreditService, senderNameResolver, capabilityService,
        SimpleMeterRegistry(), mockk<BusinessEventPublisher>(relaxed = true), redirectService,
        RecipientWhitelist(RecipientWhitelistProperties(enabled = false)),
        SendWindow.DEFAULT, queue, Clock.fixed(now, SendWindow.DEFAULT.zone)
    )

    private fun stubEnqueue(): Pair<UUID, io.mockk.CapturingSlot<OutboundMessageDraft>> {
        val id = UUID.randomUUID()
        val draft = slot<OutboundMessageDraft>()
        every { queue.enqueue(capture(draft), any(), any()) } answers {
            val d = firstArg<OutboundMessageDraft>()
            OutboundMessageEntity(
                id = id, studioId = d.studioId, customerId = d.customerId, channel = d.channel, category = d.category,
                recipient = d.recipient, subject = d.subject, body = d.body, context = d.context,
                status = OutboundMessageStatus.QUEUED, scheduledFor = secondArg()
            )
        }
        return id to draft
    }

    // ── Poza oknem: kolejka ──────────────────────────────────────────────────

    @Test
    fun `sms wieczorem laduje w kolejce na jutrzejsze poludnie i nie idzie do dostawcy`() {
        val (queuedId, draft) = stubEnqueue()

        val result = gatewayAt(warsaw(20, 50)).sendSms(customerId, studioId, "+48600700800", "Dziękujemy za wizytę")

        assertTrue(result.success)
        assertTrue(result.queued)
        assertEquals(queuedId, result.queuedMessageId)
        assertEquals(warsaw(12, 0, day = 16), result.scheduledFor)
        assertNull(result.externalMessageId)
        assertEquals("+48600700800", draft.captured.recipient)
        assertEquals("Dziękujemy za wizytę", draft.captured.body)
        assertEquals(customerId, draft.captured.customerId)
        assertEquals(CommunicationChannel.SMS, draft.captured.channel)
        verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
        verify(exactly = 0) { smsCreditService.tryDeductCredit(any()) }
    }

    @Test
    fun `mail rano laduje w kolejce na dzisiejsze poludnie razem z zalacznikiem`() {
        val (_, draft) = stubEnqueue()
        val pdf = EmailAttachment("zestawienie.pdf", byteArrayOf(1, 2, 3), "application/pdf")

        val result = gatewayAt(warsaw(8, 15)).sendTransactionalEmail(
            studioId, "ksiegowosc@flota.pl", "Zestawienie", "W załączeniu", listOf(pdf)
        )

        assertTrue(result.success)
        assertTrue(result.queued)
        assertEquals(warsaw(12, 0), result.scheduledFor)
        assertEquals(listOf(pdf), draft.captured.attachments)
        assertNull(draft.captured.customerId)
        verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
    }

    // ── W oknie: jak dotąd ───────────────────────────────────────────────────

    @Test
    fun `w oknie wiadomosc idzie od razu do dostawcy`() {
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("ext-1")

        val result = gatewayAt(warsaw(14, 0)).sendSms(customerId, studioId, "+48600700800", "Auto gotowe")

        assertTrue(result.success)
        assertFalse(result.queued)
        assertEquals("ext-1", result.externalMessageId)
        verify(exactly = 0) { queue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `sekundy po osiemnastej to jeszcze okno`() {
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("ext-1")

        val result = gatewayAt(warsaw(18, 0).plusSeconds(40)).sendSms(customerId, studioId, "+48600700800", "Auto gotowe")

        assertFalse(result.queued)
        verify(exactly = 0) { queue.enqueue(any(), any(), any()) }
    }

    // ── IMMEDIATE ────────────────────────────────────────────────────────────

    @Test
    fun `immediate omija kolejke takze poza oknem`() {
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("ext-2")

        val result = gatewayAt(warsaw(21, 0)).sendTransactionalSms(
            studioId, "+48600700800", "Link do podpisu", delivery = DeliveryPolicy.IMMEDIATE
        )

        assertTrue(result.success)
        assertFalse(result.queued)
        verify(exactly = 0) { queue.enqueue(any(), any(), any()) }
        verify(exactly = 1) { smsProvider.send(any(), any(), any()) }
    }

    // ── Blokady przed kolejką ────────────────────────────────────────────────

    @Test
    fun `wiadomosc bez modulu jest odrzucona od razu, nie odkladana na jutro`() {
        every { capabilityService.hasCapability(any(), any()) } returns false

        val result = gatewayAt(warsaw(21, 0)).sendSms(customerId, studioId, "+48600700800", "Auto gotowe")

        assertFalse(result.success)
        assertFalse(result.queued)
        verify(exactly = 0) { queue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `kampania bez zgody marketingowej nie trafia do kolejki`() {
        every { consentChecker.canSend(any(), any(), any(), any()) } returns false

        val result = gatewayAt(warsaw(21, 0)).sendEmail(
            customerId, studioId, "jan@klient.pl", "Promocja", "Treść", category = OutboundMessageCategory.CAMPAIGN
        )

        assertFalse(result.success)
        verify(exactly = 0) { queue.enqueue(any(), any(), any()) }
    }

    // ── Powrót z kolejki ─────────────────────────────────────────────────────

    @Test
    fun `wiadomosc z kolejki przechodzi pelna bramke i idzie do dostawcy`() {
        every { emailProvider.send(any(), any(), any(), any()) } returns EmailDeliveryResult.success("m-1")
        val entity = OutboundMessageEntity(
            id = UUID.randomUUID(), studioId = studioId, customerId = customerId, channel = CommunicationChannel.EMAIL,
            category = OutboundMessageCategory.TRANSACTIONAL, recipient = "jan@klient.pl", subject = "Twoja wizyta",
            body = "Dzień dobry", context = "SendVisitWelcomeEmail", status = OutboundMessageStatus.SENDING,
            scheduledFor = warsaw(12, 0)
        )

        val outcome = gatewayAt(warsaw(12, 0)).deliverQueued(QueuedOutboundMessage(entity, emptyList()))

        assertTrue(outcome.success)
        assertEquals("m-1", outcome.externalMessageId)
        verify { emailProvider.send("jan@klient.pl", "Twoja wizyta", "Dzień dobry", emptyList()) }
    }

    @Test
    fun `wiadomosc z kolejki, ktorej studio juz nie moze wyslac, konczy sie bledem a nie ponownym odlozeniem`() {
        every { capabilityService.hasCapability(any(), any()) } returns false
        val entity = OutboundMessageEntity(
            id = UUID.randomUUID(), studioId = studioId, customerId = null, channel = CommunicationChannel.SMS,
            category = OutboundMessageCategory.TRANSACTIONAL, recipient = "+48600700800", subject = null,
            body = "Auto gotowe", context = "TRANSACTIONAL", status = OutboundMessageStatus.SENDING,
            scheduledFor = warsaw(12, 0)
        )

        val outcome = gatewayAt(warsaw(12, 0)).deliverQueued(QueuedOutboundMessage(entity, emptyList()))

        assertFalse(outcome.success)
        assertTrue(outcome.retryable)
        verify(exactly = 0) { queue.enqueue(any(), any(), any()) }
        verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
    }

    @Test
    fun `brak kredytow przy wysylce z kolejki nie jest ponawiany`() {
        every { smsCreditService.tryDeductCredit(any()) } returns false
        val entity = OutboundMessageEntity(
            id = UUID.randomUUID(), studioId = studioId, customerId = customerId, channel = CommunicationChannel.SMS,
            category = OutboundMessageCategory.TRANSACTIONAL, recipient = "+48600700800", subject = null,
            body = "Auto gotowe", context = "ctx", status = OutboundMessageStatus.SENDING, scheduledFor = warsaw(12, 0)
        )

        val outcome = gatewayAt(warsaw(12, 0)).deliverQueued(QueuedOutboundMessage(entity, emptyList()))

        assertFalse(outcome.success)
        assertFalse(outcome.retryable)
    }
}
