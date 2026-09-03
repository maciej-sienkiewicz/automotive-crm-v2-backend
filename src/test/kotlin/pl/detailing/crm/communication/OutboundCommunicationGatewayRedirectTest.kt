package pl.detailing.crm.communication

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.redirect.ActiveRedirect
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.communication.whitelist.RecipientWhitelist
import pl.detailing.crm.communication.whitelist.RecipientWhitelistProperties
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
 * „Przekieruj każdą wiadomość mailową i SMS na moje dane" — the swap happens in the gateway,
 * at the last step before the provider, and only for the studio that switched it on.
 */
class OutboundCommunicationGatewayRedirectTest {

    private val smsProvider: SmsProvider = mockk()
    private val emailProvider: EmailProvider = mockk()
    private val redirectService: CommunicationRedirectService = mockk()
    private val capabilityService: CapabilityService = mockk { every { hasCapability(any(), any()) } returns true }
    private val smsCreditService: SmsCreditService = mockk(relaxed = true) { every { tryDeductCredit(any()) } returns true }
    private val senderNameResolver: SmsSenderNameResolver = mockk { every { resolve(any<UUID>()) } returns null }

    private val gateway = OutboundCommunicationGateway(
        smsProvider, emailProvider, mockk<MarketingConsentChecker>(), smsCreditService,
        senderNameResolver, capabilityService, SimpleMeterRegistry(), mockk<BusinessEventPublisher>(relaxed = true),
        redirectService, RecipientWhitelist(RecipientWhitelistProperties(enabled = false))
    )

    private val studioId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    @Test
    fun `sms goes to the studio phone with the customer stamped on it when redirect is on`() {
        every { redirectService.activeFor(studioId) } returns ActiveRedirect("+48500100200", "owner@studio.pl")
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("x")

        val result = gateway.sendSms(customerId, studioId, "+48600700800", "Auto gotowe do odbioru")

        assertTrue(result.success)
        verify(exactly = 1) { smsProvider.send("+48500100200", "[TEST → +48600700800] Auto gotowe do odbioru", null) }
        verify(exactly = 0) { smsProvider.send("+48600700800", any(), any()) }
    }

    @Test
    fun `email goes to the studio inbox with the stamp on the subject and the body untouched`() {
        every { redirectService.activeFor(studioId) } returns ActiveRedirect("+48500100200", "owner@studio.pl")
        every { emailProvider.send(any(), any(), any(), any()) } returns EmailDeliveryResult.success("m")

        gateway.sendEmail(customerId, studioId, "jan@klient.pl", "Twoja wizyta", "Dzień dobry Jan,")

        verify(exactly = 1) {
            emailProvider.send("owner@studio.pl", "[TEST → jan@klient.pl] Twoja wizyta", "Dzień dobry Jan,", emptyList())
        }
    }

    @Test
    fun `transactional email without a customer is redirected the same way`() {
        every { redirectService.activeFor(studioId) } returns ActiveRedirect("+48500100200", "owner@studio.pl")
        every { emailProvider.send(any(), any(), any(), any()) } returns EmailDeliveryResult.success("m")

        gateway.sendTransactionalEmail(studioId, "ksiegowosc@flota.pl", "Zestawienie", "W załączeniu")

        verify(exactly = 1) { emailProvider.send("owner@studio.pl", "[TEST → ksiegowosc@flota.pl] Zestawienie", "W załączeniu", emptyList()) }
    }

    @Test
    fun `with the redirect off the customer gets the message exactly as written`() {
        every { redirectService.activeFor(studioId) } returns null
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("x")
        every { emailProvider.send(any(), any(), any(), any()) } returns EmailDeliveryResult.success("m")

        gateway.sendTransactionalSms(studioId, "+48600700800", "Auto gotowe")
        gateway.sendEmail(customerId, studioId, "jan@klient.pl", "Twoja wizyta", "Treść")

        verify(exactly = 1) { smsProvider.send("+48600700800", "Auto gotowe", null) }
        verify(exactly = 1) { emailProvider.send("jan@klient.pl", "Twoja wizyta", "Treść", emptyList()) }
    }

    @Test
    fun `redirect is looked up per studio, so another studio is not affected`() {
        val otherStudio = UUID.randomUUID()
        every { redirectService.activeFor(studioId) } returns ActiveRedirect("+48500100200", "owner@studio.pl")
        every { redirectService.activeFor(otherStudio) } returns null
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("x")

        gateway.sendTransactionalSms(otherStudio, "+48600700800", "Auto gotowe")

        verify(exactly = 1) { smsProvider.send("+48600700800", "Auto gotowe", null) }
    }
}
