package pl.detailing.crm.smscampaigns.consent

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
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
 *   Account → Settings → 2-way SMS (Callback Addresses → 2Way SMS)
 *   → https://<your-domain>/api/sms/inbound
 *
 * SMSAPI POSTs the following form parameters (application/x-www-form-urlencoded):
 *   - sms_from – sender's phone number, e.g. "48100200300"
 *   - sms_to   – your virtual number that received the reply
 *   - sms_text – message body
 *   - sms_date – UNIX timestamp of receipt
 *   - username – SMSAPI account username
 *
 * IMPORTANT: SMSAPI requires the response body to contain the literal string "OK".
 * An HTTP 200 with an empty body is NOT sufficient — SMSAPI will retry the request.
 *
 * This endpoint is intentionally public (no session required) because SMSAPI
 * calls it from its own servers.  It is registered in [SecurityConfig] as permitAll.
 */
@RestController
@RequestMapping("/api/sms/inbound")
class SmsInboundController(
    private val smsConsentService: SmsConsentService,
    private val reservationUpsellConsentService: pl.detailing.crm.visitcard.upsell.ReservationUpsellConsentService
) {

    private val logger = LoggerFactory.getLogger(SmsInboundController::class.java)

    @PostMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun handleInbound(
        @RequestParam(name = "sms_from", required = false) smsFrom: String?,
        @RequestParam(name = "sms_text", required = false) smsText: String?,
        @RequestParam(name = "sms_to", required = false) smsTo: String?,
        @RequestParam(name = "sms_date", required = false) smsDate: String?,
        @RequestParam(name = "username", required = false) username: String?
    ): ResponseEntity<String> {

        if (smsFrom.isNullOrBlank() || smsText.isNullOrBlank()) {
            logger.warn("Inbound SMS callback missing required params: sms_from={} sms_text={}", smsFrom, smsText)
            // Still return "OK" so SMSAPI does not keep retrying a malformed request
            return ResponseEntity.ok("OK")
        }

        logger.info("Inbound SMS received | sms_from={} sms_to={} sms_date={} text={}", smsFrom, smsTo, smsDate, smsText)

        smsConsentService.processInboundReply(smsFrom, smsText)
        // Upsell requests made on a reservation card (pre-check-in) are tracked separately
        reservationUpsellConsentService.processInboundReply(smsFrom, smsText)

        // SMSAPI requires the literal string "OK" in the response body
        return ResponseEntity.ok("OK")
    }
}
