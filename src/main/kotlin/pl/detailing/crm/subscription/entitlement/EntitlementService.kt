package pl.detailing.crm.subscription.entitlement

import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.domain.*
import pl.detailing.crm.subscription.entitlement.infrastructure.*
import pl.detailing.crm.subscription.lifecycle.BillingSnapshot
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Wynik próby wyłączenia modułu. */
enum class AddOnCancellationResult {
    /** Moduł działa do końca opłaconego okresu, potem zniknie. */
    SCHEDULED,
    /** Moduł usunięty od razu (trial, brak trwającego opłaconego okresu). */
    REMOVED,
    /** Moduł nie był aktywny. */
    NOT_ACTIVE
}

/**
 * Core entitlement service. Single source of truth for "what can a studio do?".
 *
 * Caching strategy:
 * - Resolved entitlements are cached in Redis under key "studio-entitlements::{studioId}"
 * - TTL is configured in CacheConfig (default 5 minutes)
 * - Każda mutacja unieważnia wpis teraz i PO zakończeniu transakcji ([EntitlementCacheInvalidator]).
 *
 * Każda mutacja zaczyna od blokady wiersza studia ([StudioRepository.lockById]) — ta sama
 * blokada, którą biorą realizacja zamówień, zmiany planu i job cyklu życia. Ponowne
 * zablokowanie wiersza we własnej transakcji jest w Postgresie bezkosztowe, więc metody
 * wołane z wnętrza już zablokowanej operacji nie czekają na siebie.
 */
