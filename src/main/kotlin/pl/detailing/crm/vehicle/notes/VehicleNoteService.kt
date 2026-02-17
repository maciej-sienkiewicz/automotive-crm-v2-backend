package pl.detailing.crm.vehicle.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.UUID

@Service
class VehicleNoteService(
    private val vehicleNoteRepository: VehicleNoteRepository,
    private val vehicleRepository: VehicleRepository,
    private val auditService: AuditService
) {

    @Transactional(readOnly = true)
    suspend fun listNotes(vehicleId: UUID, studioId: UUID): List<VehicleNoteItem> =
        withContext(Dispatchers.IO) {
            vehicleNoteRepository
                .findByVehicleIdAndStudioIdOrderByCreatedAtDesc(vehicleId, studioId)
                .map { it.toItem() }
        }

    @Transactional
    suspend fun addNote(
        vehicleId: UUID,
        studioId: UUID,
        content: String,
        createdBy: UUID,
        createdByName: String
    ): VehicleNoteItem = withContext(Dispatchers.IO) {
        vehicleRepository.findByIdAndStudioId(vehicleId, studioId)
            ?: throw EntityNotFoundException("Vehicle not found")

        val now = Instant.now()
        val entity = VehicleNoteEntity(
            id = UUID.randomUUID(),
            studioId = studioId,
            vehicleId = vehicleId,
            content = content.trim(),
            createdBy = createdBy,
            createdByName = createdByName,
            createdAt = now,
            updatedAt = now
        )

        val saved = vehicleNoteRepository.save(entity)

        auditService.log(LogAuditCommand(
            studioId = StudioId(studioId),
            userId = UserId(createdBy),
            userDisplayName = createdByName,
            module = AuditModule.VEHICLE,
            entityId = vehicleId.toString(),
            action = AuditAction.NOTE_ADDED,
            changes = listOf(FieldChange("content", null, content.trim())),
            metadata = mapOf("noteId" to saved.id.toString())
        ))

        saved.toItem()
    }

    @Transactional
    suspend fun updateNote(
        noteId: UUID,
        studioId: UUID,
        content: String,
        updatedBy: UUID? = null,
        updatedByName: String? = null
    ): VehicleNoteItem = withContext(Dispatchers.IO) {
        val entity = vehicleNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        val oldContent = entity.content
        entity.content = content.trim()
        entity.updatedAt = Instant.now()

        val saved = vehicleNoteRepository.save(entity)

        if (updatedBy != null) {
            auditService.log(LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(updatedBy),
                userDisplayName = updatedByName ?: "",
                module = AuditModule.VEHICLE,
                entityId = entity.vehicleId.toString(),
                action = AuditAction.NOTE_UPDATED,
                changes = listOf(FieldChange("content", oldContent, content.trim())),
                metadata = mapOf("noteId" to noteId.toString())
            ))
        }

        saved.toItem()
    }

    @Transactional
    suspend fun deleteNote(
        noteId: UUID,
        studioId: UUID,
        deletedBy: UUID? = null,
        deletedByName: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val entity = vehicleNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        vehicleNoteRepository.delete(entity)

        if (deletedBy != null) {
            auditService.log(LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(deletedBy),
                userDisplayName = deletedByName ?: "",
                module = AuditModule.VEHICLE,
                entityId = entity.vehicleId.toString(),
                action = AuditAction.NOTE_DELETED,
                metadata = mapOf("noteId" to noteId.toString())
            ))
        }
    }

    private fun VehicleNoteEntity.toItem() = VehicleNoteItem(
        id = id.toString(),
        content = content,
        createdBy = createdBy.toString(),
        createdByName = createdByName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

data class VehicleNoteItem(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
