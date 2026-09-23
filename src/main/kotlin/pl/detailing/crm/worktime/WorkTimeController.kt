package pl.detailing.crm.worktime

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.pii.Pii
import pl.detailing.crm.signing.SignatureRequestDto
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.infrastructure.SignatureEventPublisher
import pl.detailing.crm.signing.toDto
import pl.detailing.crm.worktime.attendance.AttendanceSheetEntity
import pl.detailing.crm.worktime.attendance.AttendanceSheetRemoteSigning
import pl.detailing.crm.worktime.attendance.AttendanceSheetService
import pl.detailing.crm.worktime.attendance.AttendanceSheetStatus
import pl.detailing.crm.worktime.attendance.AttendanceSigningOptions
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

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
class TeamWorkTimeController(
    private val workTimeService: WorkTimeService,
    private val attendanceSheetService: AttendanceSheetService,
    private val remoteSigning: AttendanceSheetRemoteSigning,
    private val signatureEventPublisher: SignatureEventPublisher
) {

    /**
     * Lista obecności na wskazany miesiąc dla zaznaczonych pracowników.
     *
     * Zwraca OPIS dokumentu, a nie jego bajty: arkusz ląduje w zakładce Rozliczenia,
     * gdzie każdy administrator widzi go razem ze stanem (do zatwierdzenia / zatwierdzona).
     * Sam plik idzie osobnym GET-em — do podglądu albo pobrania.
     *
     * POST, a nie GET, bo lista pracowników bywa długa i nie ma po co lądować
     * w logach serwera ani w historii przeglądarki.
     */
    @PostMapping("/attendance-sheet")
    fun generateAttendanceSheet(
        @RequestBody body: AttendanceSheetRequest
    ): ResponseEntity<AttendanceSheetResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val sheet = attendanceSheetService.generate(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            period = parsePeriod(body.period),
            employeeIds = body.employeeIds.map { EmployeeId.fromString(it) }
        )
        ResponseEntity.ok(sheet.toResponse())
    }

    /**
     * Zatwierdzenie rozliczenia — opcjonalnie z podpisem złożonym na tym urządzeniu.
     * Kto zatwierdza, wynika z sesji, jak przy podpisie.
     */
    @PostMapping("/attendance-sheet/{sheetId}/approve")
    fun approveAttendanceSheet(
        @PathVariable sheetId: String,
        @RequestBody(required = false) body: ApproveAttendanceSheetRequest?
    ): ResponseEntity<AttendanceSheetResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val sheet = attendanceSheetService.approve(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            sheetId = UUID.fromString(sheetId),
            signatureDataUrl = body?.signatureImage?.ifBlank { null }
        )
        ResponseEntity.ok(sheet.toResponse())
    }

    /** Usunięcie rozliczenia razem z plikami arkusza. */
    @DeleteMapping("/attendance-sheet/{sheetId}")
    fun deleteAttendanceSheet(@PathVariable sheetId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        attendanceSheetService.delete(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            sheetId = UUID.fromString(sheetId)
        )
        ResponseEntity.noContent().build()
    }

    /** Plik arkusza — podpisany, jeśli podpis już złożono. */
    @GetMapping("/attendance-sheet/{sheetId}/file")
    fun downloadAttendanceSheet(@PathVariable sheetId: String): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val (sheet, bytes) = attendanceSheetService.download(
            principal.studioId, UUID.fromString(sheetId)
        )

        val suffix = if (sheet.signedFileS3Key != null) "-podpisana" else ""
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_PDF
        headers.setContentDispositionFormData("attachment", "lista-obecnosci-${sheet.period}$suffix.pdf")
        ResponseEntity.ok().headers(headers).body(bytes)
    }

    /**
     * Podpis złożony na TYM urządzeniu — kanwa w przeglądarce oddaje PNG z kanałem alfa.
     *
     * Kto podpisał, wynika z sesji: podpisuje osoba zalogowana, więc nazwiska nie
     * przyjmujemy z żądania — inaczej dokument mógłby wskazać kogokolwiek.
     */
    @PostMapping("/attendance-sheet/{sheetId}/sign")
    fun signAttendanceSheet(
        @PathVariable sheetId: String,
        @RequestBody body: SignAttendanceSheetRequest
    ): ResponseEntity<AttendanceSheetResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val sheet = attendanceSheetService.sign(
            studioId = principal.studioId,
            userId = principal.userId,
            signerName = principal.fullName,
            sheetId = UUID.fromString(sheetId),
            signatureDataUrl = body.signatureImage
        )
        ResponseEntity.ok(sheet.toResponse())
    }

    // ── Podpis na tablecie studia albo na własnym telefonie ─────────────────────

    /** Czym można poprosić o podpis listy: sparowane tablety studia i własny numer (zamaskowany). */
    @GetMapping("/attendance-sheet/signing-options")
    fun attendanceSigningOptions(): ResponseEntity<AttendanceSigningOptions> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(remoteSigning.options(principal.studioId, principal.userId))
    }

    /**
     * „Wyświetl podpis na tablecie" (channel=TABLET) albo „Wyślij podpis na mój numer
     * telefonu" (channel=SMS). Podpisuje osoba zalogowana: imię, nazwisko i numer biorą się
     * z jej konta, nie z żądania. Po podpisie lista jest zatwierdzona.
     */
    @PostMapping("/attendance-sheet/{sheetId}/signature-requests")
    fun requestAttendanceSignature(
        @PathVariable sheetId: String,
        @RequestBody body: AttendanceSignatureRequestBody,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignatureRequestDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val channel = when (body.channel.trim().uppercase()) {
            "TABLET" -> SignatureChannel.TABLET
            "SMS", "SMS_LINK" -> SignatureChannel.SMS_LINK
            else -> throw ValidationException("Nieznany sposób podpisu: ${body.channel}")
        }
        val request = remoteSigning.request(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            sheetId = UUID.fromString(sheetId),
            channel = channel,
            tabletId = body.tabletId,
            ipAddress = clientIp(httpRequest)
        )
        // Dopiero po zatwierdzeniu transakcji: tablet odpowiada na to zdarzenie od razu
        // pobraniem kolejki i nie może trafić na jeszcze niezapisane żądanie
        // (patrz SignatureRequestController.requestSignature).
        signatureEventPublisher.publish(
            tenantId = principal.studioId.value.toString(),
            requestId = request.id.toString(),
            eventType = "SIGNATURE_REQUESTED",
            tabletId = request.tabletId,
            documentName = request.documentName,
            signerName = request.signerName,
            status = request.status.name
        )
        ResponseEntity.status(HttpStatus.CREATED).body(request.toDto())
    }

    /** Najnowsza prośba o podpis tej listy (także zakończona); 204, gdy nie było żadnej. */
    @GetMapping("/attendance-sheet/{sheetId}/signature-request")
    fun latestAttendanceSignature(@PathVariable sheetId: String): ResponseEntity<SignatureRequestDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val request = remoteSigning.latest(principal.studioId, UUID.fromString(sheetId))
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(request.toDto())
    }

    /** Anuluje czekającą prośbę o podpis; 204, gdy żadna nie czekała. */
    @DeleteMapping("/attendance-sheet/{sheetId}/signature-request")
    fun cancelAttendanceSignature(
        @PathVariable sheetId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignatureRequestDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val cancelled = remoteSigning.cancel(
            principal.studioId, UUID.fromString(sheetId), principal.userId, principal.fullName, clientIp(httpRequest)
        ) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(cancelled.toDto())
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr

    /** Historia wygenerowanych arkuszy — od najnowszych. */
    @GetMapping("/attendance-sheets")
    fun listAttendanceSheets(
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<List<AttendanceSheetResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            attendanceSheetService.history(principal.studioId, limit).map { it.toResponse() }
        )
    }

    private fun AttendanceSheetEntity.toResponse() = AttendanceSheetResponse(
        id = id.toString(),
        period = period,
        employeeCount = attendanceSheetService.employeeIdsOf(this).size,
        signed = signedFileS3Key != null,
        signerName = signerName,
        signedAt = signedAt?.toEpochMilli(),
        createdAt = createdAt.toEpochMilli(),
        status = status,
        createdByName = createdByName,
        approvedAt = approvedAt?.toEpochMilli(),
        approvedByName = approvedByName
    )

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

