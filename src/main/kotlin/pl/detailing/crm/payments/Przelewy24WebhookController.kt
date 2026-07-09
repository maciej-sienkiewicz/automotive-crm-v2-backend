package pl.detailing.crm.payments

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.payments.checkout.CheckoutService
import pl.detailing.crm.payments.order.PaymentOrderRepository
import pl.detailing.crm.payments.p24.Przelewy24Client

/**
 * Server-to-server payment status notifications from Przelewy24.
 *
 * Public endpoint (registered as permitAll in SecurityConfig) — authenticity is
 * established by the SHA-384 signature computed with the merchant CRC key, an
 * amount/currency cross-check against our order, and a mandatory verify call
 * back to the P24 API before the order is fulfilled.
 *
 * P24 retries the notification until it receives HTTP 200 with body "OK",
 * so any processing error must return a non-200 status.
 */
@RestController
@RequestMapping("/api/v1/payments/p24")
class Przelewy24WebhookController(
    private val p24Client: Przelewy24Client,
    private val checkoutService: CheckoutService,
    private val orderRepository: PaymentOrderRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/status")
    fun handleStatusNotification(@RequestBody notification: Przelewy24Client.P24Notification): ResponseEntity<String> {
        logger.info("P24 notification received sessionId={} orderId={} amount={}",
            notification.sessionId, notification.orderId, notification.amount)

        if (!p24Client.isNotificationSignValid(notification)) {
            logger.warn("P24 notification with INVALID signature sessionId={}", notification.sessionId)
            return ResponseEntity.badRequest().body("invalid signature")
        }

        val order = orderRepository.findBySessionId(notification.sessionId)
        if (order == null) {
            logger.warn("P24 notification for unknown sessionId={}", notification.sessionId)
            return ResponseEntity.badRequest().body("unknown session")
        }

        if (notification.amount != order.amountCents || notification.currency != order.currency) {
            checkoutService.failOrder(
                notification.sessionId,
                "Kwota/waluta notyfikacji (${notification.amount} ${notification.currency}) niezgodna z zamówieniem (${order.amountCents} ${order.currency})"
            )
            logger.error("P24 notification amount mismatch sessionId={}", notification.sessionId)
            return ResponseEntity.badRequest().body("amount mismatch")
        }

        return try {
            p24Client.verifyTransaction(notification.sessionId, notification.orderId, notification.amount)
            checkoutService.completeOrder(notification.sessionId, notification.orderId)
            ResponseEntity.ok("OK")
        } catch (e: Exception) {
            logger.error("P24 notification processing failed sessionId={}: {}", notification.sessionId, e.message, e)
            ResponseEntity.internalServerError().body("processing error")
        }
    }
}
