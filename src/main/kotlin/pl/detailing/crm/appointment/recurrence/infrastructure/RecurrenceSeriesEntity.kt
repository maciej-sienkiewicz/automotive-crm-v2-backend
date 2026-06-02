package pl.detailing.crm.appointment.recurrence.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.appointment.recurrence.domain.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "appointment_recurrence_series",
    indexes = [Index(name = "idx_recurrence_series_studio", columnList = "studio_id")]
)
class RecurrenceSeriesEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type", nullable = false, length = 10)
    val recurrenceType: RecurrenceType,

    @Column(name = "interval_weeks", nullable = true)
    val intervalWeeks: Int? = null,

    @Column(name = "days_of_week", nullable = true)
    val daysOfWeek: Int? = null,

    @Column(name = "day_of_month", nullable = true)
    val dayOfMonth: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "end_type", nullable = false, length = 10)
    val endType: RecurrenceEndType,

    @Column(name = "end_date", nullable = true)
    val endDate: java.time.LocalDate? = null,

    @Column(name = "max_occurrences", nullable = true)
    val maxOccurrences: Int? = null,

    @Column(name = "is_open_ended", nullable = false, columnDefinition = "boolean not null default false")
    val isOpenEnded: Boolean = false,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain() = RecurrenceSeries(
        id = RecurrenceSeriesId(id),
        studioId = StudioId(studioId),
        type = recurrenceType,
        intervalWeeks = intervalWeeks,
        daysOfWeek = daysOfWeek?.let { bitmask ->
            DayOfWeek.values().filter { (bitmask shr (it.value - 1)) and 1 == 1 }.toSet()
        },
        dayOfMonth = dayOfMonth,
        endType = endType,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        isOpenEnded = isOpenEnded,
        createdBy = UserId(createdBy),
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(series: RecurrenceSeries): RecurrenceSeriesEntity {
            val dowBitmask = series.daysOfWeek?.fold(0) { acc, day -> acc or (1 shl (day.value - 1)) }
            return RecurrenceSeriesEntity(
                id = series.id.value,
                studioId = series.studioId.value,
                recurrenceType = series.type,
                intervalWeeks = series.intervalWeeks,
                daysOfWeek = dowBitmask,
                dayOfMonth = series.dayOfMonth,
                endType = series.endType,
                endDate = series.endDate,
                maxOccurrences = series.maxOccurrences,
                isOpenEnded = series.isOpenEnded,
                createdBy = series.createdBy.value,
                createdAt = series.createdAt
            )
        }
    }
}
