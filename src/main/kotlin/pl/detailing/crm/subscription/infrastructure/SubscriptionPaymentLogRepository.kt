package pl.detailing.crm.subscription.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SubscriptionPaymentLogRepository : JpaRepository<SubscriptionPaymentLogEntity, UUID> {

    fun findAllByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<SubscriptionPaymentLogEntity>

    fun countByStudioId(studioId: UUID): Long
}
