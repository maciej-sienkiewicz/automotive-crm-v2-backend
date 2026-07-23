package pl.detailing.crm.batchorder.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.util.UUID

@Service
class DeleteBatchOrderPhotoHandler(
    private val photoRepository: BatchOrderPhotoRepository
) {
    @Transactional
    suspend fun handle(command: DeleteBatchOrderPhotoCommand) {
        val photoId = UUID.fromString(command.photoId)
        photoRepository.findByIdAndStudioId(photoId, command.studioId.value)
            ?: throw EntityNotFoundException("Photo not found: ${command.photoId}")
        photoRepository.deleteById(photoId)
    }
}

data class DeleteBatchOrderPhotoCommand(
    val photoId: String,
    val studioId: StudioId
)
