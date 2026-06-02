package pl.detailing.crm.appointment.recurrence.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceSeriesId
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceType
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesRepository
import pl.detailing.crm.shared.*
import java.time.DayOfWeek
import java.time.Instant

data class GetRecurrenceSeriesQuery(
    val seriesId: RecurrenceSeriesId,
    val studioId: StudioId
)

data class RecurrenceSeriesResponse(
    val id: String,
    val type: RecurrenceType,
    val intervalWeeks: Int?,
    val daysOfWeek: List<String>?,
    val dayOfMonth: Int?,
    val endType: String,
    val endDate: String?,
    val maxOccurrences: Int?,
    val isOpenEnded: Boolean,
    val totalOccurrences: Long,
    val createdAt: Instant
)

@Service
class GetRecurrenceSeriesHandler(
    private val recurrenceSeriesRepository: RecurrenceSeriesRepository,
    private val appointmentRepository: AppointmentRepository
) {
    suspend fun handle(query: GetRecurrenceSeriesQuery): RecurrenceSeriesResponse =
        withContext(Dispatchers.IO) {
            val entity = recurrenceSeriesRepository.findByIdAndStudioId(
                query.seriesId.value,
                query.studioId.value
            ) ?: throw EntityNotFoundException("Seria cykliczna nie została znaleziona: ${query.seriesId}")

            val total = appointmentRepository.countBySeriesId(query.seriesId.value)

            val series = entity.toDomain()

            RecurrenceSeriesResponse(
                id = series.id.value.toString(),
                type = series.type,
                intervalWeeks = series.intervalWeeks,
                daysOfWeek = series.daysOfWeek?.sortedBy { it.value }?.map { it.name },
                dayOfMonth = series.dayOfMonth,
                endType = series.endType.name,
                endDate = series.endDate?.toString(),
                maxOccurrences = series.maxOccurrences,
                isOpenEnded = series.isOpenEnded,
                totalOccurrences = total,
                createdAt = series.createdAt
            )
        }
}
