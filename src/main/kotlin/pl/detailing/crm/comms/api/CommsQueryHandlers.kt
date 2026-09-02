package pl.detailing.crm.comms.api

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommLabelEntity
import pl.detailing.crm.comms.infrastructure.CommLabelRepository
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.formmail.FormMailExtractionRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Folder listy wątków. Wątek może być w obu naraz (klient napisał, my odpisaliśmy) —
 * to nie jest przenoszenie między katalogami, tylko dwa spojrzenia na tę samą rozmowę.
 */
enum class ThreadFolder {
    /** Przynajmniej jedna wiadomość od uczestnika. */
    INBOX,
    /** Przynajmniej jedna wiadomość od nas. */
    SENT
}

data class ThreadListFilter(
    val accountId: UUID?,
    val archived: Boolean,
    val labelId: UUID?,
    val onlyUnread: Boolean,
    val onlyLeads: Boolean,
    /** null = bez ograniczenia kierunku (wszystkie wątki). */
    val folder: ThreadFolder?,
    val query: String?,
    val page: Int,
    val pageSize: Int
)

@Service
class CommsQueryHandlers(
    private val threadRepository: CommThreadRepository,
    private val messageRepository: CommMessageRepository,
    private val attachmentRepository: CommAttachmentRepository,
    private val labelRepository: CommLabelRepository,
    private val formMailExtractionRepository: FormMailExtractionRepository
) {

    @Transactional(readOnly = true)
    fun listThreads(studioId: StudioId, filter: ThreadListFilter): CommThreadPageDto {
        val result = threadRepository.search(
            studioId.value,
            filter.accountId,
            filter.archived,
            filter.labelId,
            filter.onlyUnread,
            filter.onlyLeads,
            filter.folder == ThreadFolder.INBOX,
            filter.folder == ThreadFolder.SENT,
            filter.query?.trim()?.takeIf { it.isNotBlank() },
            PageRequest.of(filter.page.coerceAtLeast(0), filter.pageSize.coerceIn(1, 100))
        )
        return CommThreadPageDto(
            items = result.content.map { it.toDto() },
            total = result.totalElements,
            page = result.number,
            pageSize = result.size,
            totalUnread = threadRepository.countUnread(studioId.value)
        )
    }

    @Transactional(readOnly = true)
    fun getThread(studioId: StudioId, threadId: UUID): CommThreadDetailDto {
        val thread = threadRepository.findByIdAndStudioId(threadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku")
        val messages = messageRepository.findByThreadIdOrderBySentAtAsc(thread.id)
        val attachmentsByMessage = attachmentRepository
            .findMetaByMessageIdIn(messages.map { it.id })
            .groupBy { it.messageId }
        // Leady z formularza pobieramy jednym zapytaniem na cały wątek — inaczej
        // podgląd rozmowy robiłby N zapytań, po jednym na wiadomość.
        val formLeadByMessage = formMailExtractionRepository
            .findByMessageIdIn(messages.map { it.id })
            .filter { it.leadId != null }
            .associate { it.messageId to it.leadId!! }
        return CommThreadDetailDto(
            thread = thread.toDto(),
            messages = messages.map {
                it.toDto(attachmentsByMessage[it.id].orEmpty(), formLeadByMessage[it.id])
            }
        )
    }

    @Transactional(readOnly = true)
    fun listLabels(studioId: StudioId): List<CommLabelDto> =
        labelRepository.findByStudioIdOrderByPositionAsc(studioId.value).map { it.toDto() }

    @Transactional
    fun createLabel(studioId: StudioId, name: String, color: String?): CommLabelDto {
        val trimmed = name.trim()
        if (trimmed.isBlank()) throw ValidationException("Podaj nazwę folderu")
        val position = labelRepository.findByStudioIdOrderByPositionAsc(studioId.value)
            .maxOfOrNull { it.position }?.plus(1) ?: 0
        return labelRepository.save(
            CommLabelEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                name = trimmed.take(100),
                color = color?.take(20),
                position = position
            )
        ).toDto()
    }

    @Transactional
    fun deleteLabel(studioId: StudioId, labelId: UUID) {
        val label = labelRepository.findByIdAndStudioId(labelId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono folderu")
        labelRepository.delete(label)
    }

    @Transactional
    fun setThreadLabel(studioId: StudioId, threadId: UUID, labelId: UUID?) {
        val thread = threadRepository.findByIdAndStudioId(threadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku")
        if (labelId != null) {
            labelRepository.findByIdAndStudioId(labelId, studioId.value)
                ?: throw NotFoundException("Nie znaleziono folderu")
        }
        thread.labelId = labelId
        threadRepository.save(thread)
    }

    /**
     * Local archive only — nothing on the IMAP server moves, so the message stays
     * visible in every other mail client exactly where it was.
     */
    @Transactional
    fun setThreadArchived(studioId: StudioId, threadId: UUID, archived: Boolean) {
        val thread = threadRepository.findByIdAndStudioId(threadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku")
        thread.archived = archived
        threadRepository.save(thread)
    }
}
