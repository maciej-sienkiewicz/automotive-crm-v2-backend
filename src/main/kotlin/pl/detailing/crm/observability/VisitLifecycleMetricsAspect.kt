package pl.detailing.crm.observability

import io.micrometer.core.instrument.MeterRegistry
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.AfterReturning
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.springframework.stereotype.Component
import pl.detailing.crm.visit.transitions.complete.CompleteVisitCommand
import pl.detailing.crm.visit.transitions.confirm.ConfirmVisitCommand

/**
 * AOP aspect that tracks visit lifecycle milestones at the command handler level.
 *
 * Targets two specific handlers to give a clear funnel view:
 *
 *   ConfirmVisitHandler.handle()  → work started (IN_PROGRESS transition)
 *   CompleteVisitHandler.handle() → vehicle picked up by customer (COMPLETED transition)
 *
 * Using @AfterReturning ensures that only *successful* state transitions are counted.
 * Both target methods are Kotlin suspend functions; Spring AOP handles the coroutine
 * continuation transparently – the advice fires after the coroutine result is resolved.
 *
 * Recorded metrics
 * ─────────────────
 * crm_visits_started_total    – Counter
 *   Tags: studio_id
 *
 * crm_visits_completed_total  – Counter
 *   Tags: studio_id
 *
 * PromQL – visit funnel per studio:
 *   sum by (studio_id) (increase(crm_visits_started_total[30d]))
 *   sum by (studio_id) (increase(crm_visits_completed_total[30d]))
 *
 * PromQL – completion rate (% of started visits that reach COMPLETED):
 *   sum(increase(crm_visits_completed_total[30d]))
 *   / sum(increase(crm_visits_started_total[30d]))
 */
@Aspect
@Component
class VisitLifecycleMetricsAspect(private val registry: MeterRegistry) {

    // ── Pointcuts ────────────────────────────────────────────────────────────

    @Pointcut("execution(* pl.detailing.crm.visit.transitions.confirm.ConfirmVisitHandler.handle(..))")
    fun visitConfirmed() {}

    @Pointcut("execution(* pl.detailing.crm.visit.transitions.complete.CompleteVisitHandler.handle(..))")
    fun visitCompleted() {}

    // ── Advice ───────────────────────────────────────────────────────────────

    @AfterReturning("visitConfirmed()")
    fun recordVisitStarted(joinPoint: JoinPoint) {
        val command = joinPoint.args.firstOrNull() as? ConfirmVisitCommand ?: return
        registry.counter(
            MetricsTags.VISITS_STARTED,
            MetricsTags.TAG_STUDIO_ID, command.studioId.value.toString()
        ).increment()
    }

    @AfterReturning("visitCompleted()")
    fun recordVisitCompleted(joinPoint: JoinPoint) {
        val command = joinPoint.args.firstOrNull() as? CompleteVisitCommand ?: return
        registry.counter(
            MetricsTags.VISITS_COMPLETED,
            MetricsTags.TAG_STUDIO_ID, command.studioId.value.toString()
        ).increment()
    }
}
