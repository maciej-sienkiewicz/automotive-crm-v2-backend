package pl.detailing.crm.support.reportproblem

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ValidationException

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class ReportProblemResponse(
    val success: Boolean
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * "Zgłoś problem" — lets any authenticated user report a bug/issue straight from
 * the app. The report (description + optional screenshots/attachments) is emailed
 * to the support address configured via `support.report.recipient-email`; nothing
 * is persisted.
 */
@RestController
@RequestMapping("/api/v1/support/report-problem")
class ReportProblemController(
    private val reportProblemService: ReportProblemService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun reportProblem(
        @RequestParam("description") description: String,
        @RequestParam("files", required = false) files: List<MultipartFile>?
    ): ResponseEntity<ReportProblemResponse> {
        val user = SecurityContextHelper.getCurrentUser()
        val trimmedDescription = description.trim()

        if (trimmedDescription.isBlank()) {
            throw ValidationException("Opis problemu jest wymagany")
        }
        if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
            throw ValidationException("Opis problemu jest zbyt długi (maks. $MAX_DESCRIPTION_LENGTH znaków)")
        }

        val attachments = (files ?: emptyList()).filter { !it.isEmpty }
        if (attachments.size > MAX_ATTACHMENTS) {
            throw ValidationException("Można załączyć maksymalnie $MAX_ATTACHMENTS plików")
        }
        attachments.forEach { file ->
            if (file.size > MAX_ATTACHMENT_SIZE) {
                throw ValidationException(
                    "Plik \"${file.originalFilename}\" przekracza dozwolony rozmiar (maks. ${MAX_ATTACHMENT_SIZE / (1024 * 1024)}MB)"
                )
            }
        }

        reportProblemService.sendReport(
            reportedBy = user,
            description = trimmedDescription,
            attachments = attachments
        )

        logger.info("Problem report submitted [studioId={}, userId={}, attachments={}]", user.studioId, user.userId, attachments.size)
        return ResponseEntity.status(HttpStatus.OK).body(ReportProblemResponse(success = true))
    }

    companion object {
        private const val MAX_DESCRIPTION_LENGTH = 5000
        private const val MAX_ATTACHMENTS = 5
        private const val MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024 // 10MB
    }
}
