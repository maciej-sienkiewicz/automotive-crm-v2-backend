package pl.detailing.crm.product.notes

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.product.infrastructure.ProductNoteEntity
import pl.detailing.crm.product.infrastructure.ProductNoteRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Notatki produktu — nieskończenie wiele na produkt, zawsze prywatne dla studia.
 * Wzorzec (autor, znacznik, miękkie usunięcie, audyt edycji) z `visit_comments`.
 */
@Service
class ProductNoteService(
    private val noteRepository: ProductNoteRepository
) {
    @Transactional(readOnly = true)
    fun list(studioId: StudioId, productId: UUID): List<NoteDto> =
        noteRepository.findActive(studioId.value, productId).map { it.toDto() }

    @Transactional
    fun add(
        studioId: StudioId,
        userId: UserId,
        userName: String,
        productId: UUID,
        content: String,
        visitId: UUID?
    ): NoteDto {
        val trimmed = content.trim()
        if (trimmed.isBlank()) throw ValidationException("Notatka nie może być pusta.")
        val entity = ProductNoteEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            productId = productId,
            content = trimmed,
            visitId = visitId,
            createdBy = userId.value,
            createdByName = userName,
            createdAt = Instant.now()
        )
        return noteRepository.save(entity).toDto()
    }

    @Transactional
    fun edit(studioId: StudioId, userId: UserId, userName: String, isOwner: Boolean, noteId: UUID, content: String): NoteDto {
        val note = noteRepository.findByIdAndStudioId(noteId, studioId.value)
            ?: throw EntityNotFoundException("Notatka nie została znaleziona")
        if (note.isDeleted) throw EntityNotFoundException("Notatka została usunięta")
        // Edytować może autor albo właściciel — cudzych notatek nie przepisujemy po cichu.
        if (note.createdBy != userId.value && !isOwner) {
            throw ForbiddenException("Można edytować tylko własne notatki.")
        }
        val trimmed = content.trim()
        if (trimmed.isBlank()) throw ValidationException("Notatka nie może być pusta.")
        note.content = trimmed
        note.updatedBy = userId.value
        note.updatedByName = userName
        note.updatedAt = Instant.now()
        return noteRepository.save(note).toDto()
    }

    @Transactional
    fun delete(studioId: StudioId, userId: UserId, isOwner: Boolean, noteId: UUID) {
        val note = noteRepository.findByIdAndStudioId(noteId, studioId.value)
            ?: throw EntityNotFoundException("Notatka nie została znaleziona")
        if (note.isDeleted) return
        if (note.createdBy != userId.value && !isOwner) {
            throw ForbiddenException("Można usuwać tylko własne notatki.")
        }
        note.isDeleted = true
        note.deletedBy = userId.value
        note.deletedAt = Instant.now()
        noteRepository.save(note)
    }
}

data class NoteDto(
    val id: String,
    val content: String,
    val visitId: String?,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant?
)

private fun ProductNoteEntity.toDto() = NoteDto(
    id = id.toString(),
    content = content,
    visitId = visitId?.toString(),
    createdByName = createdByName,
    createdAt = createdAt,
    updatedAt = updatedAt
)
