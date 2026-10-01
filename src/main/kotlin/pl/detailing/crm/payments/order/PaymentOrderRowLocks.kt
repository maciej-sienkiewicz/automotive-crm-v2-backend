package pl.detailing.crm.payments.order

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import pl.detailing.crm.shared.persistence.FreshRowLock
import java.util.UUID

/** Blokada zamówienia ze świeżym stanem — patrz [FreshRowLock]. */
interface PaymentOrderRowLocks {
    fun lockById(id: UUID): PaymentOrderEntity?
    fun lockBySessionId(sessionId: String): PaymentOrderEntity?
}

class PaymentOrderRowLocksImpl : PaymentOrderRowLocks {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun lockById(id: UUID): PaymentOrderEntity? =
        FreshRowLock.lock(entityManager, PaymentOrderEntity::class.java, id)

    override fun lockBySessionId(sessionId: String): PaymentOrderEntity? =
        entityManager.createQuery("SELECT o.id FROM PaymentOrderEntity o WHERE o.sessionId = :sessionId", UUID::class.java)
            .setParameter("sessionId", sessionId)
            .resultList.firstOrNull()
            ?.let(::lockById)
}
