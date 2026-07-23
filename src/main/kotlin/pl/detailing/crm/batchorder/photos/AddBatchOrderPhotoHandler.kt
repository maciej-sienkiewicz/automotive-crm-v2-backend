package pl.detailing.crm.batchorder.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.time.Instant
import java.util.UUID

@Service
class AddBatchOrderPhotoHandler(
    private val entryRepository: BatchOrderEntryRepository,
    private val photoRepository: BatchOrderPhotoRepository,
    private val photoSessionService: PhotoSessionService
) {
    @Transactional
    suspend fun handle(command: AddBatchOrderPhotoCommand): AddBatchOrderPhotoResult {
        val entry = entryRepository.findByIdAndStudioId(command.entryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Entry not found: ${command.entryId}")

        val photoId = UUID.randomUUID()
        val fileId = "${command.studioId.value}/batch-orders/${command.entryId.value}/photos/$photoId"

        val photo = BatchOrderPhotoEntity(
            id = photoId,
            studioId = command.studioId.value,
            entryId = command.entryId.value,
            contractorId = entry.contractorId,
            fileId = fileId,
            fileName = command.fileName,
            description = command.description,
            uploadedAt = Instant.now(),
            uploadedBy = command.userId.value,
            uploadedByName = command.userName
        )
        photoRepository.save(photo)

        val contentType = PhotoSessionService.contentTypeFromFileName(command.fileName)
        val uploadUrl = photoSessionService.generateSimpleUploadUrl(fileId, contentType)

        return AddBatchOrderPhotoResult(
            photoId = photoId.toString(),
            uploadUrl = uploadUrl,
            fileId = fileId
        )
    }
}

data class AddBatchOrderPhotoCommand(
    val entryId: BatchOrderEntryId,
    val studioId: StudioId,
    val fileName: String,
    val description: String?,
    val userId: UserId,
    val userName: String?
)

data class AddBatchOrderPhotoResult(
    val photoId: String,
    val uploadUrl: String,
    val fileId: String
)
