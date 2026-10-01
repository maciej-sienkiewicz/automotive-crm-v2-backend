package pl.detailing.crm.payments.notification

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import pl.detailing.crm.shared.persistence.FreshRowLock
import java.util.UUID

/** Blokada notyfikacji ze świeżym stanem — patrz [FreshRowLock]. */
interface PaymentNotificationRowLocks {
    fun lockById(id: UUID): PaymentNotificationEntity?
}

class PaymentNotificationRowLocksImpl : PaymentNotificationRowLocks {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun lockById(id: UUID): PaymentNotificationEntity? =
        FreshRowLock.lock(entityManager, PaymentNotificationEntity::class.java, id)
}
