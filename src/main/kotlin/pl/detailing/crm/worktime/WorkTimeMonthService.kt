package pl.detailing.crm.worktime

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.worktime.attendance.AttendanceSheetEntity
import pl.detailing.crm.worktime.attendance.AttendanceSheetRepository
import pl.detailing.crm.worktime.attendance.AttendanceSheetService
import pl.detailing.crm.worktime.attendance.AttendanceSheetStatus
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.text.Collator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Lista miesięczna: jeden przepływ na miesiąc — karty zbierane → karty zatwierdzane →
 * lista obecności podpisana (docs/api-worktime-months.md).
 *
 * Dotąd ten sam miesiąc miał dwa niezależne „zatwierdzenia": kartę (per osoba) i listę
 * obecności (PDF dla wielu osób), a lista mogła powstać z niezłożonych kart i nie wiedziała,
 * że karta zmieniła się po podpisie. Tu oba spotykają się w jednym widoku: kto złożył,
 * kto nie, co czeka na decyzję i czy podpisana lista jest jeszcze aktualna.
 *
 * Kto ma kartę w miesiącu: konta, których rola liczy czas pracy (bez właścicieli), aktywne
 * i założone przed końcem miesiąca — oraz każdy, kto za ten miesiąc ma już kartę lub wpisy,
 * nawet jeśli dziś czasu już nie liczy. Karta złożona przed odebraniem roli nadal jest
 * rozliczeniem tego miesiąca.
 */
