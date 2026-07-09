package pl.detailing.crm.payments.order

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentOrderRepository : JpaRepository<PaymentOrderEntity, UUID> {
    fun findBySessionId(sessionId: String): PaymentOrderEntity?
    fun findByIdAndStudioId(id: UUID, studioId: UUID): PaymentOrderEntity?
}
