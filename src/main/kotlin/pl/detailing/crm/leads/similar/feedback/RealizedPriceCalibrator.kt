package pl.detailing.crm.leads.similar.feedback

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.service.taxonomy.serviceNameKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Nocny kalibrator cen ZREALIZOWANYCH → studio_price_anchors (FB3, V132).
 *
 * Pętla w 100% deterministyczna i w 100% SQL-owa: zero LLM, zero kliknięć. Liczy
 * medianę i rozrzut kwot pozycji z zamkniętych zleceń (okno 18 mies., ≥3 obserwacje)
 * per (studio, nazwa usługi). Zasila:
 *  - kotwicę zastępczą dla usług `requireManualPrice` (basePriceGross = 0 z
 *    UpdateServiceHandler) — bez niej bramka skali wyłącza się dokładnie przy
 *    najdroższych i najrzadszych robotach,
 *  - rozbieżność katalog↔historia na karcie compa.
 *
 * Pozycje bierzemy TYLKO ze statusów CONFIRMED/APPROVED zleceń COMPLETED/ARCHIVED —
 * na zamkniętej wizycie to dokładnie reguła Visit.effectiveGrossAmount (PENDING
 * nie ma prawa tam istnieć), więc nie kopiujemy reguły pieniędzy do SQL-a.
 * Normalizacja nazwy do name_key zostaje w Kotlinie ([serviceNameKey]) — jedna
 * definicja klucza, nigdy druga kopia w SQL.
 */
@Service
class RealizedPriceCalibrator(
    private val entityManager: EntityManager,
    private val anchorRepository: StudioPriceAnchorRepository,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.calibration-window-months:18}") private val windowMonths: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 03:40 UTC — po nocnych backupach, przed porannym ruchem. */
    @Scheduled(cron = "\${crm.ai.similar-visits.calibration-cron:0 40 3 * * *}")
    fun recalibrate() {
        if (!enabled) return
        runCatching { recalibrateNow() }
            .onFailure { log.warn("[PRICE_ANCHORS] Kalibracja nie powiodła się: {}", it.message) }
    }

    @Transactional
    fun recalibrateNow(): Int {
        val windowFrom = Instant.now().minus(windowMonths * 30, ChronoUnit.DAYS)

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(
            """
            SELECT v.studio_id, i.service_name, i.final_price_gross
            FROM visit_service_items i
            JOIN visits v ON v.id = i.visit_id
            WHERE v.deleted_at IS NULL
              AND v.status IN ('COMPLETED', 'ARCHIVED')
              AND i.status IN ('CONFIRMED', 'APPROVED')
              AND i.final_price_gross > 0
              AND COALESCE(v.actual_completion_date, v.scheduled_date) >= :windowFrom
            """
        ).setParameter("windowFrom", Timestamp.from(windowFrom)).resultList as List<Array<Any?>>

        val grouped = rows
            .mapNotNull { row ->
                val studioId = row[0] as? UUID ?: return@mapNotNull null
                val name = (row[1] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val gross = (row[2] as? Number)?.toLong() ?: return@mapNotNull null
                Triple(studioId, serviceNameKey(name), gross)
            }
            .groupBy({ it.first to it.second }, { it.third })
            .filterValues { it.size >= MIN_OBSERVATIONS }

        val windowFromDate = LocalDate.ofInstant(windowFrom, ZoneOffset.UTC)
        val now = Instant.now()
        var updated = 0
        grouped.forEach { (key, prices) ->
            val (studioId, nameKey) = key
            val sorted = prices.sorted()
            val median = percentile(sorted, 0.5)
            val iqrRatio = if (sorted.size >= 4 && median > 0) {
                BigDecimal((percentile(sorted, 0.75) - percentile(sorted, 0.25)).toDouble() / median)
                    .setScale(3, RoundingMode.HALF_UP)
            } else null

            val existing = anchorRepository.findByStudioIdAndNameKey(studioId, nameKey)
            if (existing != null) {
                existing.medianRealizedGross = median
                existing.iqrRatio = iqrRatio
                existing.observations = sorted.size
                existing.windowFrom = windowFromDate
                existing.computedAt = now
                anchorRepository.save(existing)
            } else {
                anchorRepository.save(
                    StudioPriceAnchorEntity(
                        studioId = studioId,
                        nameKey = nameKey,
                        medianRealizedGross = median,
                        iqrRatio = iqrRatio,
                        observations = sorted.size,
                        windowFrom = windowFromDate,
                        computedAt = now
                    )
                )
            }
            updated++
        }

        if (updated > 0) log.info("[PRICE_ANCHORS] Przeliczono {} kotwic zrealizowanych", updated)
        return updated
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = (sorted.size - 1) * p
        val lower = sorted[index.toInt()]
        val upper = sorted[minOf(index.toInt() + 1, sorted.size - 1)]
        val fraction = index - index.toInt()
        return (lower + (upper - lower) * fraction).toLong()
    }

    companion object {
        /** Mediana z dwóch obserwacji to nie mediana, tylko średnia dwóch przypadków. */
        const val MIN_OBSERVATIONS = 3
    }
}
