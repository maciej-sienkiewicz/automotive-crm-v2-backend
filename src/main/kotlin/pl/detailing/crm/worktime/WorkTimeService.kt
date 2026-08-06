package pl.detailing.crm.worktime

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
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
    private val permissionCheckService: PermissionCheckService,
    private val auditService: AuditService,
    private val auditActorResolver: AuditActorResolver
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
        val overtimeMinutes = entries.sumOf { maxOf(0, it.minutes - 480) }

        return PeriodDetailResponse(
            period = yearMonth.toString(),
            label = yearMonth.toPolishLabel(),
            status = periodEntity?.status?.name ?: PeriodStatus.DRAFT.name,
            totalMinutes = totalMinutes,
            totalHours = formatMinutes(totalMinutes),
            entryCount = entries.size,
            overtimeMinutes = overtimeMinutes,
            overtimeHours = formatMinutes(overtimeMinutes),
            returnNote = periodEntity?.returnNote,
            entries = entries.map { it.toResponse() }
        )
    }

    @Transactional
    fun upsertEntry(userId: UserId, studioId: StudioId, date: LocalDate, minutes: Int, note: String?): EntryResponse {
        val existing = entryRepository.findByUserIdAndDate(userId.value, date)
        // Read before mutating — the entity is updated in place below.
        val previousMinutes = existing?.minutes
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

        auditService.recordSync(
            AuditEvent(
                studioId = studioId,
                actor = actorFor(userId),
                module = AuditModule.WORK_TIME,
                action = AuditAction.WORK_TIME_LOGGED,
                entityId = entry.id.toString(),
                entityDisplayName = "Czas pracy — ${date}",
                changes = listOfNotNull(
                    FieldChange("workDate", null, date.toString()),
                    FieldChange("hoursWorked", previousMinutes?.let { formatMinutes(it) }, formatMinutes(minutes)),
                    note?.let { FieldChange("content", null, it) }
                ),
                metadata = mapOf("employeeUserId" to userId.toString(), "minutes" to minutes.toString())
            )
        )

        return entry.toResponse()
    }

    @Transactional
    fun deleteEntry(userId: UserId, studioId: StudioId, date: LocalDate) {
        val entry = entryRepository.findByUserIdAndDate(userId.value, date) ?: return
        val removedMinutes = entry.minutes
        entryRepository.delete(entry)

        auditService.recordSync(
            AuditEvent(
                studioId = studioId,
                actor = actorFor(userId),
                module = AuditModule.WORK_TIME,
                action = AuditAction.WORK_TIME_ENTRY_DELETED,
                entityId = entry.id.toString(),
                entityDisplayName = "Czas pracy — ${date}",
                changes = listOf(
                    FieldChange("workDate", date.toString(), null),
                    FieldChange("hoursWorked", formatMinutes(removedMinutes), null)
                ),
                metadata = mapOf("employeeUserId" to userId.toString())
            )
        )
    }

    @Transactional
    fun fillMonth(userId: UserId, studioId: StudioId, yearMonth: YearMonth): PeriodDetailResponse {
        val period = ensurePeriodExists(userId.value, studioId.value, yearMonth)
        if (period.status == PeriodStatus.APPROVED) throw ValidationException("Karta zatwierdzona — edycja niemożliwa")

        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        var current = from
        var daysFilled = 0
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
                    daysFilled++
                }
            }
            current = current.plusDays(1)
        }

        // One entry for the whole gesture rather than one per day — twenty identical rows
        // would bury everything else in the feed.
        if (daysFilled > 0) {
            auditService.recordSync(
                AuditEvent(
                    studioId = studioId,
                    actor = actorFor(userId),
                    module = AuditModule.WORK_TIME,
                    action = AuditAction.WORK_TIME_LOGGED,
                    entityId = period.id.toString(),
                    entityDisplayName = "Karta czasu pracy — ${yearMonth.toPolishLabel()}",
                    changes = listOf(
                        FieldChange("periodStart", null, from.toString()),
                        FieldChange("periodEnd", null, to.toString())
                    ),
                    metadata = mapOf(
                        "employeeUserId" to userId.toString(),
                        "period" to yearMonth.toString(),
                        "daysFilled" to daysFilled.toString(),
                        "fillMonth" to "true"
                    )
                )
            )
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

        recordPeriodEvent(
            studioId = studioId,
            userId = userId,
            period = period,
            yearMonth = yearMonth,
            action = AuditAction.WORK_TIME_PERIOD_SAVED,
            statusFrom = PeriodStatus.DRAFT.name,
            statusTo = PeriodStatus.SUBMITTED.name
        )

        return period.toSummary(userId.value)
    }

    @Transactional
    fun approvePeriod(userId: UserId, studioId: StudioId, yearMonth: YearMonth, approvedBy: UserId): PeriodSummaryResponse {
        val period = periodRepository.findByUserIdAndPeriod(userId.value, yearMonth.toString())
            ?: throw EntityNotFoundException("Karta za okres ${yearMonth} nie istnieje")
        if (period.status == PeriodStatus.DRAFT) throw ValidationException("Karta nie została jeszcze złożona do zatwierdzenia")
        if (period.status == PeriodStatus.APPROVED) throw ValidationException("Karta jest już zatwierdzona")

        val previousStatus = period.status
        period.status = PeriodStatus.APPROVED
        period.approvedAt = Instant.now()
        period.approvedBy = approvedBy.value
        period.updatedAt = Instant.now()
        periodRepository.save(period)

        recordPeriodEvent(
            studioId = studioId,
            userId = userId,
            period = period,
            yearMonth = yearMonth,
            action = AuditAction.WORK_TIME_APPROVED,
            statusFrom = previousStatus.name,
            statusTo = PeriodStatus.APPROVED.name,
            actor = auditActorResolver.current(AuditActor.employee(approvedBy, null))
        )

        return period.toSummary(userId.value)
    }

    @Transactional
    fun returnPeriod(userId: UserId, studioId: StudioId, yearMonth: YearMonth, returnedBy: UserId, note: String?): PeriodSummaryResponse {
        val period = periodRepository.findByUserIdAndPeriod(userId.value, yearMonth.toString())
            ?: throw EntityNotFoundException("Karta za okres ${yearMonth} nie istnieje")
        if (period.status == PeriodStatus.DRAFT) throw ValidationException("Karta nie była złożona do zatwierdzenia")

        val previousStatus = period.status
        period.status = PeriodStatus.RETURNED
        period.returnedAt = Instant.now()
        period.returnedBy = returnedBy.value
        period.returnNote = note
        period.updatedAt = Instant.now()
        periodRepository.save(period)

        recordPeriodEvent(
            studioId = studioId,
            userId = userId,
            period = period,
            yearMonth = yearMonth,
            action = AuditAction.WORK_TIME_REJECTED,
            statusFrom = previousStatus.name,
            statusTo = PeriodStatus.RETURNED.name,
            actor = auditActorResolver.current(AuditActor.employee(returnedBy, null)),
            extraChanges = listOfNotNull(note?.let { FieldChange("content", null, it) })
        )

        return period.toSummary(userId.value)
    }

    /**
     * The employee whose card this is, unless someone else is acting on it — a manager
     * approving or returning it. [AuditActorResolver] picks up the authenticated user;
     * the fallback keeps the id when the security context is not reachable.
     */
    private fun actorFor(employee: UserId): AuditActor =
        auditActorResolver.current(AuditActor.employee(employee, null))

    private fun recordPeriodEvent(
        studioId: StudioId,
        userId: UserId,
        period: WorkTimePeriodEntity,
        yearMonth: YearMonth,
        action: AuditAction,
        statusFrom: String,
        statusTo: String,
        actor: AuditActor = actorFor(userId),
        extraChanges: List<FieldChange> = emptyList()
    ) {
        auditService.recordSync(
            AuditEvent(
                studioId = studioId,
                actor = actor,
                module = AuditModule.WORK_TIME,
                action = action,
                entityId = period.id.toString(),
                entityDisplayName = "Karta czasu pracy — ${yearMonth.toPolishLabel()}",
                changes = listOf(FieldChange("status", statusFrom, statusTo)) + extraChanges,
                metadata = mapOf(
                    "employeeUserId" to userId.toString(),
                    "period" to yearMonth.toString()
                )
            )
        )
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
        val entries = entryRepository.findByUserIdAndDateBetween(userId, ym.atDay(1), ym.atEndOfMonth())
        val totalMinutes = entries.sumOf { it.minutes }
        val overtimeMinutes = entries.sumOf { maxOf(0, it.minutes - 480) }
        return PeriodSummaryResponse(
            period = period,
            label = ym.toPolishLabel(),
            status = status.name,
            totalMinutes = totalMinutes,
            totalHours = formatMinutes(totalMinutes),
            entryCount = entries.size,
            overtimeMinutes = overtimeMinutes,
            overtimeHours = formatMinutes(overtimeMinutes),
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
