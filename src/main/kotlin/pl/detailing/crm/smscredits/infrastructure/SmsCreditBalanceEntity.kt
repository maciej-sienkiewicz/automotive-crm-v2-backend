package pl.detailing.crm.smscredits.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscredits.domain.SmsCreditBalance
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "sms_credit_balances",
    indexes = [Index(name = "idx_sms_credit_balances_studio_id", columnList = "studio_id", unique = true)]
)
class SmsCreditBalanceEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid", unique = true)
    val studioId: UUID,

    @Column(name = "available_credits", nullable = false)
    var availableCredits: Int,

    @Column(name = "total_purchased", nullable = false)
    var totalPurchased: Int,

    @Column(name = "total_used", nullable = false)
    var totalUsed: Int,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0L,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): SmsCreditBalance = SmsCreditBalance(
        id = id,
        studioId = StudioId(studioId),
        availableCredits = availableCredits,
        totalPurchased = totalPurchased,
        totalUsed = totalUsed,
        updatedAt = updatedAt
    )

    fun applyDomain(domain: SmsCreditBalance) {
        availableCredits = domain.availableCredits
        totalPurchased = domain.totalPurchased
        totalUsed = domain.totalUsed
        updatedAt = domain.updatedAt
    }

    companion object {
        fun fromDomain(domain: SmsCreditBalance): SmsCreditBalanceEntity = SmsCreditBalanceEntity(
            id = domain.id,
            studioId = domain.studioId.value,
            availableCredits = domain.availableCredits,
            totalPurchased = domain.totalPurchased,
            totalUsed = domain.totalUsed,
            updatedAt = domain.updatedAt
        )
    }
}
