package pl.detailing.crm.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Registers platform-wide business Gauges sourced directly from the database.
 *
 * These metrics answer operational questions about overall platform growth:
 *
 *   crm_platform_studios_total        – how many detailing companies have registered
 *   crm_platform_appointments_total   – cumulative appointments (reservations) ever created
 *   crm_platform_visits_total         – cumulative visits ever created
 *
 * Each Gauge calls COUNT(*) on Prometheus scrape. The queries are simple index-only
 * scans (PK count) so the cost is negligible at the expected table sizes.
 *
 * PromQL examples:
 *   crm_platform_studios_total                    – current studio count
 *   increase(crm_platform_appointments_total[30d]) – new appointments in last 30 days
 *   increase(crm_platform_visits_total[30d])        – new visits in last 30 days
 */
@Component
class PlatformBusinessMetrics(
    private val registry: MeterRegistry,
    private val jdbcTemplate: JdbcTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun registerGauges() {
        Gauge.builder(MetricsTags.PLATFORM_STUDIOS_TOTAL) { countStudios() }
            .description("Total number of detailing companies (studios) registered on the platform")
            .register(registry)

        Gauge.builder(MetricsTags.PLATFORM_APPOINTMENTS_TOTAL) { countAppointments() }
            .description("Cumulative total number of appointments (reservations) ever created on the platform")
            .register(registry)

        Gauge.builder(MetricsTags.PLATFORM_VISITS_TOTAL) { countVisits() }
            .description("Cumulative total number of visits ever created on the platform")
            .register(registry)
    }

    private fun countStudios(): Double = runCatching {
        (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM studios", Long::class.java) ?: 0L).toDouble()
    }.getOrElse {
        log.warn("Failed to query studio count: ${it.message}")
        -1.0
    }

    private fun countAppointments(): Double = runCatching {
        (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM appointments", Long::class.java) ?: 0L).toDouble()
    }.getOrElse {
        log.warn("Failed to query appointment count: ${it.message}")
        -1.0
    }

    private fun countVisits(): Double = runCatching {
        (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM visits", Long::class.java) ?: 0L).toDouble()
    }.getOrElse {
        log.warn("Failed to query visit count: ${it.message}")
        -1.0
    }
}
