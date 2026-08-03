package pl.detailing.crm.worktime

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

// ── Self-service endpoints (employee's own time) ─────────────────────────────

@RestController
@RequestMapping("/api/v1/my/worktime")
class MyWorkTimeController(private val workTimeService: WorkTimeService) {

    @GetMapping("/periods")
    fun listMyPeriods(): ResponseEntity<List<PeriodSummaryResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        return ResponseEntity.ok(workTimeService.listPeriodSummaries(principal.userId, principal.studioId))
    }

    @GetMapping("/periods/{period}")
    fun getMyPeriod(@PathVariable period: String): ResponseEntity<PeriodDetailResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        val yearMonth = parsePeriod(period)
        return ResponseEntity.ok(workTimeService.getPeriodDetail(principal.userId, principal.studioId, yearMonth))
    }

    @PutMapping("/entries/{date}")
    fun upsertEntry(
        @PathVariable date: String,
        @RequestBody body: UpsertEntryRequest
    ): ResponseEntity<EntryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        val localDate = parseDate(date)
        if (body.minutes < 0 || body.minutes > 1440) throw ValidationException("Czas pracy musi być między 0 a 1440 minut (24h)")

        val periodEntity = workTimeService.getPeriodOrNull(principal.userId.value, localDate.yearMonth())
        if (periodEntity?.status == PeriodStatus.APPROVED) {
            throw ForbiddenException("Karta jest zaakceptowana — edycja niemożliwa do czasu zwrotu do poprawy")
        }

        val entry = workTimeService.upsertEntry(principal.userId, principal.studioId, localDate, body.minutes, body.note)
        return ResponseEntity.ok(entry)
    }

    @DeleteMapping("/entries/{date}")
    fun deleteEntry(@PathVariable date: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        val localDate = parseDate(date)

        val periodEntity = workTimeService.getPeriodOrNull(principal.userId.value, localDate.yearMonth())
        if (periodEntity?.status == PeriodStatus.APPROVED) {
            throw ForbiddenException("Karta jest zaakceptowana — edycja niemożliwa do czasu zwrotu do poprawy")
        }

        workTimeService.deleteEntry(principal.userId, principal.studioId, localDate)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/periods/{period}/fill-month")
    fun fillMonth(@PathVariable period: String): ResponseEntity<PeriodDetailResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        val yearMonth = parsePeriod(period)

        val periodEntity = workTimeService.getPeriodOrNull(principal.userId.value, yearMonth)
        if (periodEntity?.status == PeriodStatus.APPROVED) {
            throw ForbiddenException("Karta jest zaakceptowana — edycja niemożliwa do czasu zwrotu do poprawy")
        }

        return ResponseEntity.ok(
            workTimeService.fillMonth(principal.userId, principal.studioId, yearMonth)
        )
    }

    @PostMapping("/today")
    fun standardToday(): ResponseEntity<EntryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        val today = LocalDate.now()

        val periodEntity = workTimeService.getPeriodOrNull(principal.userId.value, today.yearMonth())
        if (periodEntity?.status == PeriodStatus.APPROVED) {
            throw ForbiddenException("Karta jest zaakceptowana — edycja niemożliwa do czasu zwrotu do poprawy")
        }

        val entry = workTimeService.upsertEntry(principal.userId, principal.studioId, today, 480, null)
        return ResponseEntity.ok(entry)
    }

    @PostMapping("/periods/{period}/submit")
    fun submitPeriod(@PathVariable period: String): ResponseEntity<PeriodSummaryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireTrackWorkTime(principal)
        val yearMonth = parsePeriod(period)
        return ResponseEntity.ok(workTimeService.submitPeriod(principal.userId, principal.studioId, yearMonth))
    }

    private fun requireTrackWorkTime(principal: pl.detailing.crm.auth.UserPrincipal) {
        if (principal.isOwner) throw ForbiddenException("Właściciele nie prowadzą własnej karty czasu pracy")
        if (!workTimeService.hasTrackWorkTime(principal.userId, principal.studioId)) {
            throw ForbiddenException("Śledzenie czasu pracy nie jest włączone dla Twojej roli")
        }
    }
}

// ── Manager endpoints (EMPLOYEES_MANAGE) ─────────────────────────────────────

@RequiresPermission(Permission.EMPLOYEES_MANAGE)
@RestController
@RequestMapping("/api/v1/worktime/team")
class TeamWorkTimeController(private val workTimeService: WorkTimeService) {

    @GetMapping("/{userId}/periods")
    fun listUserPeriods(@PathVariable userId: String): ResponseEntity<List<PeriodSummaryResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val targetUserId = UserId.fromString(userId)
        return ResponseEntity.ok(workTimeService.listPeriodSummaries(targetUserId, principal.studioId))
    }

    @GetMapping("/{userId}/periods/{period}")
    fun getUserPeriod(
        @PathVariable userId: String,
        @PathVariable period: String
    ): ResponseEntity<PeriodDetailResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val targetUserId = UserId.fromString(userId)
        val yearMonth = parsePeriod(period)
        return ResponseEntity.ok(workTimeService.getPeriodDetail(targetUserId, principal.studioId, yearMonth))
    }

    @PostMapping("/{userId}/periods/{period}/approve")
    fun approvePeriod(
        @PathVariable userId: String,
        @PathVariable period: String
    ): ResponseEntity<PeriodSummaryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val targetUserId = UserId.fromString(userId)
        val yearMonth = parsePeriod(period)
        return ResponseEntity.ok(
            workTimeService.approvePeriod(targetUserId, principal.studioId, yearMonth, principal.userId)
        )
    }

    @PostMapping("/{userId}/periods/{period}/return")
    fun returnPeriod(
        @PathVariable userId: String,
        @PathVariable period: String,
        @RequestBody body: ReturnPeriodRequest
    ): ResponseEntity<PeriodSummaryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val targetUserId = UserId.fromString(userId)
        val yearMonth = parsePeriod(period)
        return ResponseEntity.ok(
            workTimeService.returnPeriod(targetUserId, principal.studioId, yearMonth, principal.userId, body.note)
        )
    }
}

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class UpsertEntryRequest(
    val minutes: Int,
    val note: String? = null
)

data class ReturnPeriodRequest(
    val note: String? = null
)

data class EntryResponse(
    val date: String,
    val minutes: Int,
    val hours: String,
    val note: String?
)

data class PeriodSummaryResponse(
    val period: String,
    val label: String,
    val status: String,
    val totalMinutes: Int,
    val totalHours: String,
    val entryCount: Int,
    val overtimeMinutes: Int,
    val overtimeHours: String,
    val returnNote: String?
)

data class PeriodDetailResponse(
    val period: String,
    val label: String,
    val status: String,
    val totalMinutes: Int,
    val totalHours: String,
    val entryCount: Int,
    val overtimeMinutes: Int,
    val overtimeHours: String,
    val returnNote: String?,
    val entries: List<EntryResponse>
)

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun parsePeriod(period: String): YearMonth =
    try { YearMonth.parse(period) }
    catch (e: DateTimeParseException) { throw ValidationException("Nieprawidłowy format okresu: '$period' (oczekiwany YYYY-MM)") }

private fun parseDate(date: String): LocalDate =
    try { LocalDate.parse(date) }
    catch (e: DateTimeParseException) { throw ValidationException("Nieprawidłowy format daty: '$date' (oczekiwany YYYY-MM-DD)") }

private fun LocalDate.yearMonth(): YearMonth = YearMonth.from(this)
