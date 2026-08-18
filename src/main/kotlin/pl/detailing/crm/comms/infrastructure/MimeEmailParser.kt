package pl.detailing.crm.comms.infrastructure

import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.ParsedAttachment
import pl.detailing.crm.comms.domain.ParsedEmail
import java.io.InputStream
import java.time.Instant

/**
 * Turns one MIME message into [ParsedEmail]. Defensive by design: real-world mail is
 * full of broken encodings and non-compliant structures, and a single unparseable part
 * must degrade to "no body"/"no attachment", never to a failed sync run.
 */
@Service
class MimeEmailParser {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(message: MimeMessage, imapUid: Long?): ParsedEmail {
        val from = runCatching { message.from?.firstOrNull() }.getOrNull() as? InternetAddress
        val sentAt = (message.sentDate ?: message.receivedDate)?.toInstant() ?: Instant.now()
        val body = BodyAccumulator()
        runCatching { walk(message, body) }
            .onFailure { log.warn("Nie udało się sparsować treści MIME: {}", it.message) }

        return ParsedEmail(
            messageId = normalizeId(runCatching { message.messageID }.getOrNull()),
            inReplyTo = normalizeId(firstHeader(message, "In-Reply-To")),
            references = parseReferences(firstHeader(message, "References")),
            fromEmail = from?.address?.lowercase() ?: "unknown@unknown",
            fromName = from?.personal?.let { decodeText(it) },
            toEmails = recipients(message, Message.RecipientType.TO),
            ccEmails = recipients(message, Message.RecipientType.CC),
            subject = runCatching { message.subject }.getOrNull(),
            sentAt = sentAt,
            bodyHtml = body.html,
            bodyText = body.text,
            attachments = body.attachments,
            imapUid = imapUid,
            seen = runCatching { message.isSet(Flags.Flag.SEEN) }.getOrDefault(false),
            headers = extractFilterHeaders(message)
        )
    }

    fun normalizeId(value: String?): String? =
        value?.replace("<", "")?.replace(">", "")?.filterNot { it.isWhitespace() }
            ?.take(500)?.takeIf { it.isNotBlank() }

    fun parseReferences(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return REFERENCE_REGEX.findAll(value)
            .map { it.groupValues[1].filterNot { ch -> ch.isWhitespace() } }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun recipients(message: MimeMessage, type: Message.RecipientType): List<String> =
        runCatching { message.getRecipients(type) }.getOrNull()
            ?.filterIsInstance<InternetAddress>()
            ?.mapNotNull { it.address?.lowercase() }
            ?: emptyList()

    private fun firstHeader(message: MimeMessage, name: String): String? =
        runCatching { message.getHeader(name)?.firstOrNull() }.getOrNull()

    private fun decodeText(value: String): String =
        runCatching { MimeUtility.decodeText(value) }.getOrDefault(value)

    private fun extractFilterHeaders(message: MimeMessage): Map<String, String> =
        FILTER_HEADERS.mapNotNull { name ->
            firstHeader(message, name)?.takeIf { it.isNotBlank() }?.let { name.lowercase() to it.trim() }
        }.toMap()

    private class BodyAccumulator {
        var text: String? = null
        var html: String? = null
        val attachments = mutableListOf<ParsedAttachment>()
    }

    private fun walk(part: Part, acc: BodyAccumulator) {
        val content = runCatching { part.content }.getOrNull()
        if (content is Multipart) {
            for (i in 0 until content.count) {
                runCatching { walk(content.getBodyPart(i), acc) }
            }
            return
        }

        val disposition = runCatching { part.disposition }.getOrNull()
        val contentId = runCatching {
            part.getHeader("Content-ID")?.firstOrNull()
        }.getOrNull()?.trim('<', '>', ' ')?.takeIf { it.isNotBlank() }

        val isAttachmentDisposition = Part.ATTACHMENT.equals(disposition, ignoreCase = true)
        val isTextPart = runCatching { part.isMimeType("text/plain") || part.isMimeType("text/html") }
            .getOrDefault(false)

        // Anything that is not a top-level text body is an attachment; inline images
        // (cid:) arrive as non-attachment-disposition image parts with a Content-ID.
        if (isAttachmentDisposition || (!isTextPart && (contentId != null || hasFileName(part)))) {
            readAttachment(part, contentId, isAttachmentDisposition)?.let { acc.attachments.add(it) }
            return
        }

        when {
            runCatching { part.isMimeType("text/html") }.getOrDefault(false) ->
                if (acc.html == null) acc.html = content as? String
            runCatching { part.isMimeType("text/plain") }.getOrDefault(false) ->
                if (acc.text == null) acc.text = content as? String
        }
    }

    private fun hasFileName(part: Part): Boolean =
        runCatching { part.fileName }.getOrNull()?.isNotBlank() == true

    private fun readAttachment(part: Part, contentId: String?, dispositionAttachment: Boolean): ParsedAttachment? {
        val size = runCatching { part.size }.getOrDefault(-1)
        if (size > MAX_ATTACHMENT_BYTES) {
            log.info("Pominięto załącznik > {} MB", MAX_ATTACHMENT_BYTES / (1024 * 1024))
            return null
        }
        val bytes = runCatching {
            (part.inputStream as InputStream).readNBytes(MAX_ATTACHMENT_BYTES + 1)
        }.getOrNull() ?: return null
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            log.info("Pominięto załącznik przekraczający limit rozmiaru (nagłówek size był niewiarygodny)")
            return null
        }

        val fileName = runCatching { part.fileName }.getOrNull()
            ?.let { decodeText(it) }
            ?.take(500)
            ?: contentId?.let { "inline-$it" }
            ?: "zalacznik"
        val contentType = runCatching { part.contentType }.getOrNull()
            ?.substringBefore(';')?.trim()?.lowercase()?.take(255)
            ?: "application/octet-stream"

        return ParsedAttachment(
            fileName = fileName,
            contentType = contentType,
            contentId = contentId?.take(300),
            inline = !dispositionAttachment && contentId != null,
            content = bytes
        )
    }

    companion object {
        /** Hard guard — mailbox attachments above this are skipped, the message itself still lands. */
        const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024

        private val REFERENCE_REGEX = Regex("<([^<>]+)>")
        private val FILTER_HEADERS = listOf("List-Unsubscribe", "List-Id", "Auto-Submitted", "Precedence")
    }
}
