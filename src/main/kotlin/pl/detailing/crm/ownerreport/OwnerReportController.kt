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
import pl.detailing.crm.ownerreport.domain.ReportFrequency
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportSettingsEntity
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportSettingsRepository
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate

data class OwnerReportSettingsResponse(val frequency: ReportFrequency)

data class UpdateOwnerReportSettingsRequest(val frequency: ReportFrequency)

/**
 * Raport właściciela: podgląd PDF na żądanie i ustawienie wysyłki mailem.
 *
 * Raport niesie koszty i przychód firmy, więc wymaga uprawnienia do raportów
 * finansowych (właściciel ma je zawsze).
 */
@RestController
@RequestMapping("/api/v1/owner-report")
@RequiresPermission(Permission.FINANCE_VIEW_REPORTS)
class OwnerReportController(
    private val service: OwnerReportService,
    private val settingsRepository: OwnerReportSettingsRepository
) {

    /**
     * PDF za okres. Bez parametrów — ostatni pełny tydzień (pon–nd); z `weeks=2` —
     * ostatnie dwa; z `from`/`to` — dowolny okres do [ReportPeriod.MAX_DAYS] dni.
     */
    @GetMapping("/pdf")
    fun pdf(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false, defaultValue = "1") weeks: Int
    ): ResponseEntity<ByteArray> {
        val principal = SecurityContextHelper.getCurrentUser()
        val file = service.generate(principal.studioId, resolvePeriod(from, to, weeks))
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${file.fileName}\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(file.bytes)
    }

    @GetMapping("/settings")
    fun settings(): OwnerReportSettingsResponse {
        val studioId = SecurityContextHelper.getCurrentStudioId()
        val frequency = settingsRepository.findById(studioId.value).map { it.frequency }.orElse(ReportFrequency.OFF)
        return OwnerReportSettingsResponse(frequency)
    }

    /**
     * Raport idzie mailem do właścicieli studia, więc tylko właściciel decyduje,
     * czy i jak często chce go dostawać.
     */
    @PutMapping("/settings")
    fun updateSettings(@RequestBody request: UpdateOwnerReportSettingsRequest): OwnerReportSettingsResponse {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Wysyłkę raportu ustawia właściciel studia")
        }
        val entity = settingsRepository.findById(principal.studioId.value)
            .orElse(OwnerReportSettingsEntity(studioId = principal.studioId.value))
        entity.frequency = request.frequency
        entity.updatedAt = Instant.now()
        settingsRepository.save(entity)
        return OwnerReportSettingsResponse(entity.frequency)
    }

    private fun resolvePeriod(from: LocalDate?, to: LocalDate?, weeks: Int): ReportPeriod {
        val today = LocalDate.now(ReportPeriod.ZONE)
        if (from == null && to == null) {
            if (weeks !in 1..4) throw ValidationException("Raport obejmuje od 1 do 4 tygodni")
            return ReportPeriod.lastFullWeeks(weeks, today)
        }
        if (from == null || to == null) throw ValidationException("Podaj oba końce okresu: from i to")
        if (to.isBefore(from)) throw ValidationException("Koniec okresu jest przed jego początkiem")
        val period = ReportPeriod(from, to)
        if (period.days > ReportPeriod.MAX_DAYS) {
            throw ValidationException("Raport obejmuje najwyżej ${ReportPeriod.MAX_DAYS} dni")
        }
        return period
    }
}
