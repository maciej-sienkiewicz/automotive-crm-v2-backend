package pl.detailing.crm.smscredits.payment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

data class PaymentRequest(
    val amountInCents: Long,
    val currency: String,
    val description: String,
    val studioId: UUID
)

data class PaymentResult(
    val success: Boolean,
    val transactionId: String,
    val message: String
)

/**
 * Mock payment gateway — always succeeds and returns a fake transaction ID.
 * Replace with a real provider (Stripe, Przelewy24, etc.) before going live.
 * The interface contract is: return PaymentResult with success=true/false; never throw.
 */
@Service
class MockPaymentGateway {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun charge(request: PaymentRequest): PaymentResult {
        val transactionId = "MOCK-${UUID.randomUUID()}"
        val amountFormatted = BigDecimal(request.amountInCents).movePointLeft(2).toPlainString()

        logger.info(
            "[MOCK PAYMENT] studio={} amount={} {} desc='{}' → txId={}",
            request.studioId, amountFormatted, request.currency, request.description, transactionId
        )

        return PaymentResult(
            success = true,
            transactionId = transactionId,
            message = "Płatność przetworzona (tryb testowy)"
        )
    }
}