@Service
class EntitlementService(
    private val studioSubscriptionPlanRepository: StudioSubscriptionPlanRepository,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository,
    private val studioRepository: StudioRepository,
    private val cacheInvalidator: EntitlementCacheInvalidator,
    private val meterRegistry: MeterRegistry,
    @PersistenceContext private val entityManager: EntityManager,
    /** Podmieniany w testach; produkcyjnie zegar systemowy (Spring bierze wartość domyślną). */
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached entitlements for the given studio: what the studio BOUGHT (plan +
     * modules) plus its billing snapshot. Whether it may USE them right now is decided by
     * [pl.detailing.crm.subscription.entitlement.capability.CapabilityService].
     *
     * INVARIANT (see [ensurePlanAssigned]): every studio has exactly one
     * `studio_subscription_plans` row from the moment it is created. A missing row
     * is therefore a DATA ERROR, not a normal state. We still degrade gracefully
     * (BASIC feature set, so the studio keeps working) but we log at ERROR level
     * and bump the `entitlements.missing.plan.row` counter — that metric must be
     * wired to an alert; it means provisioning or a migration has failed.
     */
    @Cacheable(value = [EntitlementCacheInvalidator.CACHE_NAME], key = "#studioId.toString()")
    @Transactional(readOnly = true)
    fun getEntitlements(studioId: StudioId): StudioEntitlements = load(studioId)

    /**
     * To samo co [getEntitlements], ale zawsze z bazy, z pominięciem cache'u. Dla decyzji
     * pieniężnych pod blokadą studia (realizacja zamówienia, cena odnowienia): wpis w cache'u
     * może pochodzić sprzed chwili, w której inna transakcja zatwierdziła zmianę, a jeszcze
     * nie zdążyła go unieważnić.
     */
    @Transactional(readOnly = true)
    fun readCurrent(studioId: StudioId): StudioEntitlements = load(studioId)

    private fun load(studioId: StudioId): StudioEntitlements {
        logger.debug("Loading entitlements from DB for studio={}", studioId)

        // Brak wiersza studia (usunięte konto) = brak dostępu, nie „nieznany stan".
        val billing = studioRepository.findByStudioId(studioId.value)?.billing()
            ?: BillingSnapshot(SubscriptionStatus.NO_PLAN, null, null, null)

        val subscription = studioSubscriptionPlanRepository.findByStudioIdWithAddOns(studioId.value)
            ?: return degradedEntitlements(studioId, billing)

        val planFeatures = subscription.plan.features.map { it.key }.toSet()
        val addOnFeatures = subscription.activeAddOns.flatMap { it.addOn.features.map { f -> f.key } }.toSet()
        val activeAddOnKeys = subscription.activeAddOns.map { it.addOn.key }.toSet()
        // associate → LinkedHashMap: konkretny typ, który Jackson odtworzy z cache'u.
        val cancellations = subscription.activeAddOns
            .filter { it.cancelAt != null }
            .associate { it.addOn.key to it.cancelAt!! }

        return StudioEntitlements(
            planKey = subscription.plan.key,
            planName = subscription.plan.name,
            enabledFeatures = planFeatures + addOnFeatures,
            activeAddOnKeys = activeAddOnKeys,
            billing = billing,
            addOnCancellations = cancellations
        )
    }

    fun hasFeature(studioId: StudioId, featureKey: FeatureKey): Boolean =
        getEntitlements(studioId).hasFeature(featureKey)

    /**
     * True when the studio satisfies the provisioning invariant (has its plan row).
     * Used by checkout to reject an add-on purchase BEFORE any money moves —
     * feasibility must be validated before payment, never after.
     */
    @Transactional(readOnly = true)
    fun hasPlanAssigned(studioId: StudioId): Boolean =
        studioSubscriptionPlanRepository.findByStudioId(studioId.value) != null

    @Transactional(readOnly = true)
    fun getAllPlans(): List<Plan> = planRepository.findAllByIsActiveTrue()
        .sortedBy { it.displayOrder }
        .map { it.toDomain() }

    @Transactional(readOnly = true)
    fun getAllAddOns(): List<AddOn> = addOnRepository.findAllByIsActiveTrue()
        .map { it.toDomain() }

    /**
     * Idempotently guarantees the provisioning invariant: "every studio has exactly
     * one subscription-plan row". Called from studio creation (signup), trial start,
     * demo and sandbox creation. Safe to call on every one of those paths — an existing
     * row is never overwritten, and two concurrent calls never collide (`ON CONFLICT`).
     */
    @Transactional
    fun ensurePlanAssigned(studioId: StudioId, defaultPlanKey: PlanKey = PlanKey.BASIC) {
        val plan = planRepository.findByKey(defaultPlanKey)
            ?: throw EntityNotFoundException("Plan nie został znaleziony: $defaultPlanKey")

        val inserted = studioSubscriptionPlanRepository.insertIfAbsent(
            id = UUID.randomUUID(), studioId = studioId.value, planId = plan.id, now = clock.instant()
        )
        if (inserted > 0) {
            cacheInvalidator.evict(studioId)
            logger.info("Provisioned default plan={} for studio={}", defaultPlanKey, studioId)
        }
    }

    // ── Plan / Add-on management ──────────────────────────────────────────────

    /**
     * Przepina studio na [planKey] NA MIEJSCU (ta sama encja, ta sama kolekcja modułów).
     * Moduły znikają — plan docelowy definiuje zestaw od nowa. Brak wiersza planu tworzy go.
     */
    @Transactional
    fun changePlan(studioId: StudioId, planKey: PlanKey) {
        studioRepository.lockById(studioId.value)
        val plan = planRepository.findByKey(planKey)
            ?: throw EntityNotFoundException("Plan nie został znaleziony: $planKey")

        val subscription = lockedSubscription(studioId)
        if (subscription == null) {
            ensurePlanAssigned(studioId, planKey)
        } else {
            subscription.changePlan(plan, clock.instant())
        }
        cacheInvalidator.evict(studioId)
        logger.info("Studio={} assigned to plan={}", studioId, planKey)
    }

    /**
     * Aktywuje moduł. Zwraca false, gdy moduł już był aktywny (także z zaplanowanym
     * wyłączeniem) — o tym, czy to błąd, decyduje wołający (realizacja zamówienia uznaje to
     * za płatność do zwrotu, nie za cichy sukces).
     */
    @Transactional
    fun activateAddOn(studioId: StudioId, addOnKey: AddOnKey): Boolean {
        studioRepository.lockById(studioId.value)
        val subscription = lockedSubscription(studioId)
            ?: throw EntityNotFoundException("Studio nie ma aktywnego planu subskrypcji: $studioId")

        if (subscription.activeAddOns.any { it.addOn.key == addOnKey }) return false

        val addOn = addOnRepository.findByKey(addOnKey)
            ?: throw EntityNotFoundException("Moduł nie został znaleziony: $addOnKey")

        subscription.activeAddOns.add(StudioAddOnEntity(studioSubscriptionPlan = subscription, addOn = addOn))
        cacheInvalidator.evict(studioId)
        logger.info("Studio={} activated add-on={}", studioId, addOnKey)
        return true
    }

    /**
     * Wyłącza moduł z końcem opłaconego okresu ([cancelAt]) albo — gdy [cancelAt] jest null
     * (trial, brak trwającego okresu) — od razu. Dawniej moduł opłacony do końca okresu
     * znikał w chwili kliknięcia (audyt, S6).
     */
    @Transactional
    fun cancelAddOn(studioId: StudioId, addOnKey: AddOnKey, cancelAt: Instant?): AddOnCancellationResult {
        studioRepository.lockById(studioId.value)
        val subscription = lockedSubscription(studioId) ?: return AddOnCancellationResult.NOT_ACTIVE
        val row = subscription.activeAddOns.firstOrNull { it.addOn.key == addOnKey }
            ?: return AddOnCancellationResult.NOT_ACTIVE

        val result = if (cancelAt == null || !cancelAt.isAfter(clock.instant())) {
            subscription.activeAddOns.remove(row)
            AddOnCancellationResult.REMOVED
        } else {
            row.cancelAt = cancelAt
            AddOnCancellationResult.SCHEDULED
        }
        cacheInvalidator.evict(studioId)
        logger.info("Studio={} add-on={} cancellation {} (cancelAt={})", studioId, addOnKey, result, cancelAt)
        return result
    }

    /** Cofa zaplanowane wyłączenie modułu. False, gdy modułu nie ma. */
    @Transactional
    fun resumeAddOn(studioId: StudioId, addOnKey: AddOnKey): Boolean {
        studioRepository.lockById(studioId.value)
        val subscription = lockedSubscription(studioId) ?: return false
        val row = subscription.activeAddOns.firstOrNull { it.addOn.key == addOnKey } ?: return false
        row.cancelAt = null
        cacheInvalidator.evict(studioId)
        logger.info("Studio={} resumed add-on={}", studioId, addOnKey)
        return true
    }

    /**
     * Usuwa moduł, którego zaplanowane wyłączenie już nadeszło. Decyzja zapada pod blokadą:
     * jeśli w międzyczasie właściciel cofnął wyłączenie, nic się nie dzieje (false).
     */
    @Transactional
    fun removeAddOnIfCancellationDue(studioId: StudioId, addOnKey: AddOnKey, now: Instant): Boolean {
        studioRepository.lockById(studioId.value)
        val subscription = lockedSubscription(studioId) ?: return false
        val row = subscription.activeAddOns.firstOrNull { it.addOn.key == addOnKey } ?: return false
        val cancelAt = row.cancelAt ?: return false
        if (cancelAt.isAfter(now)) return false
        subscription.activeAddOns.remove(row)
        cacheInvalidator.evict(studioId)
        logger.info("Studio={} add-on={} removed at scheduled end of period {}", studioId, addOnKey, cancelAt)
        return true
    }

    /** Wymusza unieważnienie cache'u po zmianach, które nie przechodzą przez metody tego serwisu. */
    fun evictEntitlementsCache(studioId: StudioId) {
        cacheInvalidator.evict(studioId)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Wiersz planu ze świeżym stanem i blokadą.
     *
     * `flush` przed `refresh`: w tej samej transakcji mogła już zajść zmiana planu
     * (zakup po wygaśnięciu: [changePlan], potem [activateAddOn]) — refresh bez flushu
     * zgubiłby ją. `refresh` z blokadą wczytuje wiersz I kolekcję modułów od nowa: zapytanie
     * z `JOIN FETCH` NIE odświeża kolekcji zainicjalizowanej wcześniej w tej transakcji,
     * więc „czy moduł już jest?" sprawdzane było na stanie sprzed cudzego commitu, a o wyniku
     * rozstrzygał dopiero unikat `uq_studio_add_ons` (audyt, D1, D5).
     */
    private fun lockedSubscription(studioId: StudioId): StudioSubscriptionPlanEntity? {
        val subscription = studioSubscriptionPlanRepository.findByStudioId(studioId.value) ?: return null
        entityManager.flush()
        entityManager.refresh(subscription, LockModeType.PESSIMISTIC_WRITE)
        return subscription
    }

    /**
     * Loud degraded mode for a studio violating the provisioning invariant.
     *
     * Grants the BASIC feature set so the studio's core operations keep working,
     * but NEVER silently: before this change the fallback masked missing rows for
     * weeks — the studio looked healthy in every read path and add-on purchases
     * exploded only AFTER the payment was taken ("Studio nie ma aktywnego planu
     * subskrypcji"). The ERROR log + counter make the drift visible immediately.
     */
    private fun degradedEntitlements(studioId: StudioId, billing: BillingSnapshot): StudioEntitlements {
        logger.error(
            "INVARIANT VIOLATION: studio={} has no studio_subscription_plans row — " +
            "serving degraded BASIC entitlements. Provisioning or backfill has failed; " +
            "run StudioSubscriptionBackfill or investigate signup flow.",
            studioId
        )
        meterRegistry.counter("entitlements.missing.plan.row").increment()

        return StudioEntitlements(
            planKey = PlanKey.BASIC,
            planName = PlanKey.BASIC.displayName,
            enabledFeatures = linkedSetOf(
                FeatureKey.CALENDAR,
                FeatureKey.VISITS,
                FeatureKey.CUSTOMERS,
                FeatureKey.VEHICLES,
                FeatureKey.DOCUMENTS,
                FeatureKey.GALLERY
            ),
            activeAddOnKeys = linkedSetOf(),
            billing = billing,
            addOnCancellations = linkedMapOf()
        )
    }
}
