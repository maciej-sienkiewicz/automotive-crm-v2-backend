package pl.detailing.crm.subscription.it

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestTemplate
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.payments.checkout.CheckoutService
import pl.detailing.crm.payments.checkout.OrderFulfillmentService
import pl.detailing.crm.payments.checkout.SubscriptionFulfillmentSideEffects
import pl.detailing.crm.payments.notification.PaymentNotificationProcessor
import pl.detailing.crm.payments.order.PaymentOrderEntity
import pl.detailing.crm.payments.order.PaymentOrderRepository
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.payments.p24.Przelewy24Config
import pl.detailing.crm.payments.p24.Przelewy24Properties
import pl.detailing.crm.payments.reconciliation.PaymentReconciliationJob
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.smscampaigns.CommunicationOnboardingService
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.StudioProvisioningService
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.subscription.entitlement.EntitlementCacheInvalidator
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.StudioAddOnRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.StudioSubscriptionPlanRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import pl.detailing.crm.subscription.lifecycle.SubscriptionConfig
import pl.detailing.crm.subscription.lifecycle.SubscriptionLifecycleJob
import pl.detailing.crm.subscription.lifecycle.SubscriptionTransitionProcessor
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.management.PlanDowngradeScheduler
import pl.detailing.crm.subscription.management.PlanManagementService
import pl.detailing.crm.subscription.management.ScheduledPlanChangeApplier
import pl.detailing.crm.subscription.management.SubscriptionReconciliationJob
import pl.detailing.crm.subscription.pricing.PricingService
import pl.detailing.crm.subscription.pricing.ProrationService
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Wspólna podstawa testów integracyjnych subskrypcji i płatności na prawdziwym Postgresie.
 *
 * ── Dlaczego nie zwykły @DataJpaTest ─────────────────────────────────────────
 *
 * Domyślny `@DataJpaTest` owija test w jedną transakcję wycofywaną na końcu — a wtedy nie
 * widać żadnego z błędów, przed którymi te testy chronią: flush przy commicie, transakcja
 * oznaczona rollback-only, utracona aktualizacja, wyścig dwóch transakcji. Stąd
 * `Propagation.NOT_SUPPORTED`: każda metoda serwisu zatwierdza się naprawdę, jak w produkcji.
 *
 * ── Schemat ──────────────────────────────────────────────────────────────────
 *
 * Hibernate zakłada tabele (`create-drop`), a potem nakładamy na nie V172 — tą samą drogą,
 * którą migracja przechodzi na środowisku z `ddl-auto=update`. Ograniczeń z V172 (unikaty
 * częściowe, klucze obce, CHECK-i) Hibernate nie zna, a to one są ostatnią linią obrony
 * przed podwójną realizacją; test bez nich sprawdzałby inną bazę niż produkcyjna.
 *
 * ── Uruchamianie ─────────────────────────────────────────────────────────────
 *
 * `@Tag("testcontainers")` — poza domyślnym `./gradlew test`:
 * `./gradlew test -PksefStub -PrunTestcontainers`. Bez Dockera: istniejący serwer przez
 * `SUBSCRIPTION_IT_JDBC_URL` (zob. [SubscriptionItDatabase] — każda klasa dostaje własną bazę).
 *
 * Każda klasa potomna MUSI mieć własny `@DynamicPropertySource` z [SubscriptionItDatabase.register]
 * i unikalną nazwą — inaczej dwie klasy dzieliłyby kontekst i bazę, a biegną równolegle.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=12",
        "p24.mock-mode=false",
        "p24.sandbox=true",
        "p24.merchant-id=11111",
        "p24.pos-id=11111",
        "p24.crc=test-crc",
        "p24.api-key=test-api-key",
        "p24.frontend-base-url=https://app.test",
        "p24.backend-base-url=https://api.test",
        "p24.transaction-time-limit-minutes=15",
        "p24.order-expiry-minutes=30",
        "p24.reconcile-after-minutes=20",
        "subscription.grace-period-days=7"
    ]
)
@Import(
    SubscriptionIntegrationTestBase.Beans::class,
    SubscriptionConfig::class, SubscriptionAccessPolicy::class,
    EntitlementCacheInvalidator::class, EntitlementService::class, CapabilityService::class,
    ProrationService::class, PricingService::class, PlanManagementService::class,
    PlanDowngradeScheduler::class, ScheduledPlanChangeApplier::class,
    SubscriptionLifecycleJob::class, SubscriptionTransitionProcessor::class,
    StudioProvisioningService::class, SubscriptionService::class,
    OrderFulfillmentService::class, SubscriptionFulfillmentSideEffects::class, CheckoutService::class,
    Przelewy24Config::class, PaymentNotificationProcessor::class, PaymentReconciliationJob::class,
    SubscriptionReconciliationJob::class
)
abstract class SubscriptionIntegrationTestBase {

