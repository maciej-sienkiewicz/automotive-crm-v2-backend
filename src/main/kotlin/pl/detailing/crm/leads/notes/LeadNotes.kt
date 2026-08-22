package pl.detailing.crm.leads.notes

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Notatka na leadzie: „oddzwoniłem, prosił o kontakt po 15", „wyśle zdjęcia po
 * weekendzie".
 *
 * Osobny byt od korespondencji i historii statusów, bo notuje to, czego tamte nie
 * widzą: telefon nie zostawia maila w wątku, a ustalenie „dzwonić po urlopie" nie
 * zmienia statusu. Ten sam wzorzec co notatki pojazdu i klienta — jedna forma
 * notowania w całej aplikacji.
 */
@Entity
@Table(
    name = "lead_notes",
    indexes = [
        Index(name = "idx_lead_notes_lead_id", columnList = "lead_id"),
        Index(name = "idx_lead_notes_studio_id", columnList = "studio_id")
    ]
)
class LeadNoteEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

@Repository
interface LeadNoteRepository : JpaRepository<LeadNoteEntity, UUID> {
    fun findByLeadIdAndStudioIdOrderByCreatedAtDesc(leadId: UUID, studioId: UUID): List<LeadNoteEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): LeadNoteEntity?
}

data class LeadNoteItem(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Service
class LeadNoteService(
    private val noteRepository: LeadNoteRepository,
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {

    @Transactional(readOnly = true)
    fun listNotes(leadId: UUID, studioId: StudioId): List<LeadNoteItem> {
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        return noteRepository
            .findByLeadIdAndStudioIdOrderByCreatedAtDesc(leadId, studioId.value)
            .map { it.toItem() }
    }

    @Transactional
    fun addNote(
        leadId: UUID,
        studioId: StudioId,
        content: String,
        createdBy: UserId,
        createdByName: String
    ): LeadNoteItem {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) throw ValidationException("Notatka nie może być pusta")
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val now = Instant.now()
        val saved = noteRepository.save(
            LeadNoteEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                leadId = leadId,
                content = trimmed.take(MAX_CONTENT),
                createdBy = createdBy.value,
                createdByName = createdByName,
                createdAt = now,
                updatedAt = now
            )
        )

        auditService.logSync(
            LogAuditCommand(
                studioId = studioId,
                userId = createdBy,
                userDisplayName = createdByName,
                module = AuditModule.LEAD,
                entityId = leadId.toString(),
                action = AuditAction.NOTE_ADDED,
                changes = listOf(FieldChange("content", null, saved.content)),
                metadata = mapOf("noteId" to saved.id.toString())
            )
        )
        return saved.toItem()
    }

    @Transactional
    fun deleteNote(noteId: UUID, studioId: StudioId, deletedBy: UserId, deletedByName: String) {
        val entity = noteRepository.findByIdAndStudioId(noteId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono notatki")
        noteRepository.delete(entity)

        auditService.logSync(
            LogAuditCommand(
                studioId = studioId,
                userId = deletedBy,
                userDisplayName = deletedByName,
                module = AuditModule.LEAD,
                entityId = entity.leadId.toString(),
                action = AuditAction.NOTE_DELETED,
                metadata = mapOf("noteId" to noteId.toString())
            )
        )
    }

    private fun LeadNoteEntity.toItem() = LeadNoteItem(
        id = id.toString(),
        content = content,
        createdBy = createdBy.toString(),
        createdByName = createdByName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private companion object {
        const val MAX_CONTENT = 4000
    }
}
