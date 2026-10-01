package pl.detailing.crm.subscription

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.payments.checkout.CheckoutRequest
import pl.detailing.crm.payments.checkout.CheckoutService
import pl.detailing.crm.payments.checkout.OrderFulfillmentService
import pl.detailing.crm.payments.order.PaymentOrderEntity
import pl.detailing.crm.payments.order.PaymentOrderRepository
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.payments.p24.Przelewy24Config
import pl.detailing.crm.payments.p24.Przelewy24Properties
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.smscampaigns.CommunicationOnboardingService
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnEntity
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanEntity
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.StudioSubscriptionPlanRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.management.PendingPlanChangeEntity
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.management.PlanDowngradeScheduler
import pl.detailing.crm.subscription.management.PlanManagementService
import pl.detailing.crm.subscription.management.SubscriptionLifecycleScheduler
import pl.detailing.crm.subscription.pricing.ProrationService
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Znane defekty modułu subskrypcji — każdy test opisuje POPRAWNE zachowanie i dziś jest
 * czerwony z dokładnie tego powodu, który opisuje raport `docs/SUBSCRIPTION_AUDIT_2026-10.md`
 * (identyfikator w nazwie testu i w `@Disabled`). PR naprawiający defekt zdejmuje `@Disabled`
 * z odpowiadającego mu testu — zielony test jest dowodem naprawy, nie deklaracja w opisie PR.
 *
 * ── Dlaczego to nie jest zwykły @DataJpaTest ─────────────────────────────────
 *
 * Domyślny `@DataJpaTest` owija każdy test w jedną transakcję wycofywaną na końcu. W takim
 * teście NIE DA SIĘ zobaczyć żadnego z tych błędów: flush przy commicie, oznaczenie
 * transakcji jako rollback-only, utracone aktualizacje i wyścigi dwóch transakcji wymagają
 * prawdziwych, osobnych commitów. Stąd `Propagation.NOT_SUPPORTED` na klasie
 * i `TransactionTemplate` tam, gdzie test sam wyznacza granice transakcji.
 *
 * ── Jak zasymulowana jest „awaria jednego tenanta" ───────────────────────────
 *
 * Wyzwalaczem w Postgresie, który rzuca wyjątek przy zapisie wierszy jednego studia.
 * To model dowolnego błędu bazy dla jednego wiersza (timeout blokady, constraint,
 * zerwane połączenie) — liczy się mechanizm propagacji, nie przyczyna.
 *
 * ── Uruchamianie ─────────────────────────────────────────────────────────────
 *
 * `@Tag("testcontainers")` — poza domyślnym `./gradlew test`, jak reszta testów na
 * prawdziwym Postgresie: `./gradlew test -PksefStub -PrunTestcontainers`. Bez Dockera
 * można wskazać istniejącą bazę (zostanie WYCZYSZCZONA — create-drop):
 * `SUBSCRIPTION_IT_JDBC_URL=jdbc:postgresql://localhost:5432/subaudit
 *  SUBSCRIPTION_IT_DB_USER=postgres SUBSCRIPTION_IT_DB_PASSWORD=postgres`.
 *
 * Bramki (`gated…`) wstrzymują jedną transakcję w ściśle określonym miejscu, żeby wyścig
 * był deterministyczny. Przy naprawie, która zmieni wywoływane metody repozytoriów,
 * bramkę przesuwa się razem z kodem — scenariusz (kolejność zdarzeń) zostaje ten sam.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop", "p24.mock-mode=true"])
@Import(
    SubscriptionKnownDefectsTest.Beans::class,
    EntitlementService::class, ProrationService::class, PlanManagementService::class,
    PlanDowngradeScheduler::class, StudioProvisioningService::class, SubscriptionService::class,
    SubscriptionLifecycleScheduler::class, OrderFulfillmentService::class, CheckoutService::class,
    Przelewy24Config::class, Przelewy24Client::class
)
class SubscriptionKnownDefectsTest {