    @TestConfiguration
    @EnableCaching
    class Beans {
        @Bean @Primary fun clock(): MutableClock = MutableClock()
        @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
        @Bean fun cacheManager(): CacheManager = ConcurrentMapCacheManager(EntitlementCacheInvalidator.CACHE_NAME)
        @Bean fun fakeP24Gateway(): FakeP24Gateway = FakeP24Gateway()
        @Bean fun przelewy24Client(properties: Przelewy24Properties, gateway: FakeP24Gateway): Przelewy24Client =
            Przelewy24Client(properties, RestTemplate().apply { interceptors.add(gateway) })
        @Bean fun protocolProvisioner(): DefaultProtocolTemplateProvisioner = mockk(relaxed = true)
        @Bean fun consentProvisioner(): DefaultMarketingConsentProvisioner = mockk(relaxed = true)
        @Bean fun communicationOnboarding(): CommunicationOnboardingService = mockk(relaxed = true)
        @Bean fun rolePreviewGuard(): RolePreviewOutboundGuard = mockk(relaxed = true)

        /** V172 na schemacie Hibernate'a — raz na kontekst, po utworzeniu tabel (zależność od EMF). */
        @Bean fun v172(entityManagerFactory: EntityManagerFactory, dataSource: DataSource): AppliedMigration {
            val sql = ClassPathResource("db/migration/V172__subscription_integrity.sql").getContentAsString(Charsets.UTF_8)
            // Cały skrypt jednym poleceniem: sterownik Postgresa sam dzieli go na instrukcje
            // i rozumie bloki `DO $$ … $$`, których nie rozumie dzielenie po średnikach.
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
            return AppliedMigration("V172")
        }
    }

    data class AppliedMigration(val version: String)

    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var gateway: FakeP24Gateway
    @Autowired lateinit var meterRegistry: MeterRegistry
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var p24Properties: Przelewy24Properties
    @Autowired lateinit var p24Client: Przelewy24Client

    @Autowired lateinit var studioRepository: StudioRepository
    @Autowired lateinit var planRepository: PlanJpaRepository
    @Autowired lateinit var addOnRepository: AddOnJpaRepository
    @Autowired lateinit var subscriptionPlanRepository: StudioSubscriptionPlanRepository
    @Autowired lateinit var studioAddOnRepository: StudioAddOnRepository
    @Autowired lateinit var pendingRepository: PendingPlanChangeRepository
    @Autowired lateinit var orderRepository: PaymentOrderRepository
    @Autowired lateinit var paymentLogRepository: SubscriptionPaymentLogRepository

    @Autowired lateinit var accessPolicy: SubscriptionAccessPolicy
    @Autowired lateinit var entitlementService: EntitlementService
    @Autowired lateinit var capabilityService: CapabilityService
    @Autowired lateinit var prorationService: ProrationService
    @Autowired lateinit var pricingService: PricingService
    @Autowired lateinit var planManagementService: PlanManagementService
    @Autowired lateinit var downgradeScheduler: PlanDowngradeScheduler
    @Autowired lateinit var scheduledChangeApplier: ScheduledPlanChangeApplier
    @Autowired lateinit var lifecycleJob: SubscriptionLifecycleJob
    @Autowired lateinit var subscriptionService: SubscriptionService
    @Autowired lateinit var fulfillmentService: OrderFulfillmentService
    @Autowired lateinit var checkoutService: CheckoutService
    @Autowired lateinit var notificationProcessor: PaymentNotificationProcessor
    @Autowired lateinit var paymentReconciliationJob: PaymentReconciliationJob
    @Autowired lateinit var subscriptionReconciliationJob: SubscriptionReconciliationJob
    @Autowired lateinit var cacheManager: CacheManager

