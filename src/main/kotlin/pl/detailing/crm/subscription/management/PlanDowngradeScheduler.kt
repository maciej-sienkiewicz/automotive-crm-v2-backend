package pl.detailing.crm.subscription.management

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.StudioAddOnRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import java.time.Instant
import java.util.UUID

/**
 * Stosuje zmiany zaplanowane na koniec okresu: downgrade'y planu i wyłączenia modułów.
 *
 * Dawna wersja miała `@Transactional` na całej pętli i `try/catch` na wiersz. Wyglądało to na
 * izolację, ale `EntitlementService.assignPlan` dołączał do transakcji zewnętrznej: wyjątek
 * przechodzący przez jego proxy oznaczał CAŁĄ transakcję jako rollback-only, `catch` go połykał,
 * a commit na końcu rzucał `UnexpectedRollbackException`. Jedno „zatrute" studio cofało
 * downgrade'y wszystkich, co godzinę od nowa (audyt, J1 — odtworzone na Postgresie).
 *
 * Teraz pętla NIE ma transakcji: rozdziela pracę, a każdy wiersz idzie we własnej
 * ([ScheduledPlanChangeApplier], `REQUIRES_NEW`). Błąd jednego to miernik
 * `subscription.scheduled.changes.failures` i ponowienie w kolejnym przebiegu.
 */
@Component
class PlanDowngradeScheduler(
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val studioAddOnRepository: StudioAddOnRepository,
    private val applier: ScheduledPlanChangeApplier,
    private val accessPolicy: SubscriptionAccessPolicy,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val failures = meterRegistry.counter("subscription.scheduled.changes.failures")

    /** Co 10 minut, przesunięte o 5 minut względem joba cyklu życia. */
    @Scheduled(cron = "0 5/10 * * * *")
    fun applyDueDowngrades() {
        val now = accessPolicy.now()

        val dueDowngrades = pendingPlanChangeRepository.findDueIds(now, PageRequest.of(0, BATCH_SIZE))
        var applied = 0
        for (pendingId in dueDowngrades) {
            try {
                if (applier.applyDowngrade(pendingId, now)) applied++
            } catch (e: Exception) {
                failures.increment()
                logger.error("Downgrade {} nie został zastosowany — ponowienie w kolejnym przebiegu", pendingId, e)
            }
        }

        val dueCancellations = studioAddOnRepository.findDueCancellations(now, PageRequest.of(0, BATCH_SIZE))
        var removed = 0
        for (due in dueCancellations) {
            try {
                if (applier.removeCancelledAddOn(due.studioId, due.addOnKey, now)) removed++
            } catch (e: Exception) {
                failures.increment()
                logger.error("Wyłączenie modułu {} studia {} nie powiodło się — ponowienie w kolejnym przebiegu", due.addOnKey, due.studioId, e)
            }
        }

        if (dueDowngrades.isNotEmpty() || dueCancellations.isNotEmpty()) {
            logger.info(
                "Zmiany na koniec okresu: downgrade'y {}/{}, wyłączenia modułów {}/{}",
                applied, dueDowngrades.size, removed, dueCancellations.size
            )
        }
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}

/**
 * Jedna zaplanowana zmiana jednego studia, we własnej transakcji. Kolejność jest zawsze ta
 * sama: blokada studia → świeży odczyt wiersza → decyzja → zmiana. Ta sama blokada stoi
 * przed odwołaniem downgrade'u przez właściciela, więc odpowiedź „anulowano" nie może
 * zostać nadpisana przez równoległy przebieg (audyt, S2).
 */
@Service
class ScheduledPlanChangeApplier(
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val studioRepository: StudioRepository,
    private val entitlementService: EntitlementService,
    private val addOnRepository: AddOnJpaRepository,
    private val paymentLogRepository: SubscriptionPaymentLogRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyDowngrade(pendingId: UUID, now: Instant): Boolean {
        // Studio bez ładowania wiersza zmiany: wiersz wczytany PRZED blokadą byłby stanem,
        // na którym decyzja „czy wciąż PENDING" nic nie znaczy.
        val studioId = pendingPlanChangeRepository.findStudioIdById(pendingId) ?: return false
        studioRepository.lockById(studioId) ?: return false
        val pending = pendingPlanChangeRepository.findById(pendingId).orElse(null) ?: return false
        if (pending.status != PendingPlanChangeStatus.PENDING || pending.effectiveAt.isAfter(now)) return false

        entitlementService.changePlan(StudioId(studioId), pending.toPlanKey)
        pending.status = PendingPlanChangeStatus.APPLIED
        pending.appliedAt = now

        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId,
                eventType = SubscriptionEventType.PLAN_DOWNGRADE,
                amountInCents = 0,
                planKey = pending.toPlanKey,
                description = "Downgrade z ${pending.fromPlanKey.displayName} do ${pending.toPlanKey.displayName} — zastosowany automatycznie"
            )
        )
        logger.info(
            "Applied downgrade studio={} from={} to={} (scheduled for {}, applied at {})",
            studioId, pending.fromPlanKey, pending.toPlanKey, pending.effectiveAt, now
        )
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun removeCancelledAddOn(studioId: UUID, addOnKey: AddOnKey, now: Instant): Boolean {
        val removed = entitlementService.removeAddOnIfCancellationDue(StudioId(studioId), addOnKey, now)
        if (!removed) return false

        val planKey = entitlementService.getEntitlements(StudioId(studioId)).planKey
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId,
                eventType = SubscriptionEventType.ADD_ON_DEACTIVATION,
                amountInCents = 0,
                planKey = planKey,
                addOnKey = addOnKey.name,
                description = "Wyłączenie modułu ${addOnRepository.findByKey(addOnKey)?.name ?: addOnKey.name} z końcem okresu — zastosowane automatycznie"
            )
        )
        return true
    }
}
