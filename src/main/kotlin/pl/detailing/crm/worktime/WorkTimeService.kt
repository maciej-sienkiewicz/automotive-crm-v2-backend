package pl.detailing.crm.worktime

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.worktime.attendance.AttendanceSheetService
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@Service
class WorkTimeService(
    private val entryRepository: WorkTimeEntryRepository,
    private val periodRepository: WorkTimePeriodRepository,
    private val permissionCheckService: PermissionCheckService,
    private val auditService: AuditService,
    private val auditActorResolver: AuditActorResolver,
    private val businessEventPublisher: BusinessEventPublisher,
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val userRepository: UserRepository,
    private val attendanceSheetService: AttendanceSheetService,
    private val eventPublisher: ApplicationEventPublisher
) {
    /** Zegar „dziś" dla braków i „Standard dziś" — podmieniany w testach. */
    var clock: Clock = Clock.system(ZoneId.of("Europe/Warsaw"))

    companion object {
        const val RETURN_NOTE_MAX_LENGTH = 1000
    }

    fun today(): LocalDate = LocalDate.now(clock)

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
        val periodEntity = periodRepository.findByUserIdAndStudioIdAndPeriod(userId.value, studioId.value, yearMonth.toString())
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        val entries = entryRepository.findByUserIdAndStudioIdAndDateBetween(userId.value, studioId.value, from, to)
        val month = WorkTimeCalendar.month(yearMonth, entries, leaveByDayOf(userId, studioId, yearMonth), today())

        return PeriodDetailResponse(
            period = yearMonth.toString(),
            label = yearMonth.toPolishLabel(),
            status = periodEntity?.status?.name ?: PeriodStatus.DRAFT.name,
            totalMinutes = month.totalMinutes,
            totalHours = formatMinutes(month.totalMinutes),
            entryCount = entries.size,
            overtimeMinutes = month.overtimeMinutes,
            overtimeHours = formatMinutes(month.overtimeMinutes),
            returnNote = periodEntity?.visibleReturnNote(),
            entries = entries.map { it.toResponse() },
            days = month.days.map { it.toResponse() },
            expectedMinutes = month.expectedMinutes,
            missingWorkingDays = month.missingWorkingDays
        )
    }

    /**
     * Urlopy i L4 osoby w miesiącu, dzień po dniu. Urlopy są przypięte do rekordu
     * pracownika, karta do konta — konto bez rekordu pracownika nie ma urlopów w systemie.
     */
    fun leaveByDayOf(userId: UserId, studioId: StudioId, yearMonth: YearMonth) =
        employeeRepository.findByStudioIdAndUserId(studioId.value, userId.value)
            ?.let { employee ->
                employeeLeaveRepository.findOverlappingOfEmployee(
                    studioId.value, employee.id, yearMonth.atDay(1), yearMonth.atEndOfMonth()
                )
            }
            .orEmpty()
            .let { WorkTimeCalendar.leaveByDay(yearMonth, it) }

    /**
     * Czy pracownik może zmieniać kartę za [yearMonth]. Złożona czeka na decyzję
     * przełożonego i jest tylko do odczytu: zmiana pod ręką zatwierdzającego oznaczałaby,
     * że zatwierdza coś innego, niż widział. Zatwierdzona — do czasu odblokowania.
     */
    fun requireEditable(userId: UserId, studioId: StudioId, yearMonth: YearMonth) {
        val period = periodRepository.findByUserIdAndStudioIdAndPeriod(userId.value, studioId.value, yearMonth.toString())
        when (period?.status) {
            PeriodStatus.APPROVED ->
                throw ForbiddenException("Karta jest zaakceptowana — edycja niemożliwa do czasu zwrotu do poprawy")
            PeriodStatus.SUBMITTED -> throw ConflictException(
                "Karta za ${yearMonth.toPolishLabelInSentence()} czeka na decyzję przełożonego. " +
                    "Jeśli trzeba coś poprawić, poproś o zwrot."
            )
            else -> Unit
        }
    }

    /** „Standard dziś" — 8 godzin na dziś, w strefie studia. */
    @Transactional
    fun standardToday(userId: UserId, studioId: StudioId): EntryResponse =
        upsertEntry(userId, studioId, today(), WorkTimeCalendar.STANDARD_DAY_MINUTES, null)

    @Transactional
    fun upsertEntry(userId: UserId, studioId: StudioId, date: LocalDate, minutes: Int, note: String?): EntryResponse {
        requireEditable(userId, studioId, YearMonth.from(date))
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

        // Live metrics — liczymy zapisany wpis godzin pracownika (nowy albo poprawiony).
        businessEventPublisher.publish(
            tenantId = studioId,
            type = BusinessEventType.WORKTIME_ENTRY_SAVED,
            attributes = mapOf(
                "entryId" to entry.id.toString(),
                "employeeUserId" to userId.value.toString(),
                "date" to date.toString(),
                "minutes" to minutes.toString()
            )
        )

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
        requireEditable(userId, studioId, YearMonth.from(date))
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
        requireEditable(userId, studioId, yearMonth)
        val period = ensurePeriodExists(userId.value, studioId.value, yearMonth)

        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        // Osiem godzin tylko w dni, w które się pracuje: święto i dzień urlopu/L4 z wpisem
        // 8:00 to twierdzenie, że ktoś był w pracy, kiedy go nie było — a karta idzie pod podpis.
        val leaveByDay = leaveByDayOf(userId, studioId, yearMonth)
        var current = from
        var daysFilled = 0
        while (!current.isAfter(to)) {
            if (pl.detailing.crm.employee.leaverequest.domain.PolishHolidays.isWorkingDay(current) && current !in leaveByDay) {
                val existing = entryRepository.findByUserIdAndDate(userId.value, current)
                if (existing == null) {
                    val filled = entryRepository.save(
                        WorkTimeEntryEntity(
                            id = UUID.randomUUID(),
                            userId = userId.value,
                            studioId = studioId.value,
                            date = current,
                            minutes = WorkTimeCalendar.STANDARD_DAY_MINUTES
                        )
                    )
                    daysFilled++

                    // Live metrics — liczymy KAŻDY zapisany wpis godzin, tak samo jak przy
                    // pojedynczej edycji: licznik ma mierzyć wpisy, a nie gesty w interfejsie.
                    businessEventPublisher.publish(
                        tenantId = studioId,
                        type = BusinessEventType.WORKTIME_ENTRY_SAVED,
                        attributes = mapOf(
                            "entryId" to filled.id.toString(),
                            "employeeUserId" to userId.value.toString(),
                            "date" to filled.date.toString(),
                            "minutes" to filled.minutes.toString(),
                            "fillMonth" to "true"
                        )
                    )
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

        // Stan sprzed złożenia: karta poprawiona po zwrocie przechodzi RETURNED → SUBMITTED,
        // a dziennik zdarzeń ma to pokazać, a nie udawać pierwsze złożenie.
        val previousStatus = period.status
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
            statusFrom = previousStatus.name,
            statusTo = PeriodStatus.SUBMITTED.name
        )

        eventPublisher.publishEvent(
            WorkTimeCardSubmittedEvent(
                studioId = studioId,
                employeeUserId = userId,
                employeeName = displayName(studioId, userId) ?: "",
                period = yearMonth
            )
        )

        return period.toSummary(userId.value)
    }

    /**
     * Zatwierdza kartę — wyłącznie złożoną (SUBMITTED). Zwrócona czeka na poprawki
     * pracownika: zatwierdzenie jej „po drodze" przyjęłoby godziny, które przełożony sam
     * przed chwilą zakwestionował.
     *
     * Notatka zwrotu znika: dotyczyła poprzedniej wersji karty. Jeśli za ten miesiąc jest
     * już lista obecności, na której tej osoby nie ma, lista przestaje być aktualna.
     */
    @Transactional
    fun approvePeriod(
        userId: UserId,
        studioId: StudioId,
        yearMonth: YearMonth,
        approvedBy: UserId,
        approvedByName: String? = null
    ): WorkTimePeriodEntity {
        // Four-eyes rule: a manager never approves their own card, whatever role they hold.
        if (userId == approvedBy) throw ForbiddenException("Nie można zatwierdzić własnej karty czasu pracy")
        val period = periodRepository.findByUserIdAndStudioIdAndPeriod(userId.value, studioId.value, yearMonth.toString())
            ?: throw EntityNotFoundException("Karta za okres ${yearMonth} nie istnieje")
        when (period.status) {
            PeriodStatus.SUBMITTED -> Unit
            PeriodStatus.DRAFT -> throw ConflictException("Karta nie została jeszcze złożona do zatwierdzenia.")
            PeriodStatus.APPROVED -> throw ConflictException("Karta jest już zatwierdzona.")
            PeriodStatus.RETURNED -> throw ConflictException(
                "Karta jest zwrócona do poprawy. Zatwierdzić można ją dopiero po ponownym złożeniu przez pracownika."
            )
        }

        val previousStatus = period.status
        val now = Instant.now()
        period.status = PeriodStatus.APPROVED
        period.approvedAt = now
        period.approvedBy = approvedBy.value
        period.returnNote = null
        period.updatedAt = now
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

        attendanceSheetService.outdateAfterCardChange(
            studioId = studioId,
            period = yearMonth,
            cardUserId = userId,
            cardEmployeeId = employeeIdOf(studioId, userId),
            onSheet = false,
            actorId = approvedBy,
            actorName = approvedByName
        )

        eventPublisher.publishEvent(
            WorkTimeCardDecidedEvent(
                studioId = studioId,
                employeeUserId = userId,
                period = yearMonth,
                outcome = WorkTimeCardDecidedEvent.Outcome.APPROVED,
                note = null,
                decidedByName = approvedByName
            )
        )
        return period
    }

    /**
     * Zwraca kartę pracownikowi. Ze złożonej — do poprawy; z zatwierdzonej — odblokowanie
     * po zatwierdzeniu. Notatka jest wymagana: pracownik musi wiedzieć, CO poprawić,
     * a bez niej dostaje tylko kartę z powrotem i zgaduje.
     *
     * Odblokowanie karty, która jest na liście obecności tego miesiąca, unieważnia listę:
     * godziny na niej przestały być zatwierdzone.
     */
    @Transactional
    fun returnPeriod(
        userId: UserId,
        studioId: StudioId,
        yearMonth: YearMonth,
        returnedBy: UserId,
        note: String?,
        returnedByName: String? = null
    ): WorkTimePeriodEntity {
        if (userId == returnedBy) throw ForbiddenException("Nie można zwrócić własnej karty czasu pracy")
        val cleanNote = note?.trim().orEmpty()
        if (cleanNote.isEmpty()) {
            throw ValidationException("Napisz, co trzeba poprawić — pracownik zobaczy to przy karcie.", "note")
        }
        if (cleanNote.length > RETURN_NOTE_MAX_LENGTH) {
            throw ValidationException("Notatka może mieć najwyżej $RETURN_NOTE_MAX_LENGTH znaków.", "note")
        }
        val period = periodRepository.findByUserIdAndStudioIdAndPeriod(userId.value, studioId.value, yearMonth.toString())
            ?: throw EntityNotFoundException("Karta za okres ${yearMonth} nie istnieje")
        when (period.status) {
            PeriodStatus.SUBMITTED, PeriodStatus.APPROVED -> Unit
            PeriodStatus.DRAFT -> throw ConflictException("Karta nie była złożona do zatwierdzenia.")
            PeriodStatus.RETURNED -> throw ConflictException("Karta jest już zwrócona do poprawy.")
        }

        val previousStatus = period.status
        val now = Instant.now()
        period.status = PeriodStatus.RETURNED
        period.returnedAt = now
        period.returnedBy = returnedBy.value
        period.returnNote = cleanNote
        period.updatedAt = now
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
            extraChanges = listOf(FieldChange("content", null, cleanNote))
        )

        if (previousStatus == PeriodStatus.APPROVED) {
            attendanceSheetService.outdateAfterCardChange(
                studioId = studioId,
                period = yearMonth,
                cardUserId = userId,
                cardEmployeeId = employeeIdOf(studioId, userId),
                onSheet = true,
                actorId = returnedBy,
                actorName = returnedByName
            )
        }

        eventPublisher.publishEvent(
            WorkTimeCardDecidedEvent(
                studioId = studioId,
                employeeUserId = userId,
                period = yearMonth,
                outcome = WorkTimeCardDecidedEvent.Outcome.RETURNED,
                note = cleanNote,
                decidedByName = returnedByName
            )
        )
        return period
    }

    private fun employeeIdOf(studioId: StudioId, userId: UserId): EmployeeId? =
        employeeRepository.findByStudioIdAndUserId(studioId.value, userId.value)?.let { EmployeeId(it.id) }

    /** Imię i nazwisko z rekordu pracownika, a bez niego — z konta. */
    fun displayName(studioId: StudioId, userId: UserId): String? {
        employeeRepository.findByStudioIdAndUserId(studioId.value, userId.value)?.let {
            return "${it.firstName} ${it.lastName}".trim()
        }
        return userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?.let { "${it.firstName} ${it.lastName}".trim() }
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

    fun ensurePeriodExists(userId: UUID, studioId: UUID, yearMonth: YearMonth): WorkTimePeriodEntity {
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
            returnNote = visibleReturnNote()
        )
    }
}

/** Notatka zwrotu ma sens tylko przy karcie zwróconej — po ponownym złożeniu jest historią. */
fun WorkTimePeriodEntity.visibleReturnNote(): String? =
    returnNote?.takeIf { status == PeriodStatus.RETURNED }

fun WorkTimeCalendar.Day.toResponse() = CardDayResponse(
    date = date.toString(),
    minutes = entry?.minutes,
    note = entry?.note,
    isWorkingDay = isWorkingDay,
    holidayName = holidayName,
    leave = leave?.let { CardLeaveResponse(type = it.name, label = WorkTimeCalendar.leaveLabel(it)) },
    missing = missing
)

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
