package pl.detailing.crm.smscampaigns.consent

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Webhook endpoint that receives inbound (MO – Mobile Originated) SMS messages
 * forwarded by SMSAPI.
 *
 * Configure the callback URL in the SMSAPI panel:
 *   Account → Settings → 2-way SMS → Callback URL
 *   → https://<your-domain>/api/sms/inbound
 *
 * SMSAPI POSTs the following form parameters on every inbound message:
 *   - phone    – sender's phone number (e.g. "48100200300")
 *   - to       – destination virtual number
 *   - message  – message content
 *   - moMsgId  – MO message identifier
 *   - dateSent – ISO timestamp
 *
 * This endpoint is intentionally public (no session required) because SMSAPI
 * calls it from its own servers.  It is registered in [SecurityConfig] as permitAll.
 */
@RestController
@RequestMapping("/api/sms/inbound")
class SmsInboundController(
    private val smsConsentService: SmsConsentService
) {

    private val logger = LoggerFactory.getLogger(SmsInboundController::class.java)

    @PostMapping
    fun handleInbound(
        @RequestParam(name = "phone", required = false) phone: String?,
        @RequestParam(name = "message", required = false) message: String?,
        @RequestParam(name = "moMsgId", required = false) moMsgId: String?,
        @RequestParam(name = "dateSent", required = false) dateSent: String?
    ): ResponseEntity<Void> {

        if (phone.isNullOrBlank() || message.isNullOrBlank()) {
            logger.warn("Inbound SMS callback missing required params: phone={} message={}", phone, message)
            return ResponseEntity.badRequest().build()
        }

        logger.info("Inbound SMS received | moMsgId={} phone={} message={}", moMsgId, phone, message)

        smsConsentService.processInboundReply(phone, message)

        // SMSAPI expects HTTP 200 to acknowledge delivery
        return ResponseEntity.ok().build()
    }
}