@Service
class WorkTimeMonthService(
    private val periodRepository: WorkTimePeriodRepository,
    private val entryRepository: WorkTimeEntryRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val attendanceSheetRepository: AttendanceSheetRepository,
    private val attendanceSheetService: AttendanceSheetService,
    private val workTimeService: WorkTimeService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(WorkTimeMonthService::class.java)

    /** Zegar „dziś" — braki liczą się do dziś włącznie; podmieniany w testach. */
    var clock: Clock = Clock.system(WARSAW)

    companion object {
        private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        private val POLISH: Locale = Locale.forLanguageTag("pl-PL")

        /** Ta sama osoba dostaje przypomnienie o karcie najwyżej raz na tyle czasu. */
        val REMIND_INTERVAL: Duration = Duration.ofHours(12)

        /**
         * Stan karty z wiersza okresu i wpisów. Wiersz okresu powstaje także bez udziału
         * pracownika (przypomnienie menedżera), więc „nie zaczęta" to: nic nie wpisano
         * i karta nigdy nie była złożona.
         */
        fun cardStatus(period: WorkTimePeriodEntity?, hasEntries: Boolean): CardStatus = when {
            period == null -> if (hasEntries) CardStatus.DRAFT else CardStatus.NOT_STARTED
            period.status == PeriodStatus.DRAFT ->
                if (!hasEntries && period.submittedAt == null) CardStatus.NOT_STARTED else CardStatus.DRAFT
            period.status == PeriodStatus.SUBMITTED -> CardStatus.SUBMITTED
            period.status == PeriodStatus.RETURNED -> CardStatus.RETURNED
            else -> CardStatus.APPROVED
        }

        /**
         * Etap miesiąca. Kolejność rozstrzyga, co jest krokiem następnym:
         * 1. karta czeka na decyzję → REVIEWING (decyzja przed podpisem),
         * 2. najnowsza lista podpisana i nieaktualna → NEEDS_RESIGN,
         * 3. najnowsza lista podpisana i aktualna → SIGNED (także gdy ktoś został świadomie
         *    pominięty — miesiąc jest rozliczony),
         * 4. jest karta niezłożona (albo nie ma żadnej) → COLLECTING,
         * 5. wszystkie zatwierdzone, ważnej podpisanej listy brak → READY_TO_SIGN.
         *
         * @param latestSheetSigned null, gdy za miesiąc nie ma żadnej listy.
         */
        fun stageOf(statuses: Collection<CardStatus>, latestSheetSigned: Boolean?, latestSheetOutdated: Boolean): MonthStage =
            when {
                statuses.any { it == CardStatus.SUBMITTED } -> MonthStage.REVIEWING
                latestSheetSigned == true && latestSheetOutdated -> MonthStage.NEEDS_RESIGN
                latestSheetSigned == true -> MonthStage.SIGNED
                statuses.isEmpty() || statuses.any { it != CardStatus.APPROVED } -> MonthStage.COLLECTING
                else -> MonthStage.READY_TO_SIGN
            }

        /**
         * Zasada czterech oczu: o własnej karcie nie decyduje nikt. Decyzja istnieje tylko
         * dla karty złożonej (zatwierdź / zwróć) i zatwierdzonej (odblokuj).
         */
        fun canDecide(callerId: UserId, cardUserId: UUID, status: CardStatus): Boolean =
            callerId.value != cardUserId && (status == CardStatus.SUBMITTED || status == CardStatus.APPROVED)
    }

    // ── Odczyt ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun overview(studioId: StudioId, callerId: UserId, yearMonth: YearMonth): MonthOverviewResponse {
        val month = loadMonth(studioId, yearMonth)
        val rows = month.members.map { row(month, it, callerId) }
        val statuses = rows.map { it.status }
        val sheets = month.sheets
        val latest = sheets.firstOrNull()

        return MonthOverviewResponse(
            period = yearMonth.toString(),
            label = yearMonth.toPolishLabel(),
            workingDays = WorkTimeCalendar.workingDaysOf(yearMonth),
            stage = stageOf(statuses, latest?.let { it.status == AttendanceSheetStatus.APPROVED }, latest?.outdatedAt != null),
            counts = MonthCountsResponse(
                total = rows.size,
                notSubmitted = statuses.count { it == CardStatus.NOT_STARTED || it == CardStatus.DRAFT },
                submitted = statuses.count { it == CardStatus.SUBMITTED },
                returned = statuses.count { it == CardStatus.RETURNED },
                approved = statuses.count { it == CardStatus.APPROVED }
            ),
            employees = rows,
            sheet = latest?.let(::sheetResponse),
            sheetHistory = sheets.drop(1).map(::sheetResponse)
        )
    }

    @Transactional(readOnly = true)
    fun cardDetail(studioId: StudioId, callerId: UserId, yearMonth: YearMonth, userId: UserId): CardDetailResponse {
        val month = loadMonth(studioId, yearMonth)
        val member = month.members.firstOrNull { it.user.id == userId.value }
            ?: memberOutsideList(month, userId)
        val row = row(month, member, callerId)
        val calendar = calendarOf(month, member)
        val period = member.period
        return CardDetailResponse(
            userId = row.userId,
            employeeId = row.employeeId,
            name = row.name,
            status = row.status,
            totalMinutes = row.totalMinutes,
            expectedMinutes = row.expectedMinutes,
            missingWorkingDays = row.missingWorkingDays,
            overtimeMinutes = row.overtimeMinutes,
            leaveWorkingDays = row.leaveWorkingDays,
            submittedAt = row.submittedAt,
            approvedAt = row.approvedAt,
            approvedByName = row.approvedByName,
            returnNote = row.returnNote,
            canDecide = row.canDecide,
            remindedAt = row.remindedAt,
            period = yearMonth.toString(),
            label = yearMonth.toPolishLabel(),
            days = calendar.days.map { it.toResponse() },
            returnedAt = period?.returnedAt?.toString(),
            returnedByName = period?.returnedBy?.let { month.userName(it) }
        )
    }

    /**
     * Ile czeka na wywołującego: karty złożone (poza jego własną) ze wszystkich miesięcy
     * oraz miesiące do podpisu — gotowe, wymagające ponownego podpisu albo z listą
     * wygenerowaną, ale niepodpisaną.
     */
    @Transactional(readOnly = true)
    fun pendingCount(studioId: StudioId, callerId: UserId): PendingCountResponse {
        val submittedCards = periodRepository.findByStudioIdAndStatus(studioId.value, PeriodStatus.SUBMITTED)
            .count { it.userId != callerId.value }

        val currentMonth = YearMonth.now(clock)
        val periods = periodRepository.findByStudioIdOrderByUserIdAndPeriodDesc(studioId.value)
        val sheetsByMonth = attendanceSheetRepository.findAllOfStudio(studioId.value).groupBy { it.period }
        val months = (periods.map { it.period } + sheetsByMonth.keys)
            .mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }
            .filter { !it.isAfter(currentMonth) }
            .toSet()
        if (months.isEmpty()) return PendingCountResponse(submittedCards, 0)

        val users = userRepository.findByStudioId(studioId.value).associateBy { it.id }
        val tracked = trackedUsers(studioId, users.values)
        val periodsByMonth = periods.groupBy { it.period }

        val sheetsToSign = months.count { yearMonth ->
            val monthPeriods = periodsByMonth[yearMonth.toString()].orEmpty().associateBy { it.userId }
            val memberIds = tracked.filter { createdBefore(it, yearMonth) }.map { it.id }.toSet() +
                monthPeriods.keys.filter { users[it]?.isOwner == false }
            // Wpisy nie zmieniają etapu (NOT_STARTED i DRAFT są tak samo „niezłożone"),
            // więc licznik obchodzi się bez czytania wpisów każdego miesiąca.
            val statuses = memberIds.map { cardStatus(monthPeriods[it], hasEntries = false) }
            val latest = sheetsByMonth[yearMonth.toString()]?.maxByOrNull { it.createdAt }
            val stage = stageOf(statuses, latest?.let { it.status == AttendanceSheetStatus.APPROVED }, latest?.outdatedAt != null)
            stage == MonthStage.READY_TO_SIGN || stage == MonthStage.NEEDS_RESIGN ||
                latest?.status == AttendanceSheetStatus.GENERATED
        }
        return PendingCountResponse(submittedCards = submittedCards, sheetsToSign = sheetsToSign)
    }

    // ── Decyzje ───────────────────────────────────────────────────────────────

    @Transactional
    fun approveCard(studioId: StudioId, callerId: UserId, callerName: String?, yearMonth: YearMonth, userId: UserId): MonthCardRowResponse {
        workTimeService.approvePeriod(userId, studioId, yearMonth, callerId, callerName)
        return rowOf(studioId, callerId, yearMonth, userId)
    }

    @Transactional
    fun returnCard(
        studioId: StudioId,
        callerId: UserId,
        callerName: String?,
        yearMonth: YearMonth,
        userId: UserId,
        note: String?
    ): MonthCardRowResponse {
        workTimeService.returnPeriod(userId, studioId, yearMonth, callerId, note, callerName)
        return rowOf(studioId, callerId, yearMonth, userId)
    }

    /**
     * Zatwierdza z zaznaczonych tylko karty złożone, o których wywołujący może zdecydować.
     * Reszta wraca w `skipped` z powodem — zaznaczenie „wszystkich" nie może ani zatwierdzić
     * własnej karty, ani wywrócić całej operacji na jednej niezłożonej.
     */
    @Transactional
    fun bulkApprove(
        studioId: StudioId,
        callerId: UserId,
        callerName: String?,
        yearMonth: YearMonth,
        userIds: List<String>
    ): BulkApproveResponse {
        val approved = mutableListOf<String>()
        val skipped = mutableListOf<SkippedCardResponse>()
        userIds.distinct().forEach { raw ->
            val userId = parseUserId(raw) ?: run {
                skipped += SkippedCardResponse(raw, "Nieprawidłowy identyfikator osoby.")
                return@forEach
            }
            if (userId == callerId) {
                skipped += SkippedCardResponse(raw, "Własnej karty nie zatwierdzasz (zasada czterech oczu).")
                return@forEach
            }
            val period = periodRepository.findByUserIdAndStudioIdAndPeriod(userId.value, studioId.value, yearMonth.toString())
            if (period?.status != PeriodStatus.SUBMITTED) {
                skipped += SkippedCardResponse(raw, notSubmittedReason(period))
                return@forEach
            }
            workTimeService.approvePeriod(userId, studioId, yearMonth, callerId, callerName)
            approved += raw
        }
        return BulkApproveResponse(approved = approved, skipped = skipped)
    }

    /**
     * Push „Uzupełnij i złóż kartę" do osób, których karta nie jest złożona. Ta sama osoba
     * najwyżej raz na [REMIND_INTERVAL] — przypomnienie co pięć minut przestaje być
     * przypomnieniem, a staje się hałasem, który ludzie wyciszają razem z resztą powiadomień.
     */
    @Transactional
    fun remind(studioId: StudioId, callerId: UserId, yearMonth: YearMonth, userIds: List<String>): RemindResponse {
        val month = loadMonth(studioId, yearMonth)
        val now = Instant.now(clock)
        val reminded = mutableListOf<String>()
        val skipped = mutableListOf<SkippedCardResponse>()
        userIds.distinct().forEach { raw ->
            val userId = parseUserId(raw) ?: run {
                skipped += SkippedCardResponse(raw, "Nieprawidłowy identyfikator osoby.")
                return@forEach
            }
            val member = month.members.firstOrNull { it.user.id == userId.value }
            val reason = when {
                member == null -> "Ta osoba nie prowadzi karty czasu pracy w tym miesiącu."
                userId == callerId -> "To Twoja własna karta."
                !member.user.isActive -> "Konto tej osoby jest nieaktywne."
                else -> when (cardStatus(member.period, member.entries.isNotEmpty())) {
                    CardStatus.SUBMITTED -> "Karta jest już złożona."
                    CardStatus.APPROVED -> "Karta jest już zatwierdzona."
                    else -> null
                }
            }
            if (reason != null) {
                skipped += SkippedCardResponse(raw, reason)
                return@forEach
            }
            val period = member!!.period ?: workTimeService.ensurePeriodExists(userId.value, studioId.value, yearMonth)
            if (periodRepository.markReminded(period.id, now, now.minus(REMIND_INTERVAL)) == 0) {
                skipped += SkippedCardResponse(raw, "Przypomnienie wysłano mniej niż 12 godzin temu.")
                return@forEach
            }
            eventPublisher.publishEvent(WorkTimeCardReminderEvent(studioId, userId, yearMonth))
            reminded += raw
        }
        return RemindResponse(reminded = reminded, skipped = skipped)
    }

    // ── Lista obecności ───────────────────────────────────────────────────────

    /**
     * Lista obecności z zatwierdzonych kart miesiąca.
     *
     * Niezatwierdzona karta nie trafia na listę pod podpis nigdy. Gdy takie są, a
     * [allowIncomplete] jest false — 409 z nazwiskami; przy true pominięci trafiają do
     * stopki PDF i do `excludedNames`, żeby lista nie udawała kompletnej.
     *
     * Niepodpisana lista tego miesiąca jest zastępowana (usuwana dopiero PO zapisaniu
     * nowej — awaria w połowie nie zostawi miesiąca bez listy). Podpisana zostaje jako
     * historia: to dokument, nie szkic.
     */
    suspend fun generateSheet(
        studioId: StudioId,
        callerId: UserId,
        callerName: String,
        yearMonth: YearMonth,
        allowIncomplete: Boolean
    ): MonthSheetResponse {
        val month = loadMonth(studioId, yearMonth)
        val byStatus = month.members.groupBy { cardStatus(it.period, it.entries.isNotEmpty()) == CardStatus.APPROVED }
        val approvedMembers = byStatus[true].orEmpty()
        val excluded = byStatus[false].orEmpty()
        val excludedNames = excluded.map { it.name }

        if (excluded.isNotEmpty() && !allowIncomplete) throw IncompleteAttendanceSheetException(excludedNames)
        if (approvedMembers.isEmpty()) {
            throw ConflictException(
                "Za ${yearMonth.toPolishLabelInSentence()} nie ma jeszcze żadnej zatwierdzonej karty — listy nie ma z czego utworzyć."
            )
        }

        val sheet = attendanceSheetService.generateFromCards(
            studioId = studioId,
            userId = callerId,
            userName = callerName,
            period = yearMonth,
            userIds = approvedMembers.map { UserId(it.user.id) },
            employeeIds = approvedMembers.mapNotNull { member -> member.employee?.let { EmployeeId(it.id) } },
            excludedNames = excludedNames
        )

        month.sheets
            .filter { it.status == AttendanceSheetStatus.GENERATED }
            .forEach { previous ->
                runCatching { attendanceSheetService.deleteIfUnsigned(studioId, callerId, callerName, previous.id) }
                    .onFailure { logger.warn("Could not replace attendance sheet {} [period={}]", previous.id, yearMonth, it) }
            }

        return sheetResponse(sheet)
    }

    // ── Składanie wiersza ─────────────────────────────────────────────────────

    /** Osoba z kartą w miesiącu: konto, rekord pracownika (o ile jest), karta i wpisy. */
    private class Member(
        val user: UserEntity,
        val employee: EmployeeEntity?,
        val period: WorkTimePeriodEntity?,
        val entries: List<WorkTimeEntryEntity>
    ) {
        val firstName: String get() = employee?.firstName ?: user.firstName
        val lastName: String get() = employee?.lastName ?: user.lastName
        val name: String get() = "$firstName $lastName".trim()
    }

    private inner class Month(
        val studioId: StudioId,
        val yearMonth: YearMonth,
        val members: List<Member>,
        val sheets: List<AttendanceSheetEntity>,
        val usersById: Map<UUID, UserEntity>,
        val leavesByEmployee: Map<UUID, List<pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity>>
    ) {
        fun userName(userId: UUID): String? = usersById[userId]?.let { "${it.firstName} ${it.lastName}".trim() }
    }

    private fun loadMonth(studioId: StudioId, yearMonth: YearMonth): Month {
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        val users = userRepository.findByStudioId(studioId.value).associateBy { it.id }
        val periods = periodRepository.findByStudioIdAndPeriod(studioId.value, yearMonth.toString()).associateBy { it.userId }
        val entries = entryRepository.findByStudioIdAndDateBetween(studioId.value, from, to).groupBy { it.userId }
        val employees = employeeRepository.findByStudioId(studioId.value)
            .filter { it.userId != null }
            .associateBy { it.userId!! }

        val memberIds = trackedUsers(studioId, users.values).filter { createdBefore(it, yearMonth) }.map { it.id } +
            periods.keys + entries.keys
        val collator = Collator.getInstance(POLISH)
        val members = memberIds.distinct()
            .mapNotNull { users[it] }
            .filterNot { it.isOwner }
            .map { user -> Member(user, employees[user.id], periods[user.id], entries[user.id].orEmpty()) }
            .sortedWith { a, b ->
                collator.compare(a.lastName, b.lastName).takeIf { it != 0 } ?: collator.compare(a.firstName, b.firstName)
            }

        return Month(
            studioId = studioId,
            yearMonth = yearMonth,
            members = members,
            sheets = attendanceSheetRepository.findByStudioIdAndPeriod(studioId.value, yearMonth.toString()),
            usersById = users,
            leavesByEmployee = employeeLeaveRepository.findOverlappingRange(studioId.value, from, to).groupBy { it.employeeId }
        )
    }

    /** Karta osoby spoza listy miesiąca (np. konto bez roli liczącej czas) — pusta, ale czytelna. */
    private fun memberOutsideList(month: Month, userId: UserId): Member {
        val user = month.usersById[userId.value]?.takeUnless { it.isOwner }
            ?: throw EntityNotFoundException("Nie znaleziono karty czasu pracy tej osoby.")
        return Member(user, employeeRepository.findByStudioIdAndUserId(month.studioId.value, user.id), null, emptyList())
    }

    private fun rowOf(studioId: StudioId, callerId: UserId, yearMonth: YearMonth, userId: UserId): MonthCardRowResponse {
        val month = loadMonth(studioId, yearMonth)
        val member = month.members.firstOrNull { it.user.id == userId.value } ?: memberOutsideList(month, userId)
        return row(month, member, callerId)
    }

    private fun calendarOf(month: Month, member: Member): WorkTimeCalendar.Month =
        WorkTimeCalendar.month(
            yearMonth = month.yearMonth,
            entries = member.entries,
            leaveByDay = WorkTimeCalendar.leaveByDay(
                month.yearMonth,
                member.employee?.let { month.leavesByEmployee[it.id] }.orEmpty()
            ),
            today = LocalDate.now(clock)
        )

    private fun row(month: Month, member: Member, callerId: UserId): MonthCardRowResponse {
        val calendar = calendarOf(month, member)
        val period = member.period
        val status = cardStatus(period, member.entries.isNotEmpty())
        val approved = status == CardStatus.APPROVED
        return MonthCardRowResponse(
            userId = member.user.id.toString(),
            employeeId = member.employee?.id?.toString(),
            name = member.name,
            status = status,
            totalMinutes = calendar.totalMinutes,
            expectedMinutes = calendar.expectedMinutes,
            missingWorkingDays = calendar.missingWorkingDays,
            overtimeMinutes = calendar.overtimeMinutes,
            leaveWorkingDays = calendar.leaveWorkingDays,
            submittedAt = period?.submittedAt?.toString(),
            approvedAt = period?.approvedAt?.takeIf { approved }?.toString(),
            approvedByName = period?.approvedBy?.takeIf { approved }?.let { month.userName(it) },
            returnNote = period?.visibleReturnNote(),
            canDecide = canDecide(callerId, member.user.id, status),
            remindedAt = period?.remindedAt?.toString()
        )
    }

    private fun sheetResponse(sheet: AttendanceSheetEntity) = MonthSheetResponse(
        id = sheet.id.toString(),
        status = sheet.status.name,
        outdated = sheet.outdatedAt != null,
        generatedAt = sheet.createdAt.toString(),
        approvedAt = sheet.approvedAt?.toString(),
        approvedByName = sheet.approvedByName,
        excludedNames = attendanceSheetService.excludedNamesOf(sheet)
    )

    /** Aktywne konta (bez właścicieli), których rola liczy czas pracy — dziś. */
    private fun trackedUsers(studioId: StudioId, users: Collection<UserEntity>): List<UserEntity> {
        val trackedRoleIds = roleRepository.findByStudioId(studioId.value).filter { it.trackWorkTime }.map { it.id }.toSet()
        if (trackedRoleIds.isEmpty()) return emptyList()
        return users.filter { it.isActive && !it.isOwner && it.customRoleId in trackedRoleIds }
    }

    /** Konto założone po miesiącu nie ma za niego karty — w przeszłych miesiącach nie „brakuje" go. */
    private fun createdBefore(user: UserEntity, yearMonth: YearMonth): Boolean =
        user.createdAt.isBefore(yearMonth.plusMonths(1).atDay(1).atStartOfDay(WARSAW).toInstant())

    private fun parseUserId(raw: String): UserId? = runCatching { UserId(UUID.fromString(raw.trim())) }.getOrNull()

    private fun notSubmittedReason(period: WorkTimePeriodEntity?): String = when (period?.status) {
        PeriodStatus.APPROVED -> "Karta jest już zatwierdzona."
        PeriodStatus.RETURNED -> "Karta jest zwrócona do poprawy."
        else -> "Karta nie została złożona."
    }
}
