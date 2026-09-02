package pl.detailing.crm.smscampaigns.consent

import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.campaigns.application.CampaignOptOutService
import pl.detailing.crm.visitcard.upsell.ReservationUpsellConsentService

/**
 * Broken Authentication — webhook SMSAPI.
 *
 * Luka: `POST /api/sms/inbound` nie miał żadnego uwierzytelnienia. Sfabrykowane
 * `sms_text=TAK` zatwierdzało płatne usługi na cudzej wizycie, `STOP` masowo wypisywało
 * klientów z kampanii — we wszystkich studiach naraz.
 */
class SmsInboundWebhookSecurityTest {

    private val consentService = mockk<SmsConsentService>(relaxed = true)
    private val upsellService = mockk<ReservationUpsellConsentService>(relaxed = true)
    private val optOutService = mockk<CampaignOptOutService>(relaxed = true)

    private fun mvc(secret: String) = MockMvcBuilders
        .standaloneSetup(SmsInboundController(consentService, upsellService, optOutService, secret))
        .build()

    @AfterEach
    fun tearDown() = clearAllMocks()

    private fun forgedReply() = post("/api/sms/inbound")
        .contentType("application/x-www-form-urlencoded")
        .param("sms_from", "48600100200")
        .param("sms_text", "TAK")

    @Test
    fun `forged reply without the secret is 403 and never processed`() {
        mvc("s3cr3t").perform(forgedReply())
            .andExpect(status().isForbidden)
            .andExpect(content().string("FORBIDDEN"))

        verify(exactly = 0) { consentService.processInboundReply(any(), any()) }
        verify(exactly = 0) { upsellService.processInboundReply(any(), any()) }
        verify(exactly = 0) { optOutService.processInboundReply(any(), any()) }
    }

    @Test
    fun `wrong secret is 403`() {
        mvc("s3cr3t").perform(forgedReply().param("secret", "s3cr3T"))
            .andExpect(status().isForbidden)
        verify(exactly = 0) { consentService.processInboundReply(any(), any()) }
    }

    @Test
    fun `fail closed - unconfigured secret disables the webhook`() {
        mvc("").perform(forgedReply().param("secret", ""))
            .andExpect(status().isForbidden)
        verify(exactly = 0) { optOutService.processInboundReply(any(), any()) }
    }

    @Test
    fun `genuine SMSAPI callback with the secret in the URL is processed and acknowledged with OK`() {
        mvc("s3cr3t").perform(forgedReply().param("secret", "s3cr3t"))
            .andExpect(status().isOk)
            .andExpect(content().string("OK"))
        verify(exactly = 1) { consentService.processInboundReply("48600100200", "TAK") }
    }

    @Test
    fun `secret may also travel in the header`() {
        mvc("s3cr3t").perform(forgedReply().header(SmsInboundController.HEADER_WEBHOOK_SECRET, "s3cr3t"))
            .andExpect(status().isOk)
    }
}
