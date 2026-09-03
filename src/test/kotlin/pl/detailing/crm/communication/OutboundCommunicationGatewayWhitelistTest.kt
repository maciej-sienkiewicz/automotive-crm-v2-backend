package pl.detailing.crm.communication

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
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
 * Whitelist × redirect, every combination the founders asked about:
 *  - whitelist in force, redirect off  → only listed recipients, nothing else, no credit spent;
 *  - whitelist in force, redirect on   → the whitelist does not apply, the studio gets everything;
 *  - whitelist removed (disabled), redirect on → still redirected;
 *  - whitelist removed, redirect off   → customers get their messages.
 */
class OutboundCommunicationGatewayWhitelistTest {

    private val smsProvider: SmsProvider = mockk()
    private val emailProvider: EmailProvider = mockk()
    private val redirectService: CommunicationRedirectService = mockk()
    private val consentChecker: MarketingConsentChecker = mockk()
    private val capabilityService: CapabilityService = mockk { every { hasCapability(any(), any()) } returns true }
    private val smsCreditService: SmsCreditService = mockk(relaxed = true) { every { tryDeductCredit(any()) } returns true }
    private val senderNameResolver: SmsSenderNameResolver = mockk { every { resolve(any<UUID>()) } returns null }
    private val meterRegistry = SimpleMeterRegistry()

    private val studioId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private val listedPhone = "+48500100200"
    private val listedEmail = "owner@studio.pl"
    private val customerPhone = "+48600700800"
    private val customerEmail = "jan@klient.pl"

    private fun gateway(whitelist: RecipientWhitelist) = OutboundCommunicationGateway(
        smsProvider, emailProvider, consentChecker, smsCreditService, senderNameResolver,
        capabilityService, meterRegistry, mockk<BusinessEventPublisher>(relaxed = true), redirectService, whitelist
    )

    private fun inForce(phones: List<String> = listOf(listedPhone), emails: List<String> = listOf(listedEmail)) =
        RecipientWhitelist(RecipientWhitelistProperties(enabled = true, phones = phones, emails = emails))

    private fun removed() = RecipientWhitelist(RecipientWhitelistProperties(enabled = false))

    private fun providersSucceed() {
        every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.success("sms-1")
        every { emailProvider.send(any(), any(), any(), any()) } returns EmailDeliveryResult.success("mail-1")
    }

    private fun blockedCount(channel: String) =
        meterRegistry.find("communication.blocked.whitelist").tag("channel", channel).counter()?.count() ?: 0.0

