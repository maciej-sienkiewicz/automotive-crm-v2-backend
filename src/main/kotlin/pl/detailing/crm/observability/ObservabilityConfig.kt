package pl.detailing.crm.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Observability configuration for the Detailing CRM backend.
 *
 * Responsibilities
 * ────────────────
 * • Attach common tags (application, environment) to every metric so that
 *   multi-instance Prometheus queries can be scoped with a single label selector.
 * • Install JVM / system binders for GC, memory, threads and CPU metrics.
 * • Register a MeterFilter that caps the maximum expected duration for the
 *   API request Timer so that Prometheus histogram buckets are well-distributed
 *   across the 0–10 s range.
 *
 * ApiMetricsAspect and HttpMetricsFilter are @Component beans – Spring picks
 * them up automatically; no explicit registration is needed here.
 */
@Configuration
class ObservabilityConfig {

    /**
     * MeterRegistryCustomizer runs once at startup, before any metrics are
     * recorded, making it the right place for global tag and filter registration.
     */
    @Bean
    fun crmMeterRegistryCustomizer(): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer { registry ->
            registry.config()
                // Cap histogram buckets for the request-duration timer.
                // Without this, Micrometer may generate buckets up to Long.MAX_VALUE.
                .meterFilter(
                    MeterFilter.maxExpected(MetricsTags.API_REQUEST_DURATION, Duration.ofSeconds(10))
                )
                // Ignore actuator's own internal requests from appearing in our dashboards
                .meterFilter(MeterFilter.deny { id ->
                    id.name.startsWith("spring.security") ||
                        id.name.startsWith("tomcat") ||
                        (id.name == MetricsTags.API_REQUEST_DURATION &&
                            id.getTag(MetricsTags.TAG_PATH)?.startsWith("/actuator") == true)
                })
        }

    // ── JVM and system metrics ───────────────────────────────────────────────

    @Bean
    fun jvmMemoryMetrics() = JvmMemoryMetrics()

    @Bean
    fun jvmGcMetrics() = JvmGcMetrics()

    @Bean
    fun jvmThreadMetrics() = JvmThreadMetrics()

    @Bean
    fun classLoaderMetrics() = ClassLoaderMetrics()

    @Bean
    fun processorMetrics() = ProcessorMetrics()

    @Bean
    fun uptimeMetrics() = UptimeMetrics()
}
