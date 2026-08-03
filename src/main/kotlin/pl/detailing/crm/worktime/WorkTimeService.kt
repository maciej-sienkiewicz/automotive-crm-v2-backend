package pl.detailing.crm.worktime

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

@Service
class WorkTimeService(
    private val entryRepository: WorkTimeEntryRepository,
    private val periodRepository: WorkTimePeriodRepository,
    private val permissionCheckService: PermissionCheckService
) {
    fun hasTrackWorkTime(userId: UserId, studioId: StudioId): Boolean =
        permissionCheckService.getTrackWorkTime(userId, studioId)

    fun getPeriodOrNull(userId: UUID, yearMonth: YearMonth): WorkTimePeriodEntity? =
        periodRepository.findByUserIdAndPeriod(userId, yearMonth.toString())

    @Transactional(readOnly = true)
    fun listPeriodSummaries(userId: UserId, studioId: StudioId): List<PeriodSummaryResponse> {
        val periods = periodRepository.findByUserIdAndStudioIdOrderByPeriodDesc(userId.value, studioId.value)
        return periods.map { it.toSummary(userId.value) }
    }

    @Transactional(readOnly = true)
    fun getPeriodDetail(userId: UserId, studioId: StudioId, yearMonth: YearMonth): PeriodDetailResponse {
        val periodEntity = periodRepository.findByUserIdAndPeriod(userId.value, yearMonth.toString())
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        val entries = entryRepository.findByUserIdAndStudioIdAndDateBetween(userId.value, studioId.value, from, to)
        val totalMinutes = entries.sumOf { it.minutes }

        return PeriodDetailResponse(
            period = yearMonth.toString(),
            label = yearMonth.toPolishLabel(),
            status = periodEntity?.status?.name ?: PeriodStatus.DRAFT.name,
            totalMinutes = totalMinutes,
            totalHours = formatMinutes(totalMinutes),
            entryCount = entries.size,
            returnNote = periodEntity?.returnNote,
            entries = entries.map { it.toResponse() }
        )
    }

    @Transactional
    fun upsertEntry(userId: UserId, studioId: StudioId, date: LocalDate, minutes: Int, note: String?): EntryResponse {
        val existing = entryRepository.findByUserIdAndDate(userId.value, date)
        val entry = if (existing != null) {
            existing.minutes = minutes
            existing.note = note
            existing.updatedAt = Instant.now()
            entryRepository.save(existing)
        } else {
            entryRepository.save(
                WorkTimeEntryEntity(
                    id = UUID.randomUUID(),
                    userId = userId.value,
                    studioId = studioId.value,
                    date = date,
                    minutes = minutes,
                    note = note
                )
            )
        }
        ensurePeriodExists(userId.value, studioId.value, YearMonth.from(date))
        return entry.toResponse()
    }

    @Transactional
    fun deleteEntry(userId: UserId, studioId: StudioId, date: LocalDate) {
        val entry = entryRepository.findByUserIdAndDate(userId.value, date) ?: return
        entryRepository.delete(entry)
    }

    @Transactional
    fun fillMonth(userId: UserId, studioId: StudioId, yearMonth: YearMonth): PeriodDetailResponse {
        val period = ensurePeriodExists(userId.value, studioId.value, yearMonth)
        if (period.status == PeriodStatus.APPROVED) throw ValidationException("Karta zatwierdzona — edycja niemożliwa")

        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        var current = from
        while (!current.isAfter(to)) {
            val dow = current.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                val existing = entryRepository.findByUserIdAndDate(userId.value, current)
                if (existing == null) {
                    entryRepository.save(
                        WorkTimeEntryEntity(
                            id = UUID.randomUUID(),
                            userId = userId.value,
                            studioId = studioId.value,
                            date = current,
                            minutes = 480
                        )
                    )
                }
            }
            current = current.plusDays(1)
        }
        return getPeriodDetail(userId, studioId, yearMonth)
    }

    @Transactional
    fun submitPeriod(userId: UserId, studioId: StudioId, yearMonth: YearMonth): PeriodSummaryResponse {
        val period = ensurePeriodExists(userId.value, studioId.value, yearMonth)
        if (period.status == PeriodStatus.APPROVED) throw ValidationException("Karta jest już zatwierdzona")
        if (period.status == PeriodStatus.SUBMITTED) throw ValidationException("Karta jest już złożona do zatwierdzenia")

        period.status = PeriodStatus.SUBMITTED
        period.submittedAt = Instant.now()
        period.updatedAt = Instant.now()
        periodRepository.save(period)
        return period.toSummary(userId.value)
    }

    @Transactional
    fun approvePeriod(userId: UserId, studioId: StudioId, yearMonth: YearMonth, approvedBy: UserId): PeriodSummaryResponse {
        val period = periodRepository.findByUserIdAndPeriod(userId.value, yearMonth.toString())
            ?: throw EntityNotFoundException("Karta za okres ${yearMonth} nie istnieje")
        if (period.status == PeriodStatus.DRAFT) throw ValidationException("Karta nie została jeszcze złożona do zatwierdzenia")
        if (period.status == PeriodStatus.APPROVED) throw ValidationException("Karta jest już zatwierdzona")

        period.status = PeriodStatus.APPROVED
        period.approvedAt = Instant.now()
        period.approvedBy = approvedBy.value
        period.updatedAt = Instant.now()
        periodRepository.save(period)
        return period.toSummary(userId.value)
    }

    @Transactional
    fun returnPeriod(userId: UserId, studioId: StudioId, yearMonth: YearMonth, returnedBy: UserId, note: String?): PeriodSummaryResponse {
        val period = periodRepository.findByUserIdAndPeriod(userId.value, yearMonth.toString())
            ?: throw EntityNotFoundException("Karta za okres ${yearMonth} nie istnieje")
        if (period.status == PeriodStatus.DRAFT) throw ValidationException("Karta nie była złożona do zatwierdzenia")

        period.status = PeriodStatus.RETURNED
        period.returnedAt = Instant.now()
        period.returnedBy = returnedBy.value
        period.returnNote = note
        period.updatedAt = Instant.now()
        periodRepository.save(period)
        return period.toSummary(userId.value)
    }

    private fun ensurePeriodExists(userId: UUID, studioId: UUID, yearMonth: YearMonth): WorkTimePeriodEntity {
        return periodRepository.findByUserIdAndPeriod(userId, yearMonth.toString())
            ?: periodRepository.save(
                WorkTimePeriodEntity(
                    id = UUID.randomUUID(),
                    userId = userId,
                    studioId = studioId,
                    period = yearMonth.toString()
                )
            )
    }

    private fun WorkTimePeriodEntity.toSummary(userId: UUID): PeriodSummaryResponse {
        val ym = YearMonth.parse(period)
        val totalMinutes = entryRepository.sumMinutesByUserIdAndDateBetween(
            userId, ym.atDay(1), ym.atEndOfMonth()
        ) ?: 0
        val entryCount = entryRepository.findByUserIdAndDateBetween(
            userId, ym.atDay(1), ym.atEndOfMonth()
        ).size
        return PeriodSummaryResponse(
            period = period,
            label = ym.toPolishLabel(),
            status = status.name,
            totalMinutes = totalMinutes,
            totalHours = formatMinutes(totalMinutes),
            entryCount = entryCount,
            returnNote = returnNote
        )
    }
}

private fun WorkTimeEntryEntity.toResponse() = EntryResponse(
    date = date.toString(),
    minutes = minutes,
    hours = formatMinutes(minutes),
    note = note
)

fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "$h:00" else "$h:${m.toString().padStart(2, '0')}"
}

private val polishMonths = listOf(
    "Styczeń", "Luty", "Marzec", "Kwiecień", "Maj", "Czerwiec",
    "Lipiec", "Sierpień", "Wrzesień", "Październik", "Listopad", "Grudzień"
)

fun YearMonth.toPolishLabel(): String = "${polishMonths[monthValue - 1]} $year"
