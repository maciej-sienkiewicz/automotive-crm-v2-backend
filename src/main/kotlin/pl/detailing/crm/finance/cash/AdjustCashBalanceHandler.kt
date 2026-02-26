package pl.detailing.crm.finance.cash

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.CashRegister
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Command for a manual cash-register adjustment.
 *
 * [amount] is a signed value in grosz:
 *   positive → add money (e.g. start-of-day float, cash deposit from elsewhere)
 *   negative → remove money (e.g. bank deposit, withdrawal by owner)
 *
 * [comment] describes the reason for the adjustment.
 * It is MANDATORY – the system enforces a non-blank comment to ensure a
 * complete audit trail for every manual balance change.
 * Examples: "Otwarcie kasy – stan początkowy", "Wpłata do banku", "Korekta niedoboru".
 */
data class AdjustCashBalanceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,

    /**
     * Signed amount in grosz.
     * Positive = money added to the register (deposit / opening float).
     * Negative = money removed from the register (withdrawal / bank deposit).
     * Zero is not allowed.
     */
    val amount: Long,

    /**
     * Mandatory description of why this adjustment was made.
     * Stored in the immutable [CashOperation] record.
     */
    val comment: String
)

/**
 * Records a manual adjustment to the studio's cash-register balance.
 *
 * ## Business rules:
 * - [comment] must be non-blank (audit-trail requirement)
 * - [amount] must be non-zero
 * - The new balance is computed as `current + amount`
 * - A pessimistic write lock is acquired on the [CashRegisterEntity] row to
 *   prevent concurrent balance corruption in multi-user scenarios
 * - A [CashOperationEntity] record is appended (immutable, never deleted)
 */
@Service
class AdjustCashBalanceHandler(
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(AdjustCashBalanceHandler::class.java)

    @Transactional
    fun handle(command: AdjustCashBalanceCommand): CashRegister {
        validate(command)

        // Acquire pessimistic write lock to prevent concurrent balance corruption
        val cashRegister = cashRegisterRepository.findByStudioIdForUpdate(command.studioId.value)
            ?: cashRegisterRepository.save(
                CashRegisterEntity(studioId = command.studioId.value, balance = 0L)
            )

        val balanceBefore = cashRegister.balance
        val balanceAfter  = balanceBefore + command.amount

        cashRegister.balance   = balanceAfter
        cashRegister.updatedAt = Instant.now()
        cashRegisterRepository.save(cashRegister)

        cashOperationRepository.save(
            CashOperationEntity(
                id                  = UUID.randomUUID(),
                studioId            = command.studioId.value,
                cashRegisterId      = cashRegister.id,
                amount              = command.amount,
                balanceBefore       = balanceBefore,
                balanceAfter        = balanceAfter,
                operationType       = CashOperationType.MANUAL_ADJUSTMENT,
                comment             = command.comment,
                financialDocumentId = null,
                createdBy           = command.userId.value
            )
        )

        log.info(
            "Cash manual adjustment: studio={} before={} amount={} after={} by={}",
            command.studioId, balanceBefore, command.amount, balanceAfter, command.userId
        )

        auditService.logSync(
            LogAuditCommand(
                studioId          = command.studioId,
                userId            = command.userId,
                userDisplayName   = command.userDisplayName,
                module            = AuditModule.CASH_REGISTER,
                entityId          = cashRegister.id.toString(),
                action            = AuditAction.CASH_ADJUSTED,
                metadata          = mapOf(
                    "amount"        to command.amount.toString(),
                    "balanceBefore" to balanceBefore.toString(),
                    "balanceAfter"  to balanceAfter.toString(),
                    "comment"       to command.comment
                )
            )
        )

        return cashRegister.toDomain()
    }

    private fun validate(command: AdjustCashBalanceCommand) {
        if (command.comment.isBlank()) {
            throw ValidationException(
                "Komentarz jest wymagany przy manualnej korekcie stanu kasy"
            )
        }
        if (command.amount == 0L) {
            throw ValidationException("Kwota korekty nie może wynosić zero")
        }
    }
}
