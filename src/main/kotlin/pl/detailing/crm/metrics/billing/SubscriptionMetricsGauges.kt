package pl.detailing.crm.metrics.billing

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import pl.detailing.crm.metrics.config.MetricsProperties

/**
 * Publishes the subscription snapshot to Prometheus.
 *
 * This is the "real-time" half of the subscriptions requirement. The daily snapshot table
 * answers *how the customer base changed over the last year*; Prometheus answers *what it
 * is right now* and is what an alert rule can fire on — "paying accounts dropped by five
 * in an hour" is a page, not a chart.
 *
 * `MultiGauge` rather than a gauge per plan: plans and add-ons are database rows, so the
 * label set is not known at compile time. A `MultiGauge` re-registers the whole series
 * family on each refresh, which correctly *removes* a series when a plan disappears —
 * individually registered gauges would keep reporting the last value of a plan nobody is
 * on any more, and a stale non-zero gauge is worse than no gauge at all.
 */
@Component
class SubscriptionMetricsGauges(
    private val registry: MeterRegistry,
    private val service: SubscriptionMetricsService,
    private val properties: MetricsProperties
) {

    private lateinit var byPlanGauge: MultiGauge
    private lateinit var byStatusGauge: MultiGauge
    private lateinit var addOnsGauge: MultiGauge

    companion object {
        const val SUBSCRIPTIONS_BY_PLAN = "crm.subscriptions.by_plan"
        const val SUBSCRIPTIONS_BY_STATUS = "crm.subscriptions.by_status"
        const val SUBSCRIPTIONS_ADD_ONS = "crm.subscriptions.add_ons.active"
        const val SUBSCRIPTIONS_PAYING = "crm.subscriptions.paying.total"
        const val SUBSCRIPTIONS_MRR = "crm.subscriptions.mrr.gross_cents"
        const val SUBSCRIPTIONS_ARPA = "crm.subscriptions.arpa.gross_cents"
        const val SUBSCRIPTIONS_NO_PLAN = "crm.subscriptions.without_plan.total"
    }

    @PostConstruct
    fun register() {
        if (!properties.enabled) return

        byPlanGauge = MultiGauge.builder(SUBSCRIPTIONS_BY_PLAN)
            .description("Liczba studiów w podziale na pakiet i status subskrypcji")
            .register(registry)

        byStatusGauge = MultiGauge.builder(SUBSCRIPTIONS_BY_STATUS)
            .description("Liczba studiów w podziale na status subskrypcji")
            .register(registry)

        addOnsGauge = MultiGauge.builder(SUBSCRIPTIONS_ADD_ONS)
            .description("Liczba aktywnych modułów dodatkowych w podziale na moduł")
            .register(registry)

        // Scalars can be plain gauges: their label set is fixed, so there is nothing to
        // remove and the lambda simply reads the cached snapshot on each scrape.
        registry.gauge(SUBSCRIPTIONS_PAYING, this) { it.service.snapshot().payingStudios.toDouble() }
        registry.gauge(SUBSCRIPTIONS_MRR, this) { it.service.snapshot().mrrGrossCents.toDouble() }
        registry.gauge(SUBSCRIPTIONS_ARPA, this) { it.service.snapshot().arpaGrossCents.toDouble() }
        registry.gauge(SUBSCRIPTIONS_NO_PLAN, this) { it.service.snapshot().studiosWithoutPlan.toDouble() }

        refresh()
    }

    /**
     * Refreshed on a schedule rather than on scrape: `MultiGauge.register` rebuilds meter
     * registrations, which is not something to do inside a scrape callback.
     * The underlying snapshot is cached, so this is one aggregate query per minute.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    fun refresh() {
        if (!properties.enabled) return

        val snapshot = service.snapshot()

        byPlanGauge.register(
            snapshot.byPlan.map { row ->
                MultiGauge.Row.of(
                    Tags.of("plan", row.planKey, "status", row.status),
                    row.count.toDouble()
                )
            },
            true
        )

        byStatusGauge.register(
            snapshot.byStatus.map { (status, count) ->
                MultiGauge.Row.of(Tags.of("status", status), count.toDouble())
            },
            true
        )

        addOnsGauge.register(
            snapshot.activeAddOnsByKey.map { (key, count) ->
                MultiGauge.Row.of(Tags.of("add_on", key), count.toDouble())
            },
            true
        )
    }
}
