package pl.detailing.crm.communication

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscampaigns.sendername.SmsSenderNameResolver
import pl.detailing.crm.smscredits.SmsCreditService
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.util.UUID

/**
 * Zgoda marketingowa bramkuje kampanie i tylko kampanie.
 *
 * Regresja, którą te testy trzymają: wiadomość transakcyjna (gotowość do odbioru, przypomnienie,
 * karta wizyty) była odrzucana komunikatem „Brak zgody na komunikację SMS" u każdego studia,
 * które zdefiniowało zgodę marketingową, a klient jej nie podpisał — czyli klient przestawał
 * dostawać powiadomienia o własnej wizycie, bo nie zgodził się na reklamy.
 */
class OutboundCommunicationGatewayConsentTest {

    private val smsProvider: SmsProvider = mockk()
    private val emailProvider: EmailProvider = mockk()
    private val consentChecker: MarketingConsentChecker = mockk()
    private val smsCreditService: SmsCreditService = mockk(relaxed = true)
    private val senderNameResolver: SmsSenderNameResolver = mockk()
    private val capabilityService: CapabilityService = mockk()
    private val businessEventPublisher: BusinessEventPublisher = mockk(relaxed = true)
    private val redirectService: CommunicationRedirectService = mockk { every { activeFor(any()) } returns null }

    private val gateway = OutboundCommunicationGateway(
        smsProvider, emailProvider, consentChecker, smsCreditService,
        senderNameResolver, capabilityService, SimpleMeterRegistry(), businessEventPublisher, redirectService
    )

    private val customerId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()

    private fun allowModuleAndCredits() {
        every { capabilityService.hasCapability(any(), any()) } returns true
        every { smsCreditService.tryDeductCredit(any()) } returns true
        every { senderNameResolver.resolve(any<UUID>()) } returns "STUDIO"
    }

    @Test
    fun `transactional sms goes out without asking about marketing consent`() {
        allowModuleAndCredits()
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("ext-1")

        val result = gateway.sendSms(customerId, studioId, "+48500100200", "Auto gotowe do odbioru")

        assertTrue(result.success)
        verify(exactly = 1) { smsProvider.send(any(), any(), any()) }
        // Sedno sprawy: o zgodę nikt nie pyta, więc jej brak nie ma jak zablokować wysyłki.
        verify(exactly = 0) { consentChecker.canSend(any(), any(), any(), any()) }
    }

    @Test
    fun `campaign sms is blocked when the customer has not signed the consent`() {
        allowModuleAndCredits()
        every { consentChecker.canSend(customerId, studioId, any(), any()) } returns false

        val result = gateway.sendSms(
            customerId, studioId, "+48500100200", "Promocja -20%",
            category = OutboundMessageCategory.CAMPAIGN
        )

        assertFalse(result.success)
        assertEquals("Brak zgody na komunikację SMS", result.errorMessage)
        verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
    }

    @Test
    fun `campaign sms goes out once the consent is signed`() {
        allowModuleAndCredits()
        every { consentChecker.canSend(customerId, studioId, any(), any()) } returns true
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("ext-2")

        val result = gateway.sendSms(
            customerId, studioId, "+48500100200", "Promocja -20%",
            category = OutboundMessageCategory.CAMPAIGN
        )

        assertTrue(result.success)
        verify(exactly = 1) { consentChecker.canSend(any(), any(), any(), any()) }
    }

    @Test
    fun `transactional email is not marketing either`() {
        every { capabilityService.hasCapability(any(), any()) } returns true
        every { emailProvider.send(any(), any(), any(), any()) } returns EmailDeliveryResult.success("msg-1")

        val result = gateway.sendEmail(customerId, studioId, "klient@example.com", "Karta Wizyty", "Treść")

        assertTrue(result.success)
        verify(exactly = 0) { consentChecker.canSend(any(), any(), any(), any()) }
    }
}
