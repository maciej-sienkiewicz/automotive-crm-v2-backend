package pl.detailing.crm.comms.notes

import pl.detailing.crm.shared.pii.Pii
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

data class ContactNoteDto(
    val id: String,
    val body: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** true, gdy treść była później zmieniana — popover mówi wtedy „edytowano". */
    val edited: Boolean
)

data class ContactNoteEventDto(
    val id: String,
    val action: String,
    val bodyBefore: String?,
    val bodyAfter: String?,
    val actorName: String,
    val createdAt: Instant
)

data class ContactNotesDto(
    @Pii val email: String,
    val notes: List<ContactNoteDto>
)

/**
 * Notatki o kontakcie i ich pełen ślad.
 *
 * Historię piszemy w tej samej transakcji co zmianę — wpis, który mógłby się nie
 * zapisać przy udanej edycji, byłby gorszy niż jego brak, bo dawałby złudzenie
 * kompletnego dziennika.
 */
@Service
class ContactNoteService(
    private val noteRepository: ContactNoteRepository,
    private val eventRepository: ContactNoteEventRepository
) {

    @Transactional(readOnly = true)
    fun list(studioId: StudioId, rawEmail: String): ContactNotesDto {
        val email = normalize(rawEmail)
        return ContactNotesDto(
            email = email,
            notes = noteRepository
                .findByStudioIdAndContactEmailAndDeletedAtIsNullOrderByCreatedAtDesc(studioId.value, email)
                .map { it.toDto() }
        )
    }

    @Transactional(readOnly = true)
    fun count(studioId: StudioId, rawEmail: String): Long =
        noteRepository.countByStudioIdAndContactEmailAndDeletedAtIsNull(studioId.value, normalize(rawEmail))

    @Transactional(readOnly = true)
    fun history(studioId: StudioId, rawEmail: String): List<ContactNoteEventDto> =
        eventRepository
            .findTop100ByStudioIdAndContactEmailOrderByCreatedAtDesc(studioId.value, normalize(rawEmail))
            .map {
                ContactNoteEventDto(
                    id = it.id.toString(),
                    action = it.action,
                    bodyBefore = it.bodyBefore,
                    bodyAfter = it.bodyAfter,
                    actorName = it.actorName,
                    createdAt = it.createdAt
                )
            }

    @Transactional
    fun create(studioId: StudioId, rawEmail: String, rawBody: String, actorId: UUID?, actorName: String): ContactNoteDto {
        val email = normalize(rawEmail)
        val body = validBody(rawBody)
        val note = noteRepository.save(
            ContactNoteEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                contactEmail = email,
                body = body,
                createdById = actorId,
                createdByName = actorName
            )
        )
        record(note, ACTION_CREATED, before = null, after = body, actorId = actorId, actorName = actorName)
        return note.toDto()
    }

    @Transactional
    fun update(studioId: StudioId, noteId: UUID, rawBody: String, actorId: UUID?, actorName: String): ContactNoteDto {
        val note = activeNote(studioId, noteId)
        val body = validBody(rawBody)
        if (body == note.body) return note.toDto()

        val before = note.body
        note.body = body
        note.updatedAt = Instant.now()
        noteRepository.save(note)
        record(note, ACTION_UPDATED, before = before, after = body, actorId = actorId, actorName = actorName)
        return note.toDto()
    }

    @Transactional
    fun delete(studioId: StudioId, noteId: UUID, actorId: UUID?, actorName: String) {
        val note = activeNote(studioId, noteId)
        note.deletedAt = Instant.now()
        noteRepository.save(note)
        record(note, ACTION_DELETED, before = note.body, after = null, actorId = actorId, actorName = actorName)
    }

    private fun activeNote(studioId: StudioId, noteId: UUID): ContactNoteEntity =
        noteRepository.findByIdAndStudioId(noteId, studioId.value)
            ?.takeIf { it.deletedAt == null }
            ?: throw NotFoundException("Nie znaleziono notatki")

    private fun record(
        note: ContactNoteEntity,
        action: String,
        before: String?,
        after: String?,
        actorId: UUID?,
        actorName: String
    ) {
        eventRepository.save(
            ContactNoteEventEntity(
                id = UUID.randomUUID(),
                studioId = note.studioId,
                contactEmail = note.contactEmail,
                noteId = note.id,
                action = action,
                bodyBefore = before,
                bodyAfter = after,
                actorId = actorId,
                actorName = actorName
            )
        )
    }

    private fun validBody(raw: String): String {
        val body = raw.trim()
        if (body.isBlank()) throw ValidationException("Notatka nie może być pusta")
        if (body.length > MAX_BODY) throw ValidationException("Notatka może mieć najwyżej $MAX_BODY znaków")
        return body
    }

    private fun ContactNoteEntity.toDto() = ContactNoteDto(
        id = id.toString(),
        body = body,
        createdByName = createdByName,
        createdAt = createdAt,
        updatedAt = updatedAt,
        edited = updatedAt.isAfter(createdAt)
    )

    private fun normalize(raw: String): String = raw.trim().lowercase()

    private companion object {
        const val MAX_BODY = 4000
        const val ACTION_CREATED = "CREATED"
        const val ACTION_UPDATED = "UPDATED"
        const val ACTION_DELETED = "DELETED"
    }
}
