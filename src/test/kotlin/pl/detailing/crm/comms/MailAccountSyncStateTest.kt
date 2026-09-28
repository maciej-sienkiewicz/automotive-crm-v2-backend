package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.api.toStateDto
import pl.detailing.crm.comms.engine.SyncProgressSnapshot
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import java.time.Instant
import java.util.UUID

/**
 * Nieudany pierwszy import zostawiał „trwa synchronizacja" na zawsze: ekran postępu nie
 * znikał, a każda karta odpytywała konta co kilka sekund (limit żądań całego biura).
 */
class MailAccountSyncStateTest {

    private fun account(lastSyncAt: Instant? = null, lastError: String? = null, status: MailAccountStatus = MailAccountStatus.ACTIVE) =
        MailAccountEntity(
            id = UUID.randomUUID(), studioId = UUID.randomUUID(), emailAddress = "studio@example.pl",
            providerType = MailProviderType.IMAP_SMTP, authType = MailAuthType.PASSWORD, encryptedPassword = "ENC:x",
            imapHost = "imap.example.pl", imapPort = 993, smtpHost = "smtp.example.pl", smtpPort = 587,
            status = status, lastError = lastError, lastSyncAt = lastSyncAt,
            inboxUidValidity = null, inboxLastUid = null, sentUidValidity = null, sentLastUid = null
        )

    @Test
    fun `pierwszy import trwa albo zaraz ruszy`() {
        assertTrue(account().toStateDto().initialSyncInProgress)
        assertTrue(account().toStateDto(SyncProgressSnapshot(100, 10)).initialSyncInProgress)
    }

    @Test
    fun `nieudany pierwszy import nie wisi jako trwajacy - konto pokazuje blad`() {
        assertFalse(account(lastError = "Read timed out").toStateDto().initialSyncInProgress)
    }

    @Test
    fun `kolejna proba po bledzie znowu pokazuje postep`() {
        assertTrue(account(lastError = "Read timed out").toStateDto(SyncProgressSnapshot(100, 1)).initialSyncInProgress)
    }

    @Test
    fun `po udanym przebiegu i przy odrzuconym hasle - nie trwa`() {
        assertFalse(account(lastSyncAt = Instant.now()).toStateDto().initialSyncInProgress)
        assertFalse(account(status = MailAccountStatus.AUTH_FAILED).toStateDto().initialSyncInProgress)
    }
}
