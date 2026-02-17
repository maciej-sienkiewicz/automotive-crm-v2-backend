package pl.detailing.crm.vehicle.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.UUID

@Service
class VehicleNoteService(
    private val vehicleNoteRepository: VehicleNoteRepository,
    private val vehicleRepository: VehicleRepository
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

        vehicleNoteRepository.save(entity).toItem()
    }

    @Transactional
    suspend fun updateNote(
        noteId: UUID,
        studioId: UUID,
        content: String
    ): VehicleNoteItem = withContext(Dispatchers.IO) {
        val entity = vehicleNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        entity.content = content.trim()
        entity.updatedAt = Instant.now()

        vehicleNoteRepository.save(entity).toItem()
    }

    @Transactional
    suspend fun deleteNote(noteId: UUID, studioId: UUID): Unit = withContext(Dispatchers.IO) {
        val entity = vehicleNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        vehicleNoteRepository.delete(entity)
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
