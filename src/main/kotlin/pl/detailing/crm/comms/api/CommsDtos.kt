package pl.detailing.crm.comms.api

import pl.detailing.crm.comms.engine.SyncProgressSnapshot
import pl.detailing.crm.comms.infrastructure.CommAttachmentMeta
import pl.detailing.crm.comms.infrastructure.CommLabelEntity
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import java.time.Instant
import java.util.UUID

data class MailAccountStateDto(
    val id: String,
    val emailAddress: String,
    val status: String,
    val lastError: String?,
    val lastSyncAt: Instant?,
    /**
     * Skrzynka nigdy nie domknęła pełnego przebiegu synchronizacji — pierwszy import
     * trwa albo zaraz wystartuje. Interfejs pokazuje wtedy stan „trwa synchronizacja"
     * zamiast list, które i tak rosną z sekundy na sekundę.
     */
    val initialSyncInProgress: Boolean = false,
    /** Postęp bieżącego importu; null, gdy przebieg akurat nie trwa. */
    val syncTotal: Int? = null,
    val syncProcessed: Int? = null
)

data class CommThreadDto(
    val id: String,
    val accountId: String,
    val subject: String?,
    val participantEmail: String,
    val participantName: String?,
    val lastMessageAt: Instant,
    val lastDirection: String,
    val lastSnippet: String?,
    val messageCount: Int,
    val unreadCount: Int,
    val hasAttachments: Boolean,
    val leadId: String?,
    val labelId: String?,
    val archived: Boolean
)

data class CommThreadPageDto(
    val items: List<CommThreadDto>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val totalUnread: Long
)

data class CommMessageDto(
    val id: String,
    val threadId: String,
    val direction: String,
    val fromEmail: String,
    val fromName: String?,
    val toEmails: List<String>,
    val ccEmails: List<String>,
    val subject: String?,
    val sentAt: Instant,
    val bodyHtml: String?,
    val isRead: Boolean,
    val readSource: String?,
    val readAt: Instant?,
    val sendStatus: String,
    val attachments: List<CommAttachmentDto>,
    /**
     * Lead założony z TEJ wiadomości przez automat formularzowy. Wątek formularza
     * zbiera zgłoszenia wielu klientów pod wspólnym tematem, więc lead wisi przy
     * konkretnej wiadomości, a nie przy wątku.
     */
    val formLeadId: String? = null
)

data class CommAttachmentDto(
    val id: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val isInline: Boolean
)

data class CommThreadDetailDto(
    val thread: CommThreadDto,
    val messages: List<CommMessageDto>
)

data class CommLabelDto(
    val id: String,
    val name: String,
    val color: String?,
    val position: Int
)

fun MailAccountEntity.toStateDto(
    progress: SyncProgressSnapshot? = null
) = MailAccountStateDto(
    id = id.toString(),
    emailAddress = emailAddress,
    status = status.name,
    lastError = lastError,
    lastSyncAt = lastSyncAt,
    // lastSyncAt zapisuje się dopiero po DOMKNIĘCIU pełnego przebiegu — dopóki go
    // nie ma, pierwszy import trwa (albo zaraz ruszy po podłączeniu konta).
    // Tylko ACTIVE: konto z odrzuconym hasłem nigdy się nie zsynchronizuje i ma
    // pokazywać swój błąd, a nie wieczny ekran „trwa synchronizacja".
    initialSyncInProgress = lastSyncAt == null && status.name == "ACTIVE",
    syncTotal = progress?.total,
    syncProcessed = progress?.processed
)

fun CommThreadEntity.toDto() = CommThreadDto(
    id = id.toString(),
    accountId = accountId.toString(),
    subject = subject,
    participantEmail = participantEmail,
    participantName = participantName,
    lastMessageAt = lastMessageAt,
    lastDirection = lastDirection.name,
    lastSnippet = lastSnippet,
    messageCount = messageCount,
    unreadCount = unreadCount,
    hasAttachments = hasAttachments,
    leadId = leadId?.toString(),
    labelId = labelId?.toString(),
    archived = archived
)

fun CommMessageEntity.toDto(
    attachments: List<CommAttachmentMeta>,
    formLeadId: UUID? = null
) = CommMessageDto(
    id = id.toString(),
    threadId = threadId.toString(),
    direction = direction.name,
    fromEmail = fromEmail,
    fromName = fromName,
    toEmails = toEmails?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    ccEmails = ccEmails?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    subject = subject,
    sentAt = sentAt,
    bodyHtml = bodyHtmlSafe,
    isRead = isRead,
    readSource = readSource?.name,
    readAt = readAt,
    sendStatus = sendStatus.name,
    // Inline images are referenced from the HTML by URL; the visible list shows real files only.
    attachments = attachments.filter { !it.isInline }.map {
        CommAttachmentDto(
            id = it.id.toString(),
            fileName = it.fileName,
            contentType = it.contentType,
            sizeBytes = it.sizeBytes,
            isInline = it.isInline
        )
    },
    formLeadId = formLeadId?.toString()
)

fun CommLabelEntity.toDto() = CommLabelDto(
    id = id.toString(),
    name = name,
    color = color,
    position = position
)
