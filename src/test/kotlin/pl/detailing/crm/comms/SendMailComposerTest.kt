package pl.detailing.crm.comms

import io.mockk.mockk
import jakarta.mail.Part
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import pl.detailing.crm.comms.send.AccountMailSender
import pl.detailing.crm.comms.send.OutgoingAttachment
import pl.detailing.crm.comms.send.OutgoingAttachmentPolicy
import pl.detailing.crm.comms.send.OutgoingMail
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailboxEncryptionService
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Formatowanie z kompozytora CRM ma przejść przez sanitizer nietknięte — to ten sam
 * filtr, który czyści obcą pocztę, więc sprawdzamy, że nie zjada naszych znaczników.
 */
class ComposerFormattingSanitizerTest {

    private val sanitizer = EmailHtmlSanitizer()

    @Test
    fun `keeps bold italic underline strike lists and links from the composer`() {
        val out = sanitizer.sanitize(
            """<div><b>Wycena</b> <i>orientacyjna</i> <u>do potwierdzenia</u> <s>1200</s> <strike>1100</strike> 990 zł</div>
               <ul><li>korekta lakieru</li><li>powłoka</li></ul>
               <ol><li>umycie</li></ol>
               <div><a href="https://carslab.pl/cennik">cennik</a></div>""",
            UUID.randomUUID(),
            emptyMap()
        )
        listOf("<b>", "<i>", "<u>", "<s>", "<strike>", "<ul>", "<ol>", "<li>").forEach { tag ->
            assertTrue(out.contains(tag), "Sanitizer wyciął $tag: $out")
        }
        assertTrue(out.contains("""href="https://carslab.pl/cennik""""))
        assertTrue(out.contains("""target="_blank""""))
    }
}

class OutgoingAttachmentPolicyTest {

    private fun file(name: String, size: Int = 10) =
        OutgoingAttachment(fileName = name, contentType = "application/octet-stream", content = ByteArray(size) { 1 })

    @Test
    fun `accepts ordinary documents`() {
        OutgoingAttachmentPolicy.validate(listOf(file("wycena.pdf"), file("auto.jpg")))
    }

    @Test
    fun `rejects executables with a polish message`() {
        val error = assertThrows(ValidationException::class.java) {
            OutgoingAttachmentPolicy.validate(listOf(file("setup.EXE")))
        }
        assertTrue(error.message!!.contains(".exe"))
    }

    @Test
    fun `rejects empty files and too many files`() {
        assertThrows(ValidationException::class.java) {
            OutgoingAttachmentPolicy.validate(listOf(file("pusty.pdf", size = 0)))
        }
        assertThrows(ValidationException::class.java) {
            OutgoingAttachmentPolicy.validate((1..OutgoingAttachmentPolicy.MAX_FILES + 1).map { file("plik$it.pdf") })
        }
    }

    @Test
    fun `rejects a single file over the per-file limit`() {
        val big = OutgoingAttachment(
            fileName = "film.mp4",
            contentType = "video/mp4",
            content = ByteArray((OutgoingAttachmentPolicy.MAX_FILE_BYTES + 1).toInt())
        )
        assertThrows(ValidationException::class.java) { OutgoingAttachmentPolicy.validate(listOf(big)) }
    }

    @Test
    fun `strips paths and control characters from file names`() {
        assertEquals("wycena.pdf", OutgoingAttachmentPolicy.safeFileName("C:\\Users\\jan\\wycena.pdf"))
        assertEquals("wycena.pdf", OutgoingAttachmentPolicy.safeFileName("/tmp/wycena.pdf"))
        assertEquals("zalacznik", OutgoingAttachmentPolicy.safeFileName("   "))
        assertFalse(OutgoingAttachmentPolicy.safeFileName("a\"b\n.pdf").contains('"'))
    }
}

class AccountMailSenderComposeTest {

    private val sender = AccountMailSender(mockk<MailboxEncryptionService>())

    private val account = MailAccountEntity(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        emailAddress = "biuro@carslab.pl",
        providerType = MailProviderType.IMAP_SMTP,
        authType = MailAuthType.PASSWORD,
        encryptedPassword = "ENC:x",
        imapHost = "imap.example.pl",
        imapPort = 993,
        smtpHost = "smtp.example.pl",
        smtpPort = 587,
        status = MailAccountStatus.ACTIVE,
        lastError = null,
        lastSyncAt = null,
        inboxUidValidity = null,
        inboxLastUid = null,
        sentUidValidity = null,
        sentLastUid = null
    )

    @Test
    fun `html only mail carries a plain text alternative`() {
        val message = sender.compose(
            account, "abc@carslab.pl",
            OutgoingMail(
                to = listOf("klient@example.pl"),
                subject = "Wycena",
                bodyHtml = "<div>Dzień dobry,<br>koszt to <b>990 zł</b>.</div><ul><li>korekta</li></ul>"
            )
        )
        val alternative = message.content as MimeMultipart
        assertEquals("multipart/alternative", alternative.contentType.substringBefore(';'))
        val text = alternative.getBodyPart(0).content as String
        assertTrue(text.contains("Dzień dobry,\nkoszt to 990 zł."), text)
        assertTrue(text.contains("- korekta"), text)
        assertFalse(text.contains("<b>"))
        assertTrue(alternative.getBodyPart(1).contentType.startsWith("text/html"))
    }

    @Test
    fun `attachments become separate parts of a mixed multipart`() {
        val message = sender.compose(
            account, "abc@carslab.pl",
            OutgoingMail(
                to = listOf("klient@example.pl"),
                subject = "Wycena",
                bodyHtml = "<div>W załączniku wycena.</div>",
                attachments = listOf(
                    OutgoingAttachment("wycena żółta.pdf", "application/pdf", "%PDF-1.4".toByteArray()),
                    OutgoingAttachment("auto.jpg", "image/jpeg", ByteArray(3))
                )
            )
        )
        val mixed = message.content as MimeMultipart
        assertEquals("multipart/mixed", mixed.contentType.substringBefore(';'))
        assertEquals(3, mixed.count)
        assertTrue(mixed.getBodyPart(0).contentType.startsWith("multipart/alternative"))

        val pdf = mixed.getBodyPart(1)
        assertEquals(Part.ATTACHMENT, pdf.disposition)
        // Nazwa idzie w nagłówku zakodowana (RFC 2047); klient odbiorcy ją dekoduje.
        assertEquals("wycena żółta.pdf", MimeUtility.decodeText(pdf.fileName))
        assertTrue(pdf.contentType.startsWith("application/pdf"))
        assertEquals("auto.jpg", mixed.getBodyPart(2).fileName)
    }
}
