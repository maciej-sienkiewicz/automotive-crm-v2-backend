package pl.detailing.crm.subscription.lifecycle

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
import java.time.Instant
import java.util.UUID

/**
 * Przesuwa statusy rozliczeniowe wraz z upływem czasu:
 * TRIALING → EXPIRED, ACTIVE → PAST_DUE (karencja) → EXPIRED.
 *
 * Zastępuje `SubscriptionLifecycleScheduler`, który miał trzy wady naraz (audyt, J2, J3):
 *  - `forEach` bez `try/catch` — wyjątek z jednego studia przerywał pętlę, a wygaszanie
 *    płatnych subskrypcji w tym przebiegu w ogóle nie ruszało,
 *  - `@Transactional` na funkcji `suspend` z `withContext(Dispatchers.IO)` — żadnej transakcji,
 *  - brak blokady między instancjami i brak miernika niepowodzeń.
 *
 * Teraz: pętla tylko rozdziela pracę (same ID, porcjami, deterministyczna kolejność), każde
 * studio idzie we własnej transakcji w [SubscriptionTransitionProcessor], a błąd jednego to
 * miernik i wpis w logu — pozostałe przechodzą. Decyzja zapada pod blokadą wiersza
 * (`SKIP LOCKED`), więc dwie instancje joba nie wykonają przejścia dwa razy.
 *
 * Wszystkie `@Scheduled` w tej aplikacji biegną na jednym wątku — przebieg nie robi żadnych
 * wywołań zewnętrznych, a porcja jest ograniczona, żeby nie wstrzymywać innych jobów.
 */
@Component
class SubscriptionLifecycleJob(
    private val studioRepository: StudioRepository,
    private val processor: SubscriptionTransitionProcessor,
    private val accessPolicy: SubscriptionAccessPolicy,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val failures = meterRegistry.counter("subscription.lifecycle.failures")
    private val transitions = meterRegistry.counter("subscription.lifecycle.transitions")

    @Scheduled(cron = "0 */10 * * * *")
    fun advanceDueSubscriptions() {
        val now = accessPolicy.now()
        val due = studioRepository.findIdsDueForLifecycleTransition(now, now.minus(accessPolicy.gracePeriod), PageRequest.of(0, BATCH_SIZE))
        if (due.isEmpty()) return

        var advanced = 0
        var failed = 0
        for (studioId in due) {
            try {
                if (processor.advance(studioId, now)) advanced++
            } catch (e: Exception) {
                failed++
                failures.increment()
                logger.error("Przejście stanu subskrypcji studia {} nie powiodło się — ponowienie w kolejnym przebiegu", studioId, e)
            }
        }
        transitions.increment(advanced.toDouble())
        logger.info("Cykl życia subskrypcji: należnych={} przestawionych={} błędów={}", due.size, advanced, failed)
        if (due.size == BATCH_SIZE) logger.info("Cykl życia subskrypcji: pełna porcja — reszta w kolejnym przebiegu")
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}

/**
 * Jedno przejście stanu jednego studia — osobny bean, bo `@Transactional(REQUIRES_NEW)`
 * działa tylko przez proxy. Zwykła metoda, NIE `suspend`.
 */
@Service
class SubscriptionTransitionProcessor(
    private val studioRepository: StudioRepository,
    private val accessPolicy: SubscriptionAccessPolicy,
    private val entitlementService: EntitlementService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** True, gdy studio zmieniło stan; false, gdy nic nie było należne albo wiersz był zajęty. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun advance(studioId: UUID, now: Instant): Boolean {
        // SKIP LOCKED: zakup w toku albo druga instancja joba — wrócimy w kolejnym przebiegu.
        val studio = studioRepository.tryLockById(studioId) ?: return false
        // Ponowna decyzja na stanie spod blokady: między zapytaniem o należne a blokadą
        // studio mogło zostać opłacone.
        val transition = accessPolicy.dueTransition(studio.billing(), now) ?: return false

        val from = studio.subscriptionStatus
        studio.subscriptionStatus = transition.to
        studio.graceEndsAt = transition.graceEndsAt
        entitlementService.evictEntitlementsCache(StudioId(studioId))

        logger.info("Studio={} {} → {} (karencja do {})", studioId, from, transition.to, transition.graceEndsAt)
        return true
    }
}
