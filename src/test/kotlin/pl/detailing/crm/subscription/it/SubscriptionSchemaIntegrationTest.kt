package pl.detailing.crm.subscription.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.sql.Timestamp
import java.util.UUID

/**
 * Ograniczenia z V172 — ostatnia linia obrony, gdy kod się pomyli. Każdy test pokazuje
 * wiersz, który baza MUSI odrzucić (albo skasować kaskadą), wstawiony z pominięciem kodu.
 * Constraintów w module rozliczeń nie zdejmujemy: jeśli któryś z tych testów zaczyna
 * przeszkadzać zmianie, zmiana zarządza cyklem życia encji źle.
 */
class SubscriptionSchemaIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "schema")
    }

    private fun insertOrder(studioId: UUID, status: String, type: String = "ADD_ON_PURCHASE", addOns: String = "FINANCE_MODULE",
                            p24OrderId: Long? = null, paidAt: Timestamp? = null, amount: Long = 1_000) {
        jdbc.update(
            """INSERT INTO payment_orders (id, studio_id, session_id, order_type, status, plan_key, add_on_keys, amount_cents,
                   currency, description, p24_order_id, created_at, paid_at, version)
               VALUES (?, ?, ?, ?, ?, 'BASIC', ?, ?, 'PLN', 'test', ?, now(), ?, 0)""",
            UUID.randomUUID(), studioId, "CRM-${UUID.randomUUID()}", type, status, addOns, amount, p24OrderId, paidAt
        )
    }

    private fun insertPending(studioId: UUID, status: String = "PENDING") {
        jdbc.update(
            """INSERT INTO pending_plan_changes (id, studio_id, from_plan_key, to_plan_key, effective_at, requested_at, status, version)
               VALUES (?, ?, 'FULL', 'BASIC', now(), now(), ?, 0)""",
            UUID.randomUUID(), studioId, status
        )
    }

    @Test
    fun `V172 jest idempotentna - drugie wykonanie na zmigrowanym schemacie przechodzi`() {
        val sql = ClassPathResource("db/migration/V172__subscription_integrity.sql").getContentAsString(Charsets.UTF_8)
        assertDoesNotThrow { dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
    }

    @Test
    fun `jeden oczekujacy downgrade na studio`() {
        val studioId = studio(SubscriptionStatus.ACTIVE)
        insertPending(studioId)
        insertPending(studioId, status = "CANCELLED")

        assertThrows<DataIntegrityViolationException> { insertPending(studioId) }
    }

    @Test
    fun `jedno otwarte zamowienie na ten sam produkt - zamkniete nie blokuja`() {
        val studioId = studio(SubscriptionStatus.ACTIVE)
        insertOrder(studioId, "PENDING")
        insertOrder(studioId, "EXPIRED")
        insertOrder(studioId, "PENDING", addOns = "STATISTICS_MODULE")   // inny produkt

        assertThrows<DataIntegrityViolationException> { insertOrder(studioId, "PENDING") }
    }

    @Test
    fun `jedna platnosc P24 to jedno zamowienie`() {
        val studioId = studio(SubscriptionStatus.ACTIVE)
        val paidAt = Timestamp.from(clock.instant())
        insertOrder(studioId, "FULFILLED", p24OrderId = 77, paidAt = paidAt)

        assertThrows<DataIntegrityViolationException> {
            insertOrder(studioId, "FULFILLED", addOns = "STATISTICS_MODULE", p24OrderId = 77, paidAt = paidAt)
        }
    }

    @Test
    fun `zamowienie oplacone musi miec date platnosci, kwota nie moze byc ujemna`() {
        val studioId = studio(SubscriptionStatus.ACTIVE)

        assertThrows<DataIntegrityViolationException> { insertOrder(studioId, "PAID") }
        assertThrows<DataIntegrityViolationException> { insertOrder(studioId, "FULFILLED", addOns = "STATISTICS_MODULE") }
        assertThrows<DataIntegrityViolationException> { insertOrder(studioId, "PENDING", amount = -1) }
    }

    @Test
    fun `jeden efekt danego rodzaju na zamowienie w historii platnosci`() {
        val studioId = studio(SubscriptionStatus.ACTIVE)
        val orderId = UUID.randomUUID()
        val insert = {
            jdbc.update(
                """INSERT INTO subscription_payment_log (id, studio_id, event_type, amount_in_cents, currency, description, created_at, order_id)
                   VALUES (?, ?, 'SUBSCRIPTION_RENEWAL', 9900, 'PLN', 'test', now(), ?)""",
                UUID.randomUUID(), studioId, orderId
            )
        }
        insert()

        assertThrows<DataIntegrityViolationException> { insert() }
    }

    @Test
    fun `usuniecie studia kasuje jego plan, moduly i oczekujace zmiany`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = clock.instant(), addOns = listOf(AddOnKey.FINANCE_MODULE))
        insertPending(studioId)

        jdbc.update("DELETE FROM studios WHERE id = ?", studioId)

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_plans", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_add_ons", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pending_plan_changes", Long::class.java))
    }

    @Test
    fun `studia z zamowieniami nie da sie usunac kaskada - pieniadze nie znikaja`() {
        val studioId = studio(SubscriptionStatus.ACTIVE)
        order(studioId, PaymentOrderType.RENEWAL, 9_900, planKey = PlanKey.BASIC, status = PaymentOrderStatus.FULFILLED, p24OrderId = 5)

        assertThrows<DataIntegrityViolationException> { jdbc.update("DELETE FROM studios WHERE id = ?", studioId) }
    }

    @Test
    fun `plan i oczekujaca zmiana wymagaja istniejacego studia`() {
        val ghost = UUID.randomUUID()
        assertThrows<DataIntegrityViolationException> { insertPending(ghost) }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO studio_subscription_plans (id, studio_id, plan_id, activated_at, created_at, version) " +
                    "SELECT ?, ?, id, now(), now(), 0 FROM subscription_plans WHERE plan_key = 'BASIC'",
                UUID.randomUUID(), ghost
            )
        }
    }

    @Test
    fun `notyfikacja P24 jest zapisywana raz na platnosc`() {
        val insert = {
            jdbc.update(
                """INSERT INTO payment_notifications (id, provider, provider_order_id, session_id, amount_cents, currency, payload,
                       source, status, already_verified, attempts, received_at)
                   VALUES (?, 'P24', 123, 'CRM-x', 100, 'PLN', '{}'::jsonb, 'WEBHOOK', 'RECEIVED', false, 0, now())""",
                UUID.randomUUID()
            )
        }
        insert()

        assertThrows<DataIntegrityViolationException> { insert() }
    }
}
