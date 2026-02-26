package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.finance.domain.CashRegister
import pl.detailing.crm.shared.CashRegisterId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for the studio's cash register (kasa fiskalna).
 *
 * One row per studio (enforced via unique constraint on studio_id).
 * The row is created lazily on the first cash-affecting operation.
 *
 * [balance] is updated atomically via a pessimistic write lock
 * (see [CashRegisterRepository.findByStudioIdForUpdate]) to prevent
 * concurrent modification under multi-user scenarios.
 */
@Entity
@Table(
    name = "cash_registers",
    uniqueConstraints = [UniqueConstraint(name = "uq_cash_registers_studio_id", columnNames = ["studio_id"])],
    indexes = [Index(name = "idx_cash_registers_studio_id", columnList = "studio_id")]
)
class CashRegisterEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    /** Current balance in grosz (1/100 PLN). Never negative in correct usage. */
    @Column(name = "balance", nullable = false)
    var balance: Long = 0L,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "PLN",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

) {
    fun toDomain(): CashRegister = CashRegister(
        id        = CashRegisterId(id),
        studioId  = StudioId(studioId),
        balance   = Money(balance),
        currency  = currency,
        updatedAt = updatedAt
    )
}
