package pl.detailing.crm.batchorder.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.visit.infrastructure.PhotoSessionService

@Service
class ListBatchOrderPhotosHandler(
    private val photoRepository: BatchOrderPhotoRepository,
    private val photoSessionService: PhotoSessionService
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: ListBatchOrderPhotosCommand): List<BatchOrderPhotoItem> {
        return photoRepository.findByEntryIdAndStudioId(command.entryId.value, command.studioId.value)
            .sortedByDescending { it.uploadedAt }
            .map { photo ->
                BatchOrderPhotoItem(
                    id = photo.id.toString(),
                    fileId = photo.fileId,
                    fileName = photo.fileName,
                    description = photo.description,
                    url = photoSessionService.generateDownloadUrl(photo.fileId),
                    uploadedAt = photo.uploadedAt.toString(),
                    uploadedByName = photo.uploadedByName
                )
            }
    }
}

data class ListBatchOrderPhotosCommand(
    val entryId: BatchOrderEntryId,
    val studioId: StudioId
)

data class BatchOrderPhotoItem(
    val id: String,
    val fileId: String,
    val fileName: String,
    val description: String?,
    val url: String,
    val uploadedAt: String,
    val uploadedByName: String?
)