    @Nested
    inner class WhitelistInForceRedirectOff {

        init { every { redirectService.activeFor(any()) } returns null }

        @Test
        fun `sms to an unlisted customer is blocked, the provider is not called and no credit is spent`() {
            providersSucceed()
            val result = gateway(inForce()).sendSms(customerId, studioId, customerPhone, "Auto gotowe")

            assertFalse(result.success)
            assertEquals(RecipientWhitelist.BLOCK_REASON_SMS, result.errorMessage)
            verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
            verify(exactly = 0) { smsCreditService.tryDeductCredit(any()) }
            verify(exactly = 0) { smsCreditService.refundCredit(any(), any()) }
            assertEquals(1.0, blockedCount("SMS"))
        }

        @Test
        fun `transactional sms to an unlisted customer is blocked the same way`() {
            val result = gateway(inForce()).sendTransactionalSms(studioId, customerPhone, "Przypomnienie")
            assertFalse(result.success)
            assertEquals(RecipientWhitelist.BLOCK_REASON_SMS, result.errorMessage)
            verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
        }

        @Test
        fun `sms to a listed number goes out unchanged and a credit is deducted`() {
            providersSucceed()
            val result = gateway(inForce()).sendSms(customerId, studioId, "+48 500 100 200", "Auto gotowe")

            assertTrue(result.success)
            verify(exactly = 1) { smsProvider.send("+48 500 100 200", "Auto gotowe", null) }
            verify(exactly = 1) { smsCreditService.tryDeductCredit(any()) }
            assertEquals(0.0, blockedCount("SMS"))
        }

        @Test
        fun `email to an unlisted customer is blocked and the provider is not called`() {
            providersSucceed()
            val result = gateway(inForce()).sendEmail(customerId, studioId, customerEmail, "Twoja wizyta", "Treść")

            assertFalse(result.success)
            assertEquals(RecipientWhitelist.BLOCK_REASON_EMAIL, result.errorMessage)
            verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
            assertEquals(1.0, blockedCount("EMAIL"))
        }

        @Test
        fun `transactional email to an unlisted contractor is blocked`() {
            val result = gateway(inForce()).sendTransactionalEmail(studioId, "ksiegowosc@flota.pl", "Zestawienie", "W załączeniu")
            assertFalse(result.success)
            verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
        }

        @Test
        fun `email to a listed address goes out unchanged, case insensitively`() {
            providersSucceed()
            val result = gateway(inForce()).sendEmail(customerId, studioId, "Owner@Studio.pl", "Twoja wizyta", "Treść")

            assertTrue(result.success)
            verify(exactly = 1) { emailProvider.send("Owner@Studio.pl", "Twoja wizyta", "Treść", emptyList()) }
        }

        @Test
        fun `a campaign to an unlisted customer is blocked even with marketing consent`() {
            every { consentChecker.canSend(any(), any(), any(), any()) } returns true
            val result = gateway(inForce()).sendSms(
                customerId, studioId, customerPhone, "Promocja", category = OutboundMessageCategory.CAMPAIGN
            )
            assertFalse(result.success)
            assertEquals(RecipientWhitelist.BLOCK_REASON_SMS, result.errorMessage)
        }

        @Test
        fun `an enabled but empty whitelist blocks every customer message`() {
            val g = gateway(inForce(phones = emptyList(), emails = emptyList()))
            assertFalse(g.sendTransactionalSms(studioId, listedPhone, "x").success)
            assertFalse(g.sendTransactionalEmail(studioId, listedEmail, "s", "b").success)
            verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
            verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class WhitelistInForceRedirectOn {

        private val redirectPhone = "+48777666555"     // deliberately NOT on the whitelist
        private val redirectEmail = "szef@mojestudio.pl" // deliberately NOT on the whitelist

        init { every { redirectService.activeFor(studioId) } returns ActiveRedirect(redirectPhone, redirectEmail) }

        @Test
        fun `sms for an unlisted customer reaches the studio phone even though that phone is not listed`() {
            providersSucceed()
            val result = gateway(inForce()).sendSms(customerId, studioId, customerPhone, "Auto gotowe")

            assertTrue(result.success)
            verify(exactly = 1) { smsProvider.send(redirectPhone, "[TEST → $customerPhone] Auto gotowe", null) }
            verify(exactly = 1) { smsCreditService.tryDeductCredit(any()) }
            assertEquals(0.0, blockedCount("SMS"))
        }

        @Test
        fun `email for an unlisted customer reaches the studio inbox even though that inbox is not listed`() {
            providersSucceed()
            val result = gateway(inForce()).sendEmail(customerId, studioId, customerEmail, "Twoja wizyta", "Treść")

            assertTrue(result.success)
            verify(exactly = 1) { emailProvider.send(redirectEmail, "[TEST → $customerEmail] Twoja wizyta", "Treść", emptyList()) }
            assertEquals(0.0, blockedCount("EMAIL"))
        }

        @Test
        fun `an enabled but empty whitelist does not stop a redirected message`() {
            providersSucceed()
            val g = gateway(inForce(phones = emptyList(), emails = emptyList()))
            assertTrue(g.sendTransactionalSms(studioId, customerPhone, "x").success)
            assertTrue(g.sendTransactionalEmail(studioId, customerEmail, "s", "b").success)
            verify(exactly = 1) { smsProvider.send(redirectPhone, any(), any()) }
            verify(exactly = 1) { emailProvider.send(redirectEmail, any(), any(), any()) }
        }

        @Test
        fun `the original customer address never reaches the provider`() {
            providersSucceed()
            gateway(inForce()).sendSms(customerId, studioId, listedPhone, "Auto gotowe")
            verify(exactly = 0) { smsProvider.send(listedPhone, any(), any()) }
            verify(exactly = 1) { smsProvider.send(redirectPhone, any(), any()) }
        }

        @Test
        fun `another studio without a redirect is still held to the whitelist`() {
            val other = UUID.randomUUID()
            every { redirectService.activeFor(other) } returns null
            val result = gateway(inForce()).sendTransactionalSms(other, customerPhone, "x")
            assertFalse(result.success)
            assertEquals(RecipientWhitelist.BLOCK_REASON_SMS, result.errorMessage)
        }
    }

    @Nested
    inner class WhitelistRemoved {

        @Test
        fun `redirect keeps working with the whitelist disabled`() {
            every { redirectService.activeFor(studioId) } returns ActiveRedirect("+48777666555", "szef@mojestudio.pl")
            providersSucceed()
            val g = gateway(removed())

            assertTrue(g.sendSms(customerId, studioId, customerPhone, "Auto gotowe").success)
            assertTrue(g.sendEmail(customerId, studioId, customerEmail, "Twoja wizyta", "Treść").success)
            verify(exactly = 1) { smsProvider.send("+48777666555", "[TEST → $customerPhone] Auto gotowe", null) }
            verify(exactly = 1) { emailProvider.send("szef@mojestudio.pl", "[TEST → $customerEmail] Twoja wizyta", "Treść", emptyList()) }
        }

        @Test
        fun `with the whitelist disabled and no redirect the customer gets the message as written`() {
            every { redirectService.activeFor(studioId) } returns null
            providersSucceed()
            val g = gateway(removed())

            assertTrue(g.sendSms(customerId, studioId, customerPhone, "Auto gotowe").success)
            assertTrue(g.sendEmail(customerId, studioId, customerEmail, "Twoja wizyta", "Treść").success)
            verify(exactly = 1) { smsProvider.send(customerPhone, "Auto gotowe", null) }
            verify(exactly = 1) { emailProvider.send(customerEmail, "Twoja wizyta", "Treść", emptyList()) }
            assertEquals(0.0, blockedCount("SMS"))
            assertEquals(0.0, blockedCount("EMAIL"))
        }
    }

    @Nested
    inner class OrderOfChecks {

        @Test
        fun `a studio without the module is blocked before the whitelist is even consulted`() {
            every { capabilityService.hasCapability(any(), any()) } returns false
            every { redirectService.activeFor(any()) } returns null
            val result = gateway(inForce()).sendTransactionalSms(studioId, customerPhone, "x")
            assertFalse(result.success)
            assertTrue(result.errorMessage!!.contains("nie jest aktywny"), result.errorMessage)
            assertEquals(0.0, blockedCount("SMS"))
        }

        @Test
        fun `a provider failure after a whitelisted send refunds the credit`() {
            every { redirectService.activeFor(any()) } returns null
            every { smsProvider.send(any(), any(), any()) } returns SmsDeliveryResult.failure("SMSAPI down")
            val result = gateway(inForce()).sendTransactionalSms(studioId, listedPhone, "x")
            assertFalse(result.success)
            verify(exactly = 1) { smsCreditService.refundCredit(any(), any()) }
        }
    }
}
