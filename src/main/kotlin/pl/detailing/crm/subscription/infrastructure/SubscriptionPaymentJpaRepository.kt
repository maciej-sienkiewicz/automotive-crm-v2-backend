package pl.detailing.crm.subscription.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SubscriptionPaymentJpaRepository : JpaRepository<SubscriptionPaymentEntity, UUID> {

    fun findAllByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<SubscriptionPaymentEntity>
}
