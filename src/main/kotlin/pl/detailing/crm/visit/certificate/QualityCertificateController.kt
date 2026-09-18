package pl.detailing.crm.visit.certificate

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.util.UUID

/**
 * Certyfikat jakości wizyty.
 *
 * POST, a nie GET, mimo że nic nie zapisuje: treść dokumentu zależy od wyboru
 * pracownika (usługi, produkty, zalecenia z notatkami), a to nie mieści się w adresie.
 */
@RestController
@RequestMapping("/api/visits/{visitId}/quality-certificate")
class QualityCertificateController(
    private val service: QualityCertificateService
) {
    @PostMapping
    @RequiresPermission(Permission.VISITS_VIEW)
    fun generate(
        @PathVariable visitId: String,
        @RequestBody request: GenerateQualityCertificateRequest
    ): ResponseEntity<ByteArray> {
        val principal = SecurityContextHelper.getCurrentUser()
        val file = service.generate(
            studioId = principal.studioId,
            userId = principal.userId,
            userFullName = principal.fullName,
            visitId = UUID.fromString(visitId),
            request = request
        )
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${file.fileName}\"")
            .body(file.bytes)
    }
}
