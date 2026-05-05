package pl.detailing.crm.smscredits.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.smscredits.domain.SmsCreditPackage
import java.util.UUID

@Entity
@Table(
    name = "sms_credit_packages",
    indexes = [Index(name = "idx_sms_credit_packages_active", columnList = "is_active")]
)
class SmsCreditPackageEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "name", nullable = false, length = 100)
    val name: String,

    @Column(name = "credit_amount", nullable = false)
    val creditAmount: Int,

    @Column(name = "price_gross_in_cents", nullable = false)
    val priceGrossInCents: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0
) {
    fun toDomain(): SmsCreditPackage = SmsCreditPackage(
        id = id,
        name = name,
        creditAmount = creditAmount,
        priceGrossInCents = priceGrossInCents,
        currency = currency,
        isActive = isActive
    )

    companion object {
        fun fromDomain(domain: SmsCreditPackage, sortOrder: Int = 0): SmsCreditPackageEntity = SmsCreditPackageEntity(
            id = domain.id,
            name = domain.name,
            creditAmount = domain.creditAmount,
            priceGrossInCents = domain.priceGrossInCents,
            currency = domain.currency,
            isActive = domain.isActive,
            sortOrder = sortOrder
        )
    }
}
