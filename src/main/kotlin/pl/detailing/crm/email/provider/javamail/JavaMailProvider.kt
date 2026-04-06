package pl.detailing.crm.email.provider.javamail

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import jakarta.activation.DataHandler
import org.slf4j.LoggerFactory
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import java.util.Properties

/**
 * [EmailProvider] backed by the Jakarta Mail (JavaMail) API.
 *
 * All outgoing messages use the fixed technical sender: automat@detailboost.pl.
 * When [JavaMailProperties.enabled] is false the message is only logged (useful
 * for local development / testing environments).
 */
class JavaMailProvider(
    private val properties: JavaMailProperties
) : EmailProvider {

    private val logger = LoggerFactory.getLogger(JavaMailProvider::class.java)
    private val allowedMails = listOf<String>("kontakt@sienkiewicz-maciej.pl", "mikolajblaszczak@o2.pl")

    override fun send(
        to: String,
        subject: String,
        bodyText: String,
        attachments: List<EmailAttachment>
    ): EmailDeliveryResult {
        if (!properties.enabled || !allowedMails.contains(to)) {
            logger.info(
                "[EMAIL DISABLED] To: {} | Subject: {} | Attachments: {}",
                to, subject, attachments.size
            )
            return EmailDeliveryResult.failure("Email celowo zablokowany. Faza testowa. Mail mozna wyslac tylko do: \"kontakt@sienkiewicz-maciej.pl\", \"mikolajblaszczak@o2.pl\"")
        }

        return try {
            val session = buildSession()
            val message = buildMessage(session, to, subject, bodyText, attachments)
            Transport.send(message)
            logger.info("Email dispatched | to={} subject={}", to, subject)
            EmailDeliveryResult.success("")
        } catch (ex: MessagingException) {
            logger.error("Failed to send email to {}: {}", to, ex.message, ex)
            EmailDeliveryResult.failure(ex.message ?: "Unknown SMTP error")
        }
    }

    private fun buildSession(): Session {
        val props = Properties().apply {
            put("mail.smtp.host", properties.host)
            put("mail.smtp.port", properties.port.toString())
            put("mail.smtp.auth", properties.smtpAuth.toString())
            put("mail.smtp.starttls.enable", properties.smtpStarttlsEnable.toString())
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(properties.username, properties.password)
        })
    }

    private fun buildMessage(
        session: Session,
        to: String,
        subject: String,
        bodyText: String,
        attachments: List<EmailAttachment>
    ): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(FROM_ADDRESS, FROM_DISPLAY_NAME, UTF8))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.subject = MimeUtility.encodeText(subject, UTF8, "B")

        if (attachments.isEmpty()) {
            message.setText(bodyText, UTF8, "plain")
        } else {
            val multipart = MimeMultipart()

            val textPart = MimeBodyPart()
            textPart.setText(bodyText, UTF8, "plain")
            multipart.addBodyPart(textPart)

            for (attachment in attachments) {
                val attachPart = MimeBodyPart()
                attachPart.dataHandler = DataHandler(
                    ByteArrayDataSource(attachment.content, attachment.contentType)
                )
                attachPart.fileName = MimeUtility.encodeText(attachment.fileName, UTF8, "B")
                multipart.addBodyPart(attachPart)
            }

            message.setContent(multipart)
        }

        return message
    }

    companion object {
        private const val FROM_ADDRESS = "crm1@sienkiewicz-maciej.pl"
        private const val FROM_DISPLAY_NAME = "DetailBoost"
        private const val UTF8 = "UTF-8"
    }
}
