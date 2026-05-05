package pl.detailing.crm.smscredits

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscredits.domain.SmsCreditBalance
import pl.detailing.crm.smscredits.domain.SmsCreditPackageRepository
import pl.detailing.crm.smscredits.domain.SmsCreditRepository
import pl.detailing.crm.smscredits.domain.SmsCreditTransaction
import pl.detailing.crm.smscredits.domain.SmsCreditTransactionType
import pl.detailing.crm.smscredits.payment.MockPaymentGateway
import pl.detailing.crm.smscredits.payment.PaymentRequest
import java.time.Instant
import java.util.UUID

@Service
class SmsCreditService(
    private val creditRepository: SmsCreditRepository,
    private val packageRepository: SmsCreditPackageRepository,
    private val paymentGateway: MockPaymentGateway
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ─── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun getBalance(studioId: StudioId): SmsCreditBalance =
        creditRepository.findBalanceByStudioId(studioId)
            ?: SmsCreditBalance.empty(studioId)

    @Transactional(readOnly = true)
    fun getTransactions(studioId: StudioId, page: Int, size: Int): Pair<List<SmsCreditTransaction>, Long> {
        require(page >= 0) { "Page must be >= 0" }
        require(size in 1..100) { "Size must be between 1 and 100" }
        val items = creditRepository.findTransactionsByStudioId(studioId, page, size)
        val total = creditRepository.countTransactionsByStudioId(studioId)
        return items to total
    }

    // ─── Credit deduction (called by OutboundCommunicationGateway only) ───────

    /**
     * Atomically checks and deducts one SMS credit using a pessimistic row-level lock
     * (SELECT FOR UPDATE). Two concurrent callers cannot both pass the balance check —
     * the second one will wait until the first commits, then see the updated balance.
     *
     * Returns true if a credit was successfully deducted, false if the balance is zero.
     * Never throws — a failure to deduct is a normal business outcome, not an error.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun tryDeductCredit(studioId: StudioId): Boolean {
        val balance = creditRepository.findBalanceByStudioIdForUpdate(studioId)
            ?: return false.also {
                logger.warn("SMS credit deduction rejected — no balance record for studio={}", studioId)
            }

        if (!balance.hasCredits()) {
            logger.warn("SMS credit deduction rejected — zero balance for studio={}", studioId)
            return false
        }

        val updated = balance.afterDeduction()
        creditRepository.saveBalance(updated)
        creditRepository.saveTransaction(
            SmsCreditTransaction(
                id = UUID.randomUUID(),
                studioId = studioId,
                type = SmsCreditTransactionType.DEDUCTION,
                amount = -1,
                balanceAfter = updated.availableCredits,
                description = "Wysłanie SMS",
                referenceId = null,
                createdAt = Instant.now()
            )
        )

        return true
    }

    /**
     * Refunds one credit when an SMS send attempt fails after deduction.
     * Runs in its own transaction so the refund is durable even if the caller rolls back.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun refundCredit(studioId: StudioId, reason: String) {
        val balance = creditRepository.findBalanceByStudioIdForUpdate(studioId) ?: return
        val updated = balance.afterRefund()
        creditRepository.saveBalance(updated)
        creditRepository.saveTransaction(
            SmsCreditTransaction(
                id = UUID.randomUUID(),
                studioId = studioId,
                type = SmsCreditTransactionType.REFUND,
                amount = 1,
                balanceAfter = updated.availableCredits,
                description = "Zwrot kredytu — $reason",
                referenceId = null,
                createdAt = Instant.now()
            )
        )
        logger.info("Refunded 1 SMS credit for studio={} reason='{}'", studioId, reason)
    }

    // ─── Purchase ─────────────────────────────────────────────────────────────

    @Transactional
    fun purchaseCredits(studioId: StudioId, packageId: UUID): SmsCreditBalance {
        val pkg = packageRepository.findById(packageId)
            ?: throw EntityNotFoundException("Pakiet SMS o id=$packageId nie istnieje")

        if (!pkg.isActive) {
            throw ValidationException("Wybrany pakiet SMS jest niedostępny")
        }

        val paymentResult = paymentGateway.charge(
            PaymentRequest(
                amountInCents = pkg.priceGrossInCents,
                currency = pkg.currency,
                description = "Zakup ${pkg.creditAmount} kredytów SMS — pakiet ${pkg.name}",
                studioId = studioId.value
            )
        )

        if (!paymentResult.success) {
            throw ValidationException("Płatność nie powiodła się: ${paymentResult.message}")
        }

        val current = creditRepository.findBalanceByStudioIdForUpdate(studioId)
            ?: SmsCreditBalance.empty(studioId)

        val updated = current.afterPurchase(pkg.creditAmount)
        val savedBalance = creditRepository.saveBalance(updated)

        creditRepository.saveTransaction(
            SmsCreditTransaction(
                id = UUID.randomUUID(),
                studioId = studioId,
                type = SmsCreditTransactionType.PURCHASE,
                amount = pkg.creditAmount,
                balanceAfter = savedBalance.availableCredits,
                description = "Zakup pakietu '${pkg.name}' (${pkg.creditAmount} SMS) za ${pkg.priceGross} ${pkg.currency}",
                referenceId = paymentResult.transactionId,
                createdAt = Instant.now()
            )
        )

        logger.info(
            "Studio={} purchased {} SMS credits (package='{}') txId={}",
            studioId, pkg.creditAmount, pkg.name, paymentResult.transactionId
        )

        return savedBalance
    }
}
