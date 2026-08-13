package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramInsightRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Retencja danych modułu (RODO – minimalizacja i ograniczenie przechowywania):
 * - snapshoty postów i metryk: 24 miesiące (instagram.retention.months),
 * - insighty: 12 miesięcy.
 *
 * Uruchamiany 1. dnia miesiąca o 04:00. Wyłącznik: instagram.retention.enabled.
 */
@Component
class InstagramRetentionJob(
    private val postRepository: InstagramPostSnapshotRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val insightRepository: InstagramInsightRepository,
    @Value("\${instagram.retention.enabled:true}") private val enabled: Boolean,
    @Value("\${instagram.retention.months:24}") private val retentionMonths: Long
) {
    private val log = LoggerFactory.getLogger(InstagramRetentionJob::class.java)

    @Scheduled(cron = "\${instagram.retention.cron:0 0 4 1 * *}")
    @Transactional
    fun cleanup() {
        if (!enabled) return

        val cutoffInstant = Instant.now().minus(retentionMonths * 30, ChronoUnit.DAYS)
        val cutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths)
        val insightCutoff = Instant.now().minus(365, ChronoUnit.DAYS)

        val posts = postRepository.deleteByTakenAtBefore(cutoffInstant)
        val metrics = metricsRepository.deleteBySnapshotDateBefore(cutoffDate)
        val insights = insightRepository.deleteByCreatedAtBefore(insightCutoff)

        log.info(
            "Instagram retencja: usunięto {} postów, {} snapshotów metryk, {} insightów (próg {} mies.)",
            posts, metrics, insights, retentionMonths
        )
    }
}
