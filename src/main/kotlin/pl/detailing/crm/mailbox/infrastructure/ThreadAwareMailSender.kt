package pl.detailing.crm.mailbox.infrastructure

import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import java.util.Date
import java.util.Properties
import java.util.UUID

data class OutgoingReply(
    val to: String,
    val subject: String,
    val bodyHtml: String,
    val inReplyTo: String?,
    val references: List<String>
)

/**
 * Sends replies from the studio's own mailbox with RFC 5322 threading headers, so the client's
 * mail client keeps the conversation together and our own Message-ID can be persisted before
 * the message leaves (making the copy that later returns from the Sent folder deduplicable).
 *
 * The existing [pl.detailing.crm.email.provider.javamail.JavaMailProvider] cannot be reused:
 * it is bound to one global SMTP account from properties, hardcodes the sender and sets no headers.
 */
@Service
class ThreadAwareMailSender(
    private val encryptionService: MailboxEncryptionService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Message-ID without angle brackets — stored as-is in mail_messages.message_id. */
    fun newMessageId(account: MailAccountEntity): String {
        val domain = account.emailAddress.substringAfter('@', "").ifBlank { "localhost" }
        return "${UUID.randomUUID()}@$domain"
    }

    fun send(account: MailAccountEntity, messageId: String, reply: OutgoingReply) {
        val smtpHost = account.smtpHost?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("Skrzynka ${account.emailAddress} nie ma skonfigurowanego serwera SMTP")
        val smtpPort = account.smtpPort ?: 587
        val password = account.encryptedPassword?.let { encryptionService.decrypt(it) }
            ?: throw ValidationException("Skrzynka ${account.emailAddress} nie ma zapisanych poświadczeń")

        val props = Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort.toString())
            put("mail.smtp.auth", "true")
            if (smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
            }
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
        }

        val session = Session.getInstance(props)
        // Jakarta Mail generates its own Message-ID on save; override it so the id we persisted wins.
        val message = object : MimeMessage(session) {
            override fun updateMessageID() {
                setHeader("Message-ID", "<$messageId>")
            }
        }

        message.setFrom(InternetAddress(account.emailAddress))
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(reply.to))
        message.setSubject(reply.subject, "UTF-8")
        message.sentDate = Date()
        reply.inReplyTo?.let { message.setHeader("In-Reply-To", "<$it>") }
        if (reply.references.isNotEmpty()) {
            message.setHeader("References", reply.references.joinToString(" ") { "<$it>" })
        }
        message.setContent(reply.bodyHtml, "text/html; charset=UTF-8")
        message.saveChanges()

        Transport.send(message, account.emailAddress, password)
        log.info("Wysłano odpowiedź | account={} to={} messageId={}", account.emailAddress, reply.to, messageId)
    }
}