    protected val jdbc by lazy { JdbcTemplate(dataSource) }
    protected val tx by lazy { TransactionTemplate(transactionManager) }

    @BeforeEach
    fun resetWorld() {
        dropPoisonTriggers()
        jdbc.execute(
            """TRUNCATE studio_subscription_add_ons, studio_subscription_plans, pending_plan_changes,
               payment_notifications, payment_orders, subscription_payment_log, studios,
               subscription_plan_features, subscription_add_on_features,
               subscription_add_ons, subscription_plans, subscription_features CASCADE"""
        )
        cacheManager.getCache(EntitlementCacheInvalidator.CACHE_NAME)?.clear()
        clock.set(MutableClock.DEFAULT_START)
        gateway.reset()
        seedCatalog()
    }

    @AfterEach
    fun dropPoisonTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_poison ON studio_subscription_plans")
        jdbc.execute("DROP TRIGGER IF EXISTS it_poison ON studios")
        jdbc.execute("DROP TRIGGER IF EXISTS it_poison ON payment_orders")
    }

    // ── Katalog ──────────────────────────────────────────────────────────────

    /**
     * Katalog wprost przez SQL: plany i moduły z cenami brutto oraz przypisane funkcje.
     * BASIC ma funkcje podstawowe, FULL — wszystkie, każdy moduł — swoją jedną.
     */
    private fun seedCatalog() {
        val featureIds = FeatureKey.entries.associateWith { UUID.randomUUID() }
        featureIds.forEach { (key, id) ->
            jdbc.update("INSERT INTO subscription_features (id, feature_key, name, is_active) VALUES (?, ?, ?, true)", id, key.name, key.displayName)
        }
        val basicFeatures = listOf(FeatureKey.CALENDAR, FeatureKey.VISITS, FeatureKey.CUSTOMERS, FeatureKey.VEHICLES, FeatureKey.DOCUMENTS, FeatureKey.GALLERY)
        plan(PlanKey.BASIC, "Podstawowy", BASIC_PRICE, 1, basicFeatures.map(featureIds::getValue))
        plan(PlanKey.FULL, "Pełny", FULL_PRICE, 2, featureIds.values.toList())
        addOn(AddOnKey.FINANCE_MODULE, "Finanse", FINANCE_PRICE, featureIds.getValue(FeatureKey.FINANCE))
        addOn(AddOnKey.CLIENT_COMMUNICATION, "Komunikacja", COMMUNICATION_PRICE, featureIds.getValue(FeatureKey.SMS_EMAIL))
        addOn(AddOnKey.STATISTICS_MODULE, "Statystyki", STATISTICS_PRICE, featureIds.getValue(FeatureKey.STATISTICS))
    }

    private fun plan(key: PlanKey, name: String, price: Long, order: Int, features: List<UUID>) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO subscription_plans (id, plan_key, name, monthly_price_gross_cents, is_active, display_order) VALUES (?, ?, ?, ?, true, ?)",
            id, key.name, name, price, order
        )
        features.forEach { jdbc.update("INSERT INTO subscription_plan_features (plan_id, feature_id) VALUES (?, ?)", id, it) }
    }

    private fun addOn(key: AddOnKey, name: String, price: Long, feature: UUID) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO subscription_add_ons (id, add_on_key, name, monthly_price_gross_cents, is_active, is_available) VALUES (?, ?, ?, ?, true, true)",
            id, key.name, name, price
        )
        jdbc.update("INSERT INTO subscription_add_on_features (add_on_id, feature_id) VALUES (?, ?)", id, feature)
    }

    // ── Studia ───────────────────────────────────────────────────────────────

    /** Studio w danym stanie rozliczeniowym, BEZ wiersza planu. */
    protected fun studio(
        status: SubscriptionStatus,
        trialEndsAt: Instant? = null,
        endsAt: Instant? = null,
        graceEndsAt: Instant? = null
    ): UUID {
        val id = UUID.randomUUID()
        studioRepository.save(
            StudioEntity(
                id = id, name = "Studio $id", subscriptionStatus = status, trialEndsAt = trialEndsAt,
                subscriptionEndsAt = endsAt, trialUsed = trialEndsAt != null,
                emailAlias = id.toString().replace("-", ""), createdAt = clock.instant(), graceEndsAt = graceEndsAt
            )
        )
        return id
    }

    /** Studio z planem (i modułami) — stan po zakupie. */
    protected fun studioWithPlan(
        status: SubscriptionStatus,
        plan: PlanKey,
        endsAt: Instant? = null,
        trialEndsAt: Instant? = null,
        addOns: List<AddOnKey> = emptyList(),
        graceEndsAt: Instant? = null
    ): UUID {
        val id = studio(status, trialEndsAt = trialEndsAt, endsAt = endsAt, graceEndsAt = graceEndsAt)
        entitlementService.changePlan(StudioId(id), plan)
        addOns.forEach { entitlementService.activateAddOn(StudioId(id), it) }
        return id
    }

    protected fun activeStudio(plan: PlanKey = PlanKey.BASIC, daysLeft: Long = 20, addOns: List<AddOnKey> = emptyList()): UUID =
        studioWithPlan(SubscriptionStatus.ACTIVE, plan, endsAt = clock.instant().plus(java.time.Duration.ofDays(daysLeft)), addOns = addOns)

    // ── Zamówienia i płatności ───────────────────────────────────────────────

    /** Zamówienie zapisane wprost (bez checkoutu) — do scenariuszy realizacji. */
    protected fun order(
        studioId: UUID,
        type: PaymentOrderType,
        amount: Long,
        planKey: PlanKey? = null,
        addOns: List<AddOnKey> = emptyList(),
        status: PaymentOrderStatus = PaymentOrderStatus.PENDING,
        p24OrderId: Long? = null,
        sessionId: String = "CRM-${UUID.randomUUID()}",
        token: String? = null
    ): PaymentOrderEntity = orderRepository.save(
        PaymentOrderEntity(
            studioId = studioId, sessionId = sessionId, type = type, planKey = planKey, p24Token = token,
            addOnKeysRaw = PaymentOrderEntity.encodeAddOnKeys(addOns), amountCents = amount, description = type.displayName,
            createdAt = clock.instant(), status = status, p24OrderId = p24OrderId,
            paidAt = if (status.isPaid) clock.instant() else null
        )
    )

    /** Notyfikacja P24 z poprawnym podpisem — tak, jak przychodzi na webhook. */
    protected fun signedNotification(sessionId: String, amount: Long, p24OrderId: Long, currency: String = "PLN"): Przelewy24Client.P24Notification {
        val unsigned = Przelewy24Client.P24Notification(
            merchantId = p24Properties.merchantId, posId = p24Properties.posId, sessionId = sessionId,
            amount = amount, originAmount = amount, currency = currency, orderId = p24OrderId, methodId = 25,
            statement = "p24-$p24OrderId"
        )
        return unsigned.copy(sign = p24Client.notificationSign(unsigned))
    }

    /** Kupujący płaci w P24, a P24 wysyła notyfikację — pełna ścieżka webhooka bez HTTP do nas. */
    protected fun payAndNotify(order: PaymentOrderEntity) =
        payAndNotify(order.sessionId, order.amountCents)

    protected fun payAndNotify(sessionId: String, amount: Long) =
        notificationProcessor.process(notificationProcessor.record(signedNotification(sessionId, amount, gateway.pay(sessionId, amount))))

    // ── Odczyty z bazy (z pominięciem JPA — prawda o tym, co zatwierdzono) ─────

    protected fun planOf(studioId: UUID): PlanKey? = jdbc.queryForList(
        """SELECT p.plan_key FROM studio_subscription_plans s JOIN subscription_plans p ON p.id = s.plan_id
           WHERE s.studio_id = ?""", String::class.java, studioId
    ).firstOrNull()?.let(PlanKey::valueOf)

    protected fun addOnsOf(studioId: UUID): Set<AddOnKey> = jdbc.queryForList(
        """SELECT a.add_on_key FROM studio_subscription_add_ons sa
           JOIN studio_subscription_plans s ON s.id = sa.studio_subscription_plan_id
           JOIN subscription_add_ons a ON a.id = sa.add_on_id
           WHERE s.studio_id = ?""", String::class.java, studioId
    ).map(AddOnKey::valueOf).toSet()

    protected fun pendingStatuses(studioId: UUID): List<String> = jdbc.queryForList(
        "SELECT status FROM pending_plan_changes WHERE studio_id = ? ORDER BY requested_at, id", String::class.java, studioId
    )

    protected fun statusOf(studioId: UUID): SubscriptionStatus =
        SubscriptionStatus.valueOf(jdbc.queryForObject("SELECT subscription_status FROM studios WHERE id = ?", String::class.java, studioId)!!)

    protected fun endsAtOf(studioId: UUID): Instant? =
        jdbc.queryForObject("SELECT subscription_ends_at FROM studios WHERE id = ?", Timestamp::class.java, studioId)?.toInstant()

    protected fun graceEndsAtOf(studioId: UUID): Instant? =
        jdbc.queryForObject("SELECT grace_ends_at FROM studios WHERE id = ?", Timestamp::class.java, studioId)?.toInstant()

    protected fun orderStatus(orderId: UUID): PaymentOrderStatus =
        PaymentOrderStatus.valueOf(jdbc.queryForObject("SELECT status FROM payment_orders WHERE id = ?", String::class.java, orderId)!!)

    protected fun ledgerCount(studioId: UUID, eventType: String? = null): Long =
        if (eventType == null) jdbc.queryForObject("SELECT count(*) FROM subscription_payment_log WHERE studio_id = ?", Long::class.java, studioId)!!
        else jdbc.queryForObject(
            "SELECT count(*) FROM subscription_payment_log WHERE studio_id = ? AND event_type = ?", Long::class.java, studioId, eventType
        )!!

    protected fun counter(name: String, vararg tags: String): Double =
        meterRegistry.find(name).tags(*tags).counters().sumOf { it.count() }

    // ── Awaria jednego wiersza ───────────────────────────────────────────────

    /**
     * Wyzwalacz, który wywraca zapis wierszy jednego studia w [table] — model dowolnego błędu
     * bazy dla jednego tenanta (timeout blokady, ograniczenie, zerwane połączenie). Liczy się
     * mechanizm propagacji, nie przyczyna. [condition] to dodatkowy warunek na `NEW`.
     */
    protected fun poisonWrites(table: String, studioColumn: String, studioId: UUID, condition: String = "TRUE") {
        jdbc.execute(
            """CREATE OR REPLACE FUNCTION it_poison_${table}_fn() RETURNS trigger AS $$
               BEGIN
                 IF NEW.$studioColumn = '$studioId' AND ($condition) THEN
                   RAISE EXCEPTION 'awaria studia % (test)', NEW.$studioColumn;
                 END IF;
                 RETURN NEW;
               END $$ LANGUAGE plpgsql"""
        )
        jdbc.execute("CREATE TRIGGER it_poison BEFORE INSERT OR UPDATE ON $table FOR EACH ROW EXECUTE FUNCTION it_poison_${table}_fn()")
    }

    companion object {
        const val BASIC_PRICE = 9_900L
        const val FULL_PRICE = 29_900L
        const val FINANCE_PRICE = 4_900L
        const val COMMUNICATION_PRICE = 3_900L
        const val STATISTICS_PRICE = 2_900L
        const val BUYER = "owner@studio.test"
    }
}
