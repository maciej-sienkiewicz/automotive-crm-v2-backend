package pl.detailing.crm.smscredits

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.smscredits.domain.SmsCreditBalance
import pl.detailing.crm.smscredits.domain.SmsCreditPackage
import pl.detailing.crm.smscredits.domain.SmsCreditPackageRepository
import pl.detailing.crm.smscredits.domain.SmsCreditTransaction
import pl.detailing.crm.smscredits.domain.SmsCreditTransactionType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class SmsCreditBalanceDto(
    val availableCredits: Int,
    val totalPurchased: Int,
    val totalUsed: Int,
    val updatedAt: Instant
)

data class SmsCreditPackageDto(
    val id: UUID,
    val name: String,
    val creditAmount: Int,
    val priceGross: BigDecimal,
    val currency: String,
    val pricePerCredit: BigDecimal
)

data class SmsCreditTransactionDto(
    val id: UUID,
    val type: SmsCreditTransactionType,
    val amount: Int,
    val balanceAfter: Int,
    val description: String,
    val referenceId: String?,
    val createdAt: Instant
)

data class SmsCreditTransactionPageDto(
    val items: List<SmsCreditTransactionDto>,
    val total: Long,
    val page: Int,
    val size: Int
)

data class PurchaseCreditsRequest(
    val packageId: UUID
)

data class PurchaseCreditsResponse(
    val availableCredits: Int,
    val message: String
)

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun SmsCreditBalance.toDto() = SmsCreditBalanceDto(
    availableCredits = availableCredits,
    totalPurchased = totalPurchased,
    totalUsed = totalUsed,
    updatedAt = updatedAt
)

private fun SmsCreditPackage.toDto() = SmsCreditPackageDto(
    id = id,
    name = name,
    creditAmount = creditAmount,
    priceGross = priceGross,
    currency = currency,
    pricePerCredit = BigDecimal(priceGrossInCents).movePointLeft(2)
        .divide(BigDecimal(creditAmount), 4, java.math.RoundingMode.HALF_UP)
)

private fun SmsCreditTransaction.toDto() = SmsCreditTransactionDto(
    id = id,
    type = type,
    amount = amount,
    balanceAfter = balanceAfter,
    description = description,
    referenceId = referenceId,
    createdAt = createdAt
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * REST surface for SMS credit management.
 *
 * GET  /api/v1/sms-credits/balance          → current balance (OWNER, MANAGER)
 * GET  /api/v1/sms-credits/packages         → pricing catalogue (OWNER, MANAGER)
 * POST /api/v1/sms-credits/purchase         → buy credits (OWNER only)
 * GET  /api/v1/sms-credits/transactions     → transaction history (OWNER, MANAGER)
 */
@RestController
@RequestMapping("/api/v1/sms-credits")
class SmsCreditController(
    private val smsCreditService: SmsCreditService,
    private val packageRepository: SmsCreditPackageRepository
) {

    @GetMapping("/balance")
    fun getBalance(): ResponseEntity<SmsCreditBalanceDto> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Brak uprawnień do podglądu salda kredytów SMS")
        }

        val balance = smsCreditService.getBalance(principal.studioId)
        return ResponseEntity.ok(balance.toDto())
    }

    @GetMapping("/packages")
    fun getPackages(): ResponseEntity<List<SmsCreditPackageDto>> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Brak uprawnień do podglądu cennika kredytów SMS")
        }

        val packages = packageRepository.findAllActive().map { it.toDto() }
        return ResponseEntity.ok(packages)
    }

    @PostMapping("/purchase")
    fun purchaseCredits(
        @RequestBody request: PurchaseCreditsRequest
    ): ResponseEntity<PurchaseCreditsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Zakup kredytów SMS jest dostępny wyłącznie dla właściciela studia")
        }

        val updatedBalance = smsCreditService.purchaseCredits(principal.studioId, request.packageId)

        return ResponseEntity.ok(
            PurchaseCreditsResponse(
                availableCredits = updatedBalance.availableCredits,
                message = "Kredyty SMS zostały pomyślnie dodane do konta"
            )
        )
    }

    @GetMapping("/transactions")
    fun getTransactions(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<SmsCreditTransactionPageDto> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Brak uprawnień do podglądu historii kredytów SMS")
        }

        val (items, total) = smsCreditService.getTransactions(principal.studioId, page, size)

        return ResponseEntity.ok(
            SmsCreditTransactionPageDto(
                items = items.map { it.toDto() },
                total = total,
                page = page,
                size = size
            )
        )
    }
}
