package pl.detailing.crm.mailbox.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "mail_accounts",
    indexes = [
        Index(name = "idx_mail_accounts_studio_email", columnList = "studio_id, email_address", unique = true),
        Index(name = "idx_mail_accounts_studio", columnList = "studio_id"),
        Index(name = "idx_mail_accounts_status", columnList = "status")
    ]
)
class MailAccountEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "email_address", nullable = false, length = 320)
    val emailAddress: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 30)
    var providerType: MailProviderType,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 30)
    var authType: MailAuthType,

    /** AES-GCM ciphertext produced by MailboxEncryptionService — never logged, never returned by the API. */
    @Column(name = "encrypted_password", columnDefinition = "text")
    var encryptedPassword: String?,

    @Column(name = "imap_host", length = 255)
    var imapHost: String?,

    @Column(name = "imap_port")
    var imapPort: Int?,

    @Column(name = "smtp_host", length = 255)
    var smtpHost: String?,

    @Column(name = "smtp_port")
    var smtpPort: Int?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: MailAccountStatus,

    @Column(name = "last_error", length = 500)
    var lastError: String?,

    @Column(name = "last_sync_at")
    var lastSyncAt: Instant?,

    @Column(name = "inbox_uid_validity")
    var inboxUidValidity: Long?,

    @Column(name = "inbox_last_uid")
    var inboxLastUid: Long?,

    @Column(name = "sent_uid_validity")
    var sentUidValidity: Long?,

    @Column(name = "sent_last_uid")
    var sentLastUid: Long?,

    /**
     * Folder Wysłanych rozpoznany dla tej skrzynki (pełna nazwa IMAP) albo ręczne
     * nadpisanie. NULL = jeszcze nie rozpoznano. Trzymany, bo nazwa jest różna u każdego
     * dostawcy, a bez folderu Wysłanych odpowiedzi wysłane spoza CRM-a nie trafią do rozmów.
     */
    @Column(name = "sent_folder_name", length = 1000)
    var sentFolderName: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

/**
 * Znacznik UID dla JEDNEGO folderu skrzynki.
 *
 * Do tej pory para (uidValidity, lastUid) wisiała przy koncie i była jedna na Wysłane —
 * co wystarczało dopóty, dopóki folder Wysłanych był jeden. Nie jest: skrzynka po latach
 * pracy ma ich po kilka („Elementy wysłane", „Sent", „INBOX.Sent"), bo zakładał je każdy
 * kolejny program pocztowy, a poczta idzie do tego, którego akurat używa klient. Skanujemy
 * więc wszystkie, a każdy potrzebuje własnego znacznika — UID-y są numeracją FOLDERU i
 * przeniesione między folderami nie znaczą nic.
 *
 * Dlaczego osobna tabela, a nie kolejne kolumny przy koncie: folderów jest z góry nieznana
 * liczba i przybywa ich w cudzej skrzynce, bez naszego udziału.
 */
@Entity
@Table(
    name = "mail_folder_cursors",
    indexes = [
        Index(name = "idx_mail_folder_cursors_account", columnList = "account_id"),
        Index(
            name = "idx_mail_folder_cursors_account_folder",
            columnList = "account_id, folder_name",
            unique = true
        )
    ]
)
class MailFolderCursorEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    /** Pełna nazwa IMAP folderu — ta sama, którą oddaje LIST. */
    @Column(name = "folder_name", nullable = false, length = 1000)
    val folderName: String,

    /**
     * UIDVALIDITY folderu z ostatniego przebiegu. Jego zmiana to komunikat serwera
     * „folder, który znałeś, przestał istnieć" (RFC 3501) i unieważnia [lastUid].
     */
    @Column(name = "uid_validity")
    var uidValidity: Long? = null,

    @Column(name = "last_uid", nullable = false)
    var lastUid: Long = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
