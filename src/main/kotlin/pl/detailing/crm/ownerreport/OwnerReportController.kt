package pl.detailing.crm.ownerreport

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.ownerreport.domain.ReportComparison
import pl.detailing.crm.ownerreport.domain.ReportFrequency
import pl.detailing.crm.ownerreport.domain.ReportLength
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportNotificationEntity
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportNotificationRepository
import pl.detailing.crm.ownerreport.pdf.ReportFormat
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate

data class ReportPeriodResponse(val from: LocalDate, val to: LocalDate, val label: String)

data class ReportNotificationResponse(val frequency: ReportFrequency)

/** Tabela raportów: dzień założenia konta i okresy od najnowszego, trwający pierwszy. */
data class ReportArchiveResponse(
    val length: ReportLength,
    val comparison: ReportComparison,
    val since: LocalDate,
    val rows: List<ReportArchiveRowResponse>
)

data class ReportArchiveRowResponse(
    val from: LocalDate,
    val to: LocalDate,
    val label: String,
    /** Okres trwa: liczby „do dziś", bez porównania i bez PDF-u. */
    val inProgress: Boolean,
    /** Dzień, od którego raport za ten okres jest do pobrania. */
    val availableOn: LocalDate,
    val closedVisits: Int,
    /** Suma brutto wizyt wydanych w okresie, w groszach — ta sama co „Sprzedaż brutto" w PDF-ie. */
    val salesGrossCents: Long,
    val baselineClosedVisits: Int?,
    val baselineSalesGrossCents: Long?,
    /** „+12%", „-3", „bez zmian", „—" — ten sam napis co w PDF-ie. */
    val closedVisitsChange: String?,
    val salesGrossChange: String?
)

data class UpdateReportNotificationRequest(val frequency: ReportFrequency)

/**
 * Raport właściciela: PDF za pełny okres i powiadomienie „Dostępny nowy raport".
 *
 * Raport mieszka w Statystykach obok „Kosztów" i pokazuje te same kwoty, więc
 * dostęp jest ten sam co do kosztów: statystyki ALBO raporty finansowe.
 */
@RestController
@RequestMapping("/api/v1/owner-report")
@RequiresPermission(Permission.STATISTICS_VIEW, Permission.FINANCE_VIEW_REPORTS)
class OwnerReportController(
    private val service: OwnerReportService,
    private val archiveService: OwnerReportArchiveService,
    private val notificationRepository: OwnerReportNotificationRepository
) {

    /**
     * PDF za pełny okres. Bez `from` — ostatni zakończony okres danej długości;
     * z `from` — okres, który zaczyna się tego dnia (musi być wyrównany i już zakończony).
     */
    @GetMapping("/pdf")
    fun pdf(
        @RequestParam(defaultValue = "WEEK") length: ReportLength,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(defaultValue = "PREVIOUS") compare: ReportComparison
    ): ResponseEntity<ByteArray> {
        val principal = SecurityContextHelper.getCurrentUser()
        val file = service.generate(principal.studioId, resolvePeriod(length, from), compare)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${file.fileName}\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(file.bytes)
    }

    /** Pełne okresy do wyboru, od najnowszego. */
    @GetMapping("/periods")
    fun periods(@RequestParam(defaultValue = "WEEK") length: ReportLength): List<ReportPeriodResponse> =
        ReportPeriod.recentFull(length, today()).map {
            ReportPeriodResponse(it.from, it.to, ReportFormat.range(it.from, it.to))
        }

    /**
     * Tabela raportów: każdy okres od założenia konta, z porównaniem jak w PDF-ie.
     * Trwający okres jest na górze, ale PDF za niego nadal nie istnieje ([pdf] go odrzuci).
     */
    @GetMapping("/archive")
    fun archive(
        @RequestParam(defaultValue = "WEEK") length: ReportLength,
        @RequestParam(defaultValue = "PREVIOUS") compare: ReportComparison
    ): ReportArchiveResponse {
        val principal = SecurityContextHelper.getCurrentUser()
        val archive = archiveService.archive(principal.studioId, length, compare, today())
        return ReportArchiveResponse(
            length = length,
            comparison = compare,
            since = archive.since,
            rows = archive.rows.map { row ->
                ReportArchiveRowResponse(
                    from = row.period.from,
                    to = row.period.to,
                    label = ReportFormat.range(row.period.from, row.period.to),
                    inProgress = row.inProgress,
                    availableOn = row.period.to.plusDays(1),
                    closedVisits = row.closed.count,
                    salesGrossCents = row.closed.grossCents,
                    baselineClosedVisits = row.baseline?.count,
                    baselineSalesGrossCents = row.baseline?.grossCents,
                    closedVisitsChange = row.baseline?.let {
                        ReportFormat.change(row.closed.count.toLong(), it.count.toLong(), ReportFormat.SMALL_COUNT)
                    },
                    salesGrossChange = row.baseline?.let { ReportFormat.change(row.closed.grossCents, it.grossCents) }
                )
            }
        )
    }

    /** Powiadomienie jest ustawieniem osoby, nie studia — trafia na jej telefon. */
    @GetMapping("/notification")
    fun notification(): ReportNotificationResponse {
        val userId = SecurityContextHelper.getCurrentUserId()
        val frequency = notificationRepository.findById(userId.value).map { it.frequency }.orElse(ReportFrequency.OFF)
        return ReportNotificationResponse(frequency)
    }

    @PutMapping("/notification")
    fun updateNotification(@RequestBody request: UpdateReportNotificationRequest): ReportNotificationResponse {
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = notificationRepository.findById(principal.userId.value)
            .orElse(OwnerReportNotificationEntity(userId = principal.userId.value, studioId = principal.studioId.value))
        entity.frequency = request.frequency
        entity.updatedAt = Instant.now()
        notificationRepository.save(entity)
        return ReportNotificationResponse(entity.frequency)
    }

    private fun resolvePeriod(length: ReportLength, from: LocalDate?): ReportPeriod {
        val today = today()
        if (from == null) return ReportPeriod.latestFull(length, today)
        return ReportPeriod.fullStartingOn(length, from, today)
            ?: throw ValidationException("Raport jest dostępny tylko za pełny, zakończony okres")
    }

    private fun today(): LocalDate = LocalDate.now(ReportPeriod.ZONE)
}
