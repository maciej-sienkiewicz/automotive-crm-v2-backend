package pl.detailing.crm.inbound.email.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.RawIncomingEmail
import pl.detailing.crm.mailbox.ingest.IngestMailHandler
import pl.detailing.crm.mailbox.ingest.MailIngestResult
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant

/**
 * Resolves the studio from the forwarding alias and hands the message to the shared mailbox
 * pipeline. Threading, denoising, classification and lead creation all live in
 * [IngestMailHandler], so a message arriving over this webhook and one polled from IMAP are
 * treated identically — and a reply is attached to its own conversation rather than to
 * whichever lead from this address happened to still be open.
 */
@Service
class ProcessInboundEmailHandler(
    private val studioRepository: StudioRepository,
    private val ingestMailHandler: IngestMailHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: ProcessInboundEmailCommand): ProcessInboundEmailResult {
        val studioEntity = studioRepository.findByEmailAlias(command.alias)
        if (studioEntity == null) {
            log.warn("[INBOUND_EMAIL] Received email for unknown alias='{}', from='{}'", command.alias, command.from)
            return ProcessInboundEmailResult.Ignored("Unknown alias")
        }
        val studioId = StudioId(studioEntity.id)

        val result = ingestMailHandler.handle(
            studioId = studioId.value,
            mailAccountId = null,
            email = RawIncomingEmail(
                messageId = command.messageId,
                inReplyTo = command.inReplyTo,
                references = command.references,
                fromEmail = extractAddress(command.from),
                fromName = command.fromName ?: extractDisplayName(command.from),
                toEmails = emptyList(),
                subject = command.subject,
                sentAt = command.sentAt ?: Instant.now(),
                bodyHtml = command.bodyHtml,
                bodyText = command.body,
                direction = MailDirection.INBOUND,
                headers = command.headers.mapKeys { it.key.lowercase() }
            )
        )

        return when (result) {
            is MailIngestResult.LeadCreated -> ProcessInboundEmailResult.LeadCreated(result.leadId.toString())
            is MailIngestResult.ReplyAppended -> ProcessInboundEmailResult.ReplyAppended(result.leadId.toString())
            is MailIngestResult.Ignored -> ProcessInboundEmailResult.Ignored(result.reason)
            MailIngestResult.Duplicate -> ProcessInboundEmailResult.Ignored("Duplicate message")
            MailIngestResult.Stored -> ProcessInboundEmailResult.Ignored("Stored without lead")
        }
    }

    /** Cloudflare sends either a bare address or `Name <address>`. */
    private fun extractAddress(from: String): String =
        (Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from).trim().lowercase()

    private fun extractDisplayName(from: String): String? {
        if (!from.contains('<')) return null
        return from.substringBefore('<').trim().trim('"').ifBlank { null }
    }
}

sealed class ProcessInboundEmailResult {
    data class LeadCreated(val leadId: String) : ProcessInboundEmailResult()
    data class ReplyAppended(val leadId: String) : ProcessInboundEmailResult()
    data class Ignored(val reason: String) : ProcessInboundEmailResult()
}
