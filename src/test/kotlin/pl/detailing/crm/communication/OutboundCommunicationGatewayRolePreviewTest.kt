package pl.detailing.crm.communication

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.queue.OutboundMessageQueue
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.communication.whitelist.RecipientWhitelist
import pl.detailing.crm.communication.whitelist.RecipientWhitelistProperties
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscampaigns.sendername.SmsSenderNameResolver
import pl.detailing.crm.smscredits.SmsCreditService
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.util.UUID

/**
 * Wiadomość z piaskownicy podglądu roli nie dociera do operatora SMS ani do dostawcy poczty.
 * Proces, który ją wysyłał (przypomnienie, potwierdzenie wizyty), idzie dalej jak po wysyłce,
 * a panel podglądu pokazuje, co i do kogo by wyszło.
 */
class OutboundCommunicationGatewayRolePreviewTest {

    private val smsProvider: SmsProvider = mockk()
    private val emailProvider: EmailProvider = mockk()
    private val smsCreditService: SmsCreditService = mockk(relaxed = true)
    private val guard: RolePreviewOutboundGuard = mockk()

    private val gateway = OutboundCommunicationGateway(
        smsProvider, emailProvider, mockk<MarketingConsentChecker>(), smsCreditService,
        mockk<SmsSenderNameResolver> { every { resolve(any<UUID>()) } returns null },
        mockk<CapabilityService> { every { hasCapability(any(), any()) } returns true },
        SimpleMeterRegistry(), mockk<BusinessEventPublisher>(relaxed = true),
        mockk<CommunicationRedirectService> { every { activeFor(any()) } returns null },
        RecipientWhitelist(RecipientWhitelistProperties(enabled = false)),
        SendWindow.ALWAYS_OPEN, mockk<OutboundMessageQueue>(), guard
    )

    private val sandbox = UUID.randomUUID()

    init {
        every { guard.intercepts(sandbox, any(), any(), any()) } returns true
    }

    @Test
    fun `sms z piaskownicy nie dociera do operatora ani nie zjada kredytow`() {
        val result = gateway.sendTransactionalSms(sandbox, "+48000700800", "Auto gotowe do odbioru")

        assertTrue(result.success)
        verify(exactly = 0) { smsProvider.send(any(), any(), any()) }
        verify(exactly = 0) { smsCreditService.tryDeductCredit(any()) }
        verify(exactly = 1) { guard.intercepts(sandbox, SimulatedEffectChannel.SMS, "+48000700800", "Auto gotowe do odbioru") }
    }

    @Test
    fun `e-mail z piaskownicy nie dociera do dostawcy poczty`() {
        val result = gateway.sendTransactionalEmail(sandbox, "jan@example.com", "Zestawienie", "W załączeniu")

        assertTrue(result.success)
        verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
        verify(exactly = 1) { guard.intercepts(sandbox, SimulatedEffectChannel.EMAIL, "jan@example.com", "Zestawienie") }
    }
}
