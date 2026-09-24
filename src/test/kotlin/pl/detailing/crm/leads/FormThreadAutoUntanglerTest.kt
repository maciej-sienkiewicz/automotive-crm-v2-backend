package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.comms.engine.ImapSyncEngine
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.formmail.FormThreadAutoUntangler
import pl.detailing.crm.leads.formmail.FormThreadUntangler
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Wielki wątek formularza rozplata się sam po synchronizacji skrzynki — bez banera
 * i bez klikania. Kandydatem jest wątek, którego drugą stroną jest adres po naszej
 * stronie: skrzynka studia (robot wysyła „od studia do studia") albo oznaczony robot.
 */
class FormThreadAutoUntanglerTest {

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

    private val threadRepository = mockk<CommThreadRepository>()
    private val untangler = mockk<FormThreadUntangler>()
    private val directory = object : MailAddressDirectory {
        override fun addressBook(studioId: UUID) =
            MailAddressBook(ownAddresses = setOf("biuro@carslab.pl"), formSenders = setOf("wordpress@carslab.pl"))
    }

    private val auto = FormThreadAutoUntangler(
        threadRepository, mockk<MailAccountRepository>(), directory, untangler, mockk<ImapSyncEngine>()
    )

    private fun thread() = CommThreadEntity(
        id = UUID.randomUUID(),
        studioId = account.studioId,
        accountId = account.id,
        subjectNorm = "formularz kontaktowy - carslab - kontakt",
        subject = "Formularz kontaktowy - carslab - kontakt",
        participantEmail = "biuro@carslab.pl",
        participantName = "Carslab",
        lastMessageAt = Instant.now(),
        lastDirection = CommDirection.INBOUND,
        lastSnippet = null,
        leadId = null,
        labelId = null,
        messageCount = 112
    )

    @Test
    fun `po synchronizacji rozplata kazdy sklejony watek skrzynki studia i robotow`() {
        val first = thread()
        val second = thread()
        every {
            threadRepository.findUntangleCandidates(account.id, setOf("biuro@carslab.pl", "wordpress@carslab.pl"))
        } returns listOf(first, second)
        every { untangler.untangle(any(), any()) } returns null

        auto.afterSync(account)

        verify { untangler.untangle(StudioId(account.studioId), first.id) }
        verify { untangler.untangle(StudioId(account.studioId), second.id) }
    }

    @Test
    fun `awaria jednego watku nie zatrzymuje pozostalych`() {
        val broken = thread()
        val healthy = thread()
        every { threadRepository.findUntangleCandidates(any(), any()) } returns listOf(broken, healthy)
        every { untangler.untangle(any(), broken.id) } throws IllegalStateException("baza niedostępna")
        every { untangler.untangle(any(), healthy.id) } returns null

        auto.afterSync(account)

        verify { untangler.untangle(StudioId(account.studioId), healthy.id) }
    }
}
