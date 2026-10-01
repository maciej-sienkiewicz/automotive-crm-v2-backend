package pl.detailing.crm.subscription.it

import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Duration

/**
 * Blokady pod open-session-in-view. W aplikacji kontekst persystencji żyje przez całe żądanie
 * HTTP (domyślne `spring.jpa.open-in-view=true`), więc encja wczytana na początku żądania
 * wracała z „blokady" bez zmian zatwierdzonych w międzyczasie przez inny wątek — decyzja pod
 * blokadą szła na starym stanie. Test odtwarza to tak, jak robi to interceptor OSIV: jeden
 * EntityManager przypięty do wątku na cały test.
 */
class OpenSessionInViewLockingIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "osiv_locking")
    }

    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private fun <T> inRequest(body: () -> T): T {
        val holder = EntityManagerHolder(entityManagerFactory.createEntityManager())
        TransactionSynchronizationManager.bindResource(entityManagerFactory, holder)
        try {
            return body()
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory)
            holder.entityManager.close()
        }
    }

    @Test
    fun `blokada studia widzi zmiane zatwierdzona po wczytaniu go w tym samym zadaniu`() {
        val studioId = studio(SubscriptionStatus.TRIALING, trialEndsAt = clock.instant().plus(Duration.ofDays(5)))

        val statusUnderLock = inRequest {
            studioRepository.findByStudioId(studioId)                       // np. wycena w checkoucie
            jdbc.update("UPDATE studios SET subscription_status = 'ACTIVE' WHERE id = ?", studioId)   // inny wątek
            tx.execute { studioRepository.lockById(studioId)!!.subscriptionStatus }
        }

        assertEquals(SubscriptionStatus.ACTIVE, statusUnderLock)
    }

    @Test
    fun `blokada zamowienia i notyfikacji widzi stan po cudzym commicie`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC)

        val (byId, bySession) = inRequest {
            orderRepository.findById(order.id).orElseThrow()               // krok decyzji webhooka
            jdbc.update("UPDATE payment_orders SET status = 'PAID', paid_at = now(), version = version + 1 WHERE id = ?", order.id)
            tx.execute { orderRepository.lockById(order.id)!!.status to orderRepository.lockBySessionId(order.sessionId)!!.status }!!
        }

        assertEquals(PaymentOrderStatus.PAID, byId)
        assertEquals(PaymentOrderStatus.PAID, bySession)
    }

    @Test
    fun `stan uprawnien do decyzji pienieznych widzi modul wlaczony w miedzyczasie`() {
        val studioId = activeStudio(PlanKey.BASIC)

        val addOnsUnderLock = inRequest {
            entitlementService.readCurrent(StudioId(studioId))               // wczytuje plan i (pustą) kolekcję modułów
            entitlementService.activateAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE).let { }   // w tym żądaniu
            jdbc.update("DELETE FROM studio_subscription_add_ons")           // inny wątek wyłącza moduł
            tx.execute { entitlementService.readCurrent(StudioId(studioId)).activeAddOnKeys }!!
        }

        assertEquals(emptySet<AddOnKey>(), addOnsUnderLock)
    }

    @BeforeEach @AfterEach
    fun noLeakedHolder() {
        if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory)
        }
    }
}
