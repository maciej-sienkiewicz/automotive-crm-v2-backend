package pl.detailing.crm.leads.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.StudioId
import java.util.UUID

data class GetTimeAnalyticsQuery(
    val studioId: StudioId,
    val timezone: String = "UTC",
    val valueMin: Long? = null,
    val valueMax: Long? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null
)

data class TimeBucketResult(
    val bucket: Int,
    val incomingCount: Long,
    val acceptedCount: Long,
    val rejectedCount: Long
)

data class TimeAnalyticsResult(
    val byHour: List<TimeBucketResult>,
    val byDayOfMonth: List<TimeBucketResult>
)

@Service
class GetTimeAnalyticsHandler(
    private val leadRepository: LeadRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(query: GetTimeAnalyticsQuery): TimeAnalyticsResult = withContext(Dispatchers.IO) {
        val studioId = query.studioId.value
        val tz = query.timezone.ifBlank { "UTC" }

        TimeAnalyticsResult(
            byHour = buildBuckets(
                size = 24,
                incoming = leadRepository.countIncomingByTimeBucket(studioId, "hour", tz, query.valueMin, query.valueMax, query.dateFrom, query.dateTo),
                accepted = leadRepository.countAcceptedByTimeBucket(studioId, "hour", tz, query.valueMin, query.valueMax, query.dateFrom, query.dateTo),
                rejected = leadRepository.countRejectedByTimeBucket(studioId, "hour", tz, query.valueMin, query.valueMax, query.dateFrom, query.dateTo)
            ),
            byDayOfMonth = buildBuckets(
                size = 31,
                offset = 1,
                incoming = leadRepository.countIncomingByTimeBucket(studioId, "day", tz, query.valueMin, query.valueMax, query.dateFrom, query.dateTo),
                accepted = leadRepository.countAcceptedByTimeBucket(studioId, "day", tz, query.valueMin, query.valueMax, query.dateFrom, query.dateTo),
                rejected = leadRepository.countRejectedByTimeBucket(studioId, "day", tz, query.valueMin, query.valueMax, query.dateFrom, query.dateTo)
            )
        )
    }

    private fun buildBuckets(
        size: Int,
        offset: Int = 0,
        incoming: List<Array<Any>>,
        accepted: List<Array<Any>>,
        rejected: List<Array<Any>>
    ): List<TimeBucketResult> {
        val inMap = incoming.associate { row -> (row[0] as Number).toInt() to (row[1] as Number).toLong() }
        val accMap = accepted.associate { row -> (row[0] as Number).toInt() to (row[1] as Number).toLong() }
        val rejMap = rejected.associate { row -> (row[0] as Number).toInt() to (row[1] as Number).toLong() }

        return (offset until offset + size).map { b ->
            TimeBucketResult(
                bucket = b,
                incomingCount = inMap.getOrDefault(b, 0L),
                acceptedCount = accMap.getOrDefault(b, 0L),
                rejectedCount = rejMap.getOrDefault(b, 0L)
            )
        }
    }
}
