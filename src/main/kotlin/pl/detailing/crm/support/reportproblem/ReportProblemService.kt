package pl.detailing.crm.support.reportproblem

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds and sends the "Zgłoś problem" support email — no persistence, the
 * report only ever exists as an outbound email plus its attachments.
 */
@Service
class ReportProblemService(
    private val emailProvider: EmailProvider,
    private val studioRepository: StudioRepository,
    @Value("\${support.report.recipient-email}") private val recipientEmail: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendReport(reportedBy: UserPrincipal, description: String, attachments: List<MultipartFile>) {
        val studioName = studioRepository.findByStudioId(reportedBy.studioId.value)?.name ?: "(nieznane studio)"
        val timestamp = TIMESTAMP_FORMATTER.format(Instant.now())

        val body = buildString {
            appendLine("Nowe zgłoszenie problemu")
            appendLine("=========================")
            appendLine()
            appendLine("Data zgłoszenia: $timestamp")
            appendLine("Studio: $studioName (id: ${reportedBy.studioId.value})")
            appendLine("Zgłaszający: ${reportedBy.fullName} (${reportedBy.email})")
            appendLine()
            appendLine("Opis problemu:")
            appendLine(description)
            appendLine()
            appendLine("Załączniki: ${if (attachments.isEmpty()) "brak" else attachments.joinToString { it.originalFilename ?: "plik" }}")
        }

        val emailAttachments = attachments.map { file ->
            EmailAttachment(
                fileName = file.originalFilename ?: "zalacznik",
                content = file.bytes,
                contentType = file.contentType ?: "application/octet-stream"
            )
        }

        val result = emailProvider.send(
            to = recipientEmail,
            subject = "[Zgłoszenie problemu] $studioName",
            bodyText = body,
            attachments = emailAttachments
        )

        if (!result.success) {
            logger.warn(
                "Failed to dispatch problem report email [studioId={}, recipient={}, reason={}]",
                reportedBy.studioId, recipientEmail, result.errorMessage
            )
        }
    }

    companion object {
        private val TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.of("Europe/Warsaw"))
    }
}
