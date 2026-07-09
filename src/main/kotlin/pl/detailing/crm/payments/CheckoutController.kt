package pl.detailing.crm.payments

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.payments.checkout.CheckoutRequest
import pl.detailing.crm.payments.checkout.CheckoutResponse
import pl.detailing.crm.payments.checkout.CheckoutService
import pl.detailing.crm.payments.order.PaymentOrderEntity
import pl.detailing.crm.shared.ForbiddenException
import java.time.Instant
import java.util.UUID

data class PaymentOrderDto(
    val orderId: UUID,
    val type: String,
    val typeDisplayName: String,
    val status: String,
    val amountCents: Long,
    val currency: String,
    val description: String,
    val createdAt: Instant,
    val paidAt: Instant?,
    val failureReason: String?
)

/**
 * Buyer-facing checkout API (OWNER only):
 *
 *   POST /api/v1/subscription/checkout      → create a payment order; returns a P24
 *                                             payment URL, or null when fulfilled instantly
 *   GET  /api/v1/subscription/orders/{id}   → order status, polled by the payment
 *                                             return page until PAID/FAILED
 *
 * Both endpoints must stay reachable for EXPIRED studios (excluded from
 * SubscriptionInterceptor) — that's exactly when the user needs to pay.
 */
@RestController
@RequestMapping("/api/v1/subscription")
class CheckoutController(
    private val checkoutService: CheckoutService
) {

    @PostMapping("/checkout")
    fun checkout(@RequestBody request: CheckoutRequest): ResponseEntity<CheckoutResponse> {
        val principal = requireOwner()
        val response = checkoutService.checkout(principal.studioId, principal.email, request)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/orders/{orderId}")
    fun getOrder(@PathVariable orderId: UUID): ResponseEntity<PaymentOrderDto> {
        val principal = requireOwner()
        val order = checkoutService.getOrder(principal.studioId, orderId)
        return ResponseEntity.ok(order.toDto())
    }

    private fun requireOwner() = SecurityContextHelper.getCurrentUser().also {
        if (!it.isOwner) throw ForbiddenException("Zakupy subskrypcji są dostępne wyłącznie dla właściciela studia")
    }
}

private fun PaymentOrderEntity.toDto() = PaymentOrderDto(
    orderId = id,
    type = type.name,
    typeDisplayName = type.displayName,
    status = status.name,
    amountCents = amountCents,
    currency = currency,
    description = description,
    createdAt = createdAt,
    paidAt = paidAt,
    failureReason = failureReason
)