/**
 * @param period miesiąc w formacie YYYY-MM
 * @param employeeIds identyfikatory PRACOWNIKÓW (nie kont) zaznaczonych na liście
 */
data class AttendanceSheetRequest(
    val period: String,
    val employeeIds: List<String> = emptyList()
)

/** @param signatureImage podpis z kanwy jako `data:image/png;base64,...` */
data class SignAttendanceSheetRequest(
    val signatureImage: String
)

/**
 * @param channel TABLET albo SMS (na numer z konta osoby zalogowanej)
 * @param tabletId wybrany tablet; null = dowolny sparowany tablet studia
 */
data class AttendanceSignatureRequestBody(
    val channel: String,
    val tabletId: String? = null
)

/** @param signatureImage opcjonalny podpis z kanwy — bez niego lista jest tylko zatwierdzana */
data class ApproveAttendanceSheetRequest(
    val signatureImage: String? = null
)

data class AttendanceSheetResponse(
    val id: String,
    val period: String,
    val employeeCount: Int,
    val signed: Boolean,
    /** Imię i nazwisko osoby, która podpisała — maskowane tak jak przy podpisach protokołów. */
    @Pii val signerName: String?,
    val signedAt: Long?,
    val createdAt: Long,
    val status: AttendanceSheetStatus,
    /** Kto wygenerował listę; null przy liście, której autora nie da się już ustalić. */
    @Pii val createdByName: String?,
    val approvedAt: Long?,
    @Pii val approvedByName: String?
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
