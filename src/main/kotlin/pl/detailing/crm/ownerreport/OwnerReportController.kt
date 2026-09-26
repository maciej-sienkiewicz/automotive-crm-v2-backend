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
