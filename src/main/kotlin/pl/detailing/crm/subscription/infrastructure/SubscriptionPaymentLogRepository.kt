package pl.detailing.crm.subscription.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SubscriptionPaymentLogRepository : JpaRepository<SubscriptionPaymentLogEntity, UUID> {

    fun findAllByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<SubscriptionPaymentLogEntity>

    fun countByStudioId(studioId: UUID): Long

    /**
     * Wpis historii zapisany przez kod sprzed V172 (bez `order_id`) dla danej płatności — dawny
     * kod zapisywał go w tej samej transakcji co efekt zamówienia i status PAID.
     */
    fun existsByOrderIdIsNullAndTransactionIdIn(transactionIds: Collection<String>): Boolean
}