    companion object {
        private val externalUrl: String? = System.getenv("SUBSCRIPTION_IT_JDBC_URL")
        private val postgres by lazy { PostgreSQLContainer("postgres:16-alpine").also { it.start() } }

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            if (externalUrl != null) {
                registry.add("spring.datasource.url") { externalUrl }
                registry.add("spring.datasource.username") { System.getenv("SUBSCRIPTION_IT_DB_USER") ?: "postgres" }
                registry.add("spring.datasource.password") { System.getenv("SUBSCRIPTION_IT_DB_PASSWORD") ?: "postgres" }
            } else {
                registry.add("spring.datasource.url") { postgres.jdbcUrl }
                registry.add("spring.datasource.username") { postgres.username }
                registry.add("spring.datasource.password") { postgres.password }
            }
        }

        const val BASIC_PRICE = 9900L
        const val FULL_PRICE = 29900L
    }

    @TestConfiguration
    class Beans {
        @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
        @Bean fun protocolProvisioner(): DefaultProtocolTemplateProvisioner = mockk(relaxed = true)
        @Bean fun consentProvisioner(): DefaultMarketingConsentProvisioner = mockk(relaxed = true)
        @Bean fun communicationOnboarding(): CommunicationOnboardingService = mockk(relaxed = true)
        @Bean fun rolePreviewGuard(): RolePreviewOutboundGuard = mockk(relaxed = true)
    }

    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var dataSource: javax.sql.DataSource
    @Autowired lateinit var studioRepository: StudioRepository
    @Autowired lateinit var planRepository: PlanJpaRepository
    @Autowired lateinit var addOnRepository: AddOnJpaRepository
    @Autowired lateinit var subscriptionPlanRepository: StudioSubscriptionPlanRepository
    @Autowired lateinit var pendingRepository: PendingPlanChangeRepository
    @Autowired lateinit var orderRepository: PaymentOrderRepository
    @Autowired lateinit var paymentLogRepository: SubscriptionPaymentLogRepository
    @Autowired lateinit var entitlementService: EntitlementService
    @Autowired lateinit var planManagementService: PlanManagementService
    @Autowired lateinit var downgradeScheduler: PlanDowngradeScheduler
    @Autowired lateinit var lifecycleScheduler: SubscriptionLifecycleScheduler
    @Autowired lateinit var checkoutService: CheckoutService
    @Autowired lateinit var prorationService: ProrationService
    @Autowired lateinit var meterRegistry: MeterRegistry

    private val jdbc by lazy { JdbcTemplate(dataSource) }
    private val tx by lazy { TransactionTemplate(transactionManager) }

    @BeforeEach
    fun seedCatalog() {
        cleanup()
        planRepository.save(PlanEntity(key = PlanKey.BASIC, name = "Podstawowy", monthlyPriceGrossCents = BASIC_PRICE))
        planRepository.save(PlanEntity(key = PlanKey.FULL, name = "Pełny", monthlyPriceGrossCents = FULL_PRICE))
        addOnRepository.save(AddOnEntity(key = AddOnKey.FINANCE_MODULE, name = "Finanse", monthlyPriceGrossCents = 4900))
    }

    @AfterEach
    fun cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS poison_plan ON studio_subscription_plans")
        jdbc.execute("DROP TRIGGER IF EXISTS poison_studio ON studios")
        jdbc.execute(
            """TRUNCATE studio_subscription_add_ons, studio_subscription_plans, pending_plan_changes,
               payment_orders, subscription_payment_log, studios, subscription_add_ons, subscription_plans CASCADE"""
        )
    }

    // ── J1 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("J1: PlanDowngradeScheduler — try/catch w jednej transakcji; zdjąć po naprawie")
    fun `J1 awaria jednego studia nie cofa downgrade'ow pozostalych`() {
        val past = Instant.now().minus(1, ChronoUnit.HOURS)
        val healthy = studio(SubscriptionStatus.ACTIVE, endsAt = past)
        val poisoned = studio(SubscriptionStatus.ACTIVE, endsAt = past)
        listOf(healthy, poisoned).forEach { entitlementService.assignPlan(StudioId(it), PlanKey.FULL) }
        pendingRepository.save(PendingPlanChangeEntity(studioId = healthy, fromPlanKey = PlanKey.FULL, toPlanKey = PlanKey.BASIC, effectiveAt = past.minusSeconds(60)))
        pendingRepository.save(PendingPlanChangeEntity(studioId = poisoned, fromPlanKey = PlanKey.FULL, toPlanKey = PlanKey.BASIC, effectiveAt = past))
        failWritesOfPlanRow(poisoned)

        // Dziś: UnexpectedRollbackException — i zdrowe studio zostaje na FULL, co godzinę od nowa.
        downgradeScheduler.applyDueDowngrades()

        assertEquals(PlanKey.BASIC, planOf(healthy))
        assertEquals(listOf("APPLIED"), pendingStatuses(healthy))
        assertEquals(listOf("PENDING"), pendingStatuses(poisoned), "nieudany wiersz czeka na ponowienie")
    }

    // ── J2 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("J2: SubscriptionLifecycleScheduler — brak izolacji per tenant; zdjąć po naprawie")
    fun `J2 awaria jednego studia nie zatrzymuje wygaszania pozostalych`() {
        val past = Instant.now().minus(1, ChronoUnit.DAYS)
        val trials = (1..5).map { studio(SubscriptionStatus.TRIALING, trialEndsAt = past) }
        val poisoned = trials[2]
        val lapsedActive = studio(SubscriptionStatus.ACTIVE, endsAt = past)
        failExpiryOf(poisoned)

        // Dziś: wyjątek z trzeciego studia przerywa pętlę, a expireSubscriptions() nie rusza wcale.
        runCatching { lifecycleScheduler.expireLapsedSubscriptions() }

        (trials - poisoned).forEach { assertEquals("EXPIRED", statusOf(it), "studio $it") }
        assertEquals("EXPIRED", statusOf(lapsedActive))
        assertEquals("TRIALING", statusOf(poisoned))
    }

    // ── P1 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("P1: równoległy duplikat notyfikacji P24 realizuje zamówienie dwa razy; zdjąć po naprawie")
    fun `P1 rownolegly duplikat notyfikacji przedluza abonament dokladnie raz`() {
        val endsAt = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val studioId = studio(SubscriptionStatus.ACTIVE, endsAt = endsAt)
        entitlementService.assignPlan(StudioId(studioId), PlanKey.BASIC)
        val order = orderRepository.save(
            PaymentOrderEntity(studioId = studioId, sessionId = "CRM-${UUID.randomUUID()}", type = PaymentOrderType.RENEWAL,
                planKey = PlanKey.BASIC, amountCents = BASIC_PRICE, description = "Przedłużenie")
        )

        // B przeczytał zamówienie jako PENDING i stoi przed odczytem studia; w tym czasie A
        // przechodzi całą realizację i commituje. Dokładnie tak wygląda ponowienie P24, które
        // przyszło, zanim pierwsza obsługa (z weryfikacją po HTTP) zdążyła się zakończyć.
        val bReadOrder = CountDownLatch(1)
        val aCommitted = CountDownLatch(1)
        val gated = ThreadLocal.withInitial { false }
        val gatedStudios = object : StudioRepository by studioRepository {
            override fun findByStudioId(id: UUID): StudioEntity? {
                if (gated.get()) { bReadOrder.countDown(); aCommitted.await(5, TimeUnit.SECONDS) }
                return studioRepository.findByStudioId(id)
            }
        }
        val checkout = checkoutWith(studios = gatedStudios)

        val b = thread { gated.set(true); runCatching { tx.executeWithoutResult { checkout.completeOrder(order.sessionId, 777L) } } }
        bReadOrder.await(5, TimeUnit.SECONDS)
        runCatching { tx.executeWithoutResult { checkout.completeOrder(order.sessionId, 777L) } }
        aCommitted.countDown()
        b.join()

        // Dziś: +60 dni i dwa wpisy SUBSCRIPTION_RENEWAL za jedną płatność.
        assertEquals(30, Duration.between(endsAt, endsAtOf(studioId)).toDays())
        assertEquals(1L, jdbc.queryForObject(
            "SELECT count(*) FROM subscription_payment_log WHERE studio_id = ? AND event_type = 'SUBSCRIPTION_RENEWAL'",
            Long::class.java, studioId))
    }

    // ── S2 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("S2: anulowanie downgrade'u przegrywa z PlanDowngradeScheduler (lost update); zdjąć po naprawie")
    fun `S2 potwierdzone anulowanie downgrade'u jest wiazace`() {
        val studioId = studio(SubscriptionStatus.ACTIVE, endsAt = Instant.now().plus(1, ChronoUnit.DAYS))
        entitlementService.assignPlan(StudioId(studioId), PlanKey.FULL)
        pendingRepository.save(PendingPlanChangeEntity(studioId = studioId, fromPlanKey = PlanKey.FULL, toPlanKey = PlanKey.BASIC,
            effectiveAt = Instant.now().minus(1, ChronoUnit.MINUTES)))

        // Scheduler wczytał należne zmiany; zanim je zastosuje, właściciel klika „Anuluj".
        val loaded = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val gatedPending = object : PendingPlanChangeRepository by pendingRepository {
            override fun findDueChanges(now: Instant): List<PendingPlanChangeEntity> =
                pendingRepository.findDueChanges(now).also { loaded.countDown(); cancelled.await(5, TimeUnit.SECONDS) }
        }
        val scheduler = PlanDowngradeScheduler(gatedPending, entitlementService, paymentLogRepository)
        val run = thread { runCatching { tx.executeWithoutResult { scheduler.applyDueDowngrades() } } }
        loaded.await(5, TimeUnit.SECONDS)
        val userWasToldCancelled = runCatching { planManagementService.cancelPendingDowngrade(StudioId(studioId)) }.getOrDefault(false)
        cancelled.countDown()
        run.join()

        // Dziś: API odpowiada „anulowano" (204), a studio i tak ląduje na BASIC.
        if (userWasToldCancelled) {
            assertEquals(PlanKey.FULL, planOf(studioId))
            assertEquals(listOf("CANCELLED"), pendingStatuses(studioId))
        } else {
            assertEquals(PlanKey.BASIC, planOf(studioId))
        }
    }

    // ── S1 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("S1: odnowienie po zaplanowanym downgradzie liczone po cenie FULL; zdjąć po naprawie")
    fun `S1 odnowienie po zaplanowanym downgradzie kosztuje tyle co plan nastepnego okresu`() {
        val studioId = studio(SubscriptionStatus.ACTIVE, endsAt = Instant.now().plus(5, ChronoUnit.DAYS))
        entitlementService.assignPlan(StudioId(studioId), PlanKey.FULL)
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)

        val renewal = checkoutService.checkout(StudioId(studioId), "owner@studio.pl", CheckoutRequest(type = PaymentOrderType.RENEWAL))
        // Czas dochodzi do końca okresu, w którym downgrade został zamówiony.
        jdbc.update("UPDATE pending_plan_changes SET effective_at = now() - interval '1 minute' WHERE studio_id = ?", studioId)
        downgradeScheduler.applyDueDowngrades()

        // Dziś: pobrano 299 zł (FULL), a w opłaconym okresie studio ma BASIC.
        val planInPaidPeriod = planOf(studioId)
        val priceOfThatPlan = if (planInPaidPeriod == PlanKey.FULL) FULL_PRICE else BASIC_PRICE
        assertEquals(priceOfThatPlan, renewal.amountCents, "zapłacono za inny plan niż dostarczony (plan=$planInPaidPeriod)")
    }

    // ── S4 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("S4: PLAN_UPGRADE dla studia po terminie kosztuje 0 zł; zdjąć po naprawie")
    fun `S4 upgrade studia z wygasla subskrypcja nie jest darmowy`() {
        val studioId = studio(SubscriptionStatus.EXPIRED, endsAt = Instant.now().minus(3, ChronoUnit.DAYS))
        entitlementService.assignPlan(StudioId(studioId), PlanKey.BASIC)

        // Dziś: ProrationService zwraca null dla okresu z przeszłości → kwota 0 → FULL od ręki.
        val result = runCatching {
            checkoutService.checkout(StudioId(studioId), "owner@studio.pl", CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))
        }

        val gotFullForFree = result.getOrNull()?.amountCents == 0L && planOf(studioId) == PlanKey.FULL
        assertTrue(!gotFullForFree, "studio EXPIRED dostało FULL za 0 zł")
    }

    // ── P7 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("P7: dwa otwarte zamówienia na ten sam moduł = dwie płatności; zdjąć po naprawie")
    fun `P7 drugie zamowienie na ten sam modul jest odrzucane albo wskazuje pierwsze`() {
        val studioId = studio(SubscriptionStatus.ACTIVE, endsAt = Instant.now().plus(20, ChronoUnit.DAYS))
        entitlementService.assignPlan(StudioId(studioId), PlanKey.BASIC)
        val p24 = mockk<Przelewy24Client>()
        every { p24.registerTransaction(any()) } answers { "token-${UUID.randomUUID()}" }
        val checkout = checkoutWith(p24 = p24, properties = Przelewy24Properties(merchantId = 1, posId = 1, crc = "c", apiKey = "k"))
        val request = CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE))

        // Dwie karty przeglądarki / podwójne kliknięcie „Kup moduł".
        val first = tx.execute { checkout.checkout(StudioId(studioId), "owner@studio.pl", request) }!!
        val second = runCatching { tx.execute { checkout.checkout(StudioId(studioId), "owner@studio.pl", request) }!! }

        // Dziś: dwa niezależne zamówienia PENDING, oba do opłacenia; drugie opłacone przepada bez efektu.
        assertTrue(second.isFailure || second.getOrNull()?.orderId == first.orderId)
    }

    // ── D1 ───────────────────────────────────────────────────────────────────

    @Test
    @Disabled("D1: activateAddOn nie jest idempotentny przy nieświeżym kontekście persystencji; zdjąć po naprawie")
    fun `D1 dwie rownolegle aktywacje tego samego modulu nie koncza sie DataIntegrityViolation`() {
        val studioId = studio(SubscriptionStatus.ACTIVE, endsAt = Instant.now().plus(20, ChronoUnit.DAYS))
        entitlementService.assignPlan(StudioId(studioId), PlanKey.BASIC)

        // B wczytał plan (jak ensurePlanAssigned na początku realizacji), A w tym czasie aktywuje moduł.
        val bLoaded = CountDownLatch(1)
        val aCommitted = CountDownLatch(1)
        var bError: Throwable? = null
        val b = thread {
            runCatching {
                tx.executeWithoutResult {
                    subscriptionPlanRepository.findByStudioId(studioId)
                    bLoaded.countDown(); aCommitted.await(5, TimeUnit.SECONDS)
                    entitlementService.activateAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
                }
            }.onFailure { bError = it }
        }
        bLoaded.await(5, TimeUnit.SECONDS)
        entitlementService.activateAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
        aCommitted.countDown()
        b.join()

        // Dziś: duplicate key value violates unique constraint "uq_studio_add_ons".
        assertNull(bError, "druga aktywacja: ${bError?.message}")
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_add_ons", Long::class.java))
    }

    @Test
    @Disabled("D1: ensurePlanAssigned to check-then-act; zdjąć po naprawie")
    fun `D1 rownolegle pierwsze przypisanie planu jest idempotentne`() {
        val studioId = studio(SubscriptionStatus.NO_PLAN)
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..4).map {
            thread { start.await(); runCatching { entitlementService.ensurePlanAssigned(StudioId(studioId)) }.onFailure { errors += it } }
        }
        start.countDown()
        threads.forEach { it.join() }

        // Dziś (zależnie od przeplotu): DataIntegrityViolation na uq_studio_subscription_plans_studio.
        assertTrue(errors.isEmpty(), "błędy: ${errors.map { it.javaClass.simpleName }}")
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_plans WHERE studio_id = ?", Long::class.java, studioId))
    }

    // ── Strażnik refaktoru (zielony dziś i ma zostać zielony) ────────────────

    @Test
    fun `GUARD zmiana planu i ponowny zakup modulu po wygasnieciu dzialaja w jednej transakcji`() {
        val studioId = studio(SubscriptionStatus.EXPIRED, endsAt = Instant.now().minus(1, ChronoUnit.DAYS))
        entitlementService.assignPlan(StudioId(studioId), PlanKey.BASIC)
        entitlementService.activateAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        // Zakup po wygaśnięciu: assignPlan czyści moduły (orphanRemoval), activateAddOn wstawia ten sam —
        // w jednym flushu. Kolejność akcji Hibernate'a (DELETE sierot przed INSERT) chroni unikat.
        val rebuy = orderRepository.save(PaymentOrderEntity(studioId = studioId, sessionId = "CRM-${UUID.randomUUID()}",
            type = PaymentOrderType.INITIAL_PURCHASE, planKey = PlanKey.BASIC, addOnKeysRaw = "FINANCE_MODULE",
            amountCents = BASIC_PRICE + 4900, description = "Pakiet"))
        tx.executeWithoutResult { checkoutService.completeOrder(rebuy.sessionId, 10L) }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_add_ons", Long::class.java))

        val upgrade = orderRepository.save(PaymentOrderEntity(studioId = studioId, sessionId = "CRM-${UUID.randomUUID()}",
            type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL, amountCents = 10000, description = "Upgrade"))
        tx.executeWithoutResult { checkoutService.completeOrder(upgrade.sessionId, 11L) }
        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_add_ons", Long::class.java))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun studio(status: SubscriptionStatus, trialEndsAt: Instant? = null, endsAt: Instant? = null): UUID {
        val id = UUID.randomUUID()
        studioRepository.save(
            StudioEntity(id = id, name = "Studio $id", subscriptionStatus = status, trialEndsAt = trialEndsAt,
                subscriptionEndsAt = endsAt, trialUsed = trialEndsAt != null, emailAlias = id.toString().replace("-", ""))
        )
        return id
    }

    private fun checkoutWith(
        studios: StudioRepository = studioRepository,
        p24: Przelewy24Client = Przelewy24Client(Przelewy24Properties()),
        properties: Przelewy24Properties = Przelewy24Properties(mockMode = true)
    ): CheckoutService {
        val fulfillment = OrderFulfillmentService(studios, entitlementService, paymentLogRepository, pendingRepository,
            mockk(relaxed = true), meterRegistry)
        return CheckoutService(properties, p24, orderRepository, fulfillment, studioRepository, entitlementService,
            prorationService, planRepository, addOnRepository, mockk(relaxed = true))
    }

    private fun planOf(studioId: UUID): PlanKey? = jdbc.queryForList(
        """SELECT p.plan_key FROM studio_subscription_plans s JOIN subscription_plans p ON p.id = s.plan_id
           WHERE s.studio_id = ?""", String::class.java, studioId
    ).firstOrNull()?.let(PlanKey::valueOf)

    private fun pendingStatuses(studioId: UUID): List<String> = jdbc.queryForList(
        "SELECT status FROM pending_plan_changes WHERE studio_id = ? ORDER BY requested_at", String::class.java, studioId)

    private fun statusOf(studioId: UUID): String? =
        jdbc.queryForObject("SELECT subscription_status FROM studios WHERE id = ?", String::class.java, studioId)

    private fun endsAtOf(studioId: UUID): Instant =
        jdbc.queryForObject("SELECT subscription_ends_at FROM studios WHERE id = ?", Timestamp::class.java, studioId)!!.toInstant()

    private fun failWritesOfPlanRow(studioId: UUID) {
        jdbc.execute(
            """CREATE OR REPLACE FUNCTION poison_plan_fn() RETURNS trigger AS $$
               BEGIN
                 IF NEW.studio_id = '$studioId' THEN RAISE EXCEPTION 'awaria studia %', NEW.studio_id; END IF;
                 RETURN NEW;
               END $$ LANGUAGE plpgsql"""
        )
        jdbc.execute("CREATE TRIGGER poison_plan BEFORE INSERT OR UPDATE ON studio_subscription_plans FOR EACH ROW EXECUTE FUNCTION poison_plan_fn()")
    }

    private fun failExpiryOf(studioId: UUID) {
        jdbc.execute(
            """CREATE OR REPLACE FUNCTION poison_studio_fn() RETURNS trigger AS $$
               BEGIN
                 IF NEW.id = '$studioId' AND NEW.subscription_status = 'EXPIRED' THEN RAISE EXCEPTION 'awaria studia %', NEW.id; END IF;
                 RETURN NEW;
               END $$ LANGUAGE plpgsql"""
        )
        jdbc.execute("CREATE TRIGGER poison_studio BEFORE UPDATE ON studios FOR EACH ROW EXECUTE FUNCTION poison_studio_fn()")
    }
}
