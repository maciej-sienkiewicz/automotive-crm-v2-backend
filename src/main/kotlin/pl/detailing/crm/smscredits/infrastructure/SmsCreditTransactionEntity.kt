package pl.detailing.crm.smscredits.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscredits.domain.SmsCreditTransaction
import pl.detailing.crm.smscredits.domain.SmsCreditTransactionType
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "sms_credit_transactions",
    indexes = [
        Index(name = "idx_sms_credit_tx_studio_created", columnList = "studio_id, created_at"),
        Index(name = "idx_sms_credit_tx_type", columnList = "studio_id, type")
    ]
)
class SmsCreditTransactionEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    val type: SmsCreditTransactionType,

    @Column(name = "amount", nullable = false)
    val amount: Int,

    @Column(name = "balance_after", nullable = false)
    val balanceAfter: Int,

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    val description: String,

    @Column(name = "reference_id", nullable = true, length = 255)
    val referenceId: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): SmsCreditTransaction = SmsCreditTransaction(
        id = id,
        studioId = StudioId(studioId),
        type = type,
        amount = amount,
        balanceAfter = balanceAfter,
        description = description,
        referenceId = referenceId,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(domain: SmsCreditTransaction): SmsCreditTransactionEntity = SmsCreditTransactionEntity(
            id = domain.id,
            studioId = domain.studioId.value,
            type = domain.type,
            amount = domain.amount,
            balanceAfter = domain.balanceAfter,
            description = domain.description,
            referenceId = domain.referenceId,
            createdAt = domain.createdAt
        )
    }
}
