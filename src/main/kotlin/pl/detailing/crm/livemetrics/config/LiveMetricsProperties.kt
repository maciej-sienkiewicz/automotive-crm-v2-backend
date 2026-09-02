package pl.detailing.crm.livemetrics.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

@ConfigurationProperties(prefix = "crm.live-metrics")
data class LiveMetricsProperties(
    val enabled: Boolean = true,
    /** Klucz konsoli platformy (`X-Platform-Key`). Pusty = konsola zamknięta (503). */
    val platformApiKey: String = "",
    /** Strefa czasowa kubełków minutowych / godzinowych / dziennych. */
    val zone: String = "Europe/Warsaw",
    val retention: Retention = Retention(),
    /** Co ile sekund gauge'e Prometheus są odświeżane z Redisa. */
    val prometheusRefreshSeconds: Long = 15,
    val recentEvents: Int = 200,
    val streamMaxLength: Long = 100_000,
    val ingest: Ingest = Ingest()
) {
    data class Retention(
        val minuteDays: Long = 3,
        val hourDays: Long = 90
    )

    data class Ingest(
        val queueCapacity: Int = 20_000,
        val batchSize: Int = 500,
        val flushIntervalMs: Long = 250
    )

    val zoneId: ZoneId get() = ZoneId.of(zone)
}
