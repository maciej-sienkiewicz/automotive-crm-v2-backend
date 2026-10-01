package pl.detailing.crm.payments

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.payments.notification.PaymentNotificationProcessor
import pl.detailing.crm.payments.p24.Przelewy24Client

/**
 * Server-to-server payment status notifications from Przelewy24.
 *
 * Public endpoint (registered as permitAll in SecurityConfig) — authenticity is
 * established by the SHA-384 signature computed with the merchant CRC key, an
 * amount/currency cross-check against our order, and a mandatory verify call
 * back to the P24 API before the order is fulfilled.
 *
 * Kolejność po audycie (P1, P2, P5): podpis → TRWAŁY ZAPIS notyfikacji (inbox, idempotentny
 * po orderId P24) → obsługa ([PaymentNotificationProcessor]). Od chwili zapisu ponowienia są
 * nasze — 200 „OK" wraca także wtedy, gdy obsługa czeka na ponowienie (P24 i tak ponawia
 * notyfikacje do skutecznego `verify`, nie do kodu HTTP: 3, 5, 15, 30, 60, 150 i 450 min).
 * Jedyny przypadek, w którym odpowiadamy błędem serwera, to nieudany ZAPIS — wtedy
 * ponowienie P24 jest jedyną kopią informacji o płatności.
 *
 * P24 opisuje notyfikację raz jako JSON, raz jako pola formularza — przyjmujemy oba formaty.
 */
@RestController
@RequestMapping("/api/v1/payments/p24")
class Przelewy24WebhookController(
    private val p24Client: Przelewy24Client,
    private val processor: PaymentNotificationProcessor
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/status", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun handleJsonNotification(@RequestBody notification: Przelewy24Client.P24Notification): ResponseEntity<String> =
        handle(notification)

    @PostMapping("/status", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun handleFormNotification(@ModelAttribute notification: Przelewy24Client.P24Notification): ResponseEntity<String> =
        handle(notification)

    private fun handle(notification: Przelewy24Client.P24Notification): ResponseEntity<String> {
        logger.info(
            "P24 notification received sessionId={} orderId={} amount={}",
            notification.sessionId, notification.orderId, notification.amount
        )

        if (!p24Client.isNotificationSignValid(notification)) {
            logger.warn("P24 notification with INVALID signature sessionId={}", notification.sessionId)
            return ResponseEntity.badRequest().body("invalid signature")
        }

        val notificationId = try {
            processor.record(notification)
        } catch (e: Exception) {
            logger.error("P24 notification sessionId={} NOT STORED — P24 retry is the only copy", notification.sessionId, e)
            return ResponseEntity.internalServerError().body("storage error")
        }

        val outcome = try {
            processor.process(notificationId)
        } catch (e: Exception) {
            // Zapisana notyfikacja zostaje RECEIVED — dokończy ją worker rekoncyliacji.
            logger.error("P24 notification {} processing crashed — worker will retry", notificationId, e)
            null
        }
        logger.info("P24 notification {} sessionId={} → {}", notificationId, notification.sessionId, outcome)
        return ResponseEntity.ok("OK")
    }
}
