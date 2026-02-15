package pl.detailing.crm.visit.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import org.springframework.beans.factory.annotation.Value

/**
 * Handler for deleting a photo from a visit.
 *
 * This removes the photo from the visit and deletes the file from S3.
 */
@Service
class DeleteVisitPhotoHandler(
    private val visitRepository: VisitRepository,
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    @Transactional
    suspend fun handle(command: DeleteVisitPhotoCommand): Unit = withContext(Dispatchers.IO) {
        // 1. Find visit with studio isolation
        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        // 2. Force load photos
        visitEntity.photos.size

        // 3. Convert to domain
        val visit = visitEntity.toDomain()

        // 4. Find the photo to delete
        val photoToDelete = visit.photos.find { it.id.value == command.photoId.value }
            ?: throw EntityNotFoundException("Photo not found: ${command.photoId}")

        // 5. Remove photo from list
        val updatedPhotos = visit.photos.filter { it.id.value != command.photoId.value }

        // 6. Update visit entity
        visitEntity.photos.clear()
        visitEntity.photos.addAll(updatedPhotos.map { photo ->
            pl.detailing.crm.visit.infrastructure.VisitPhotoEntity(
                id = photo.id.value,
                visit = visitEntity,
                fileId = photo.fileId,
                fileName = photo.fileName,
                description = photo.description,
                uploadedAt = photo.uploadedAt
            )
        })

        visitRepository.save(visitEntity)

        // 7. Delete file from S3
        try {
            val deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(photoToDelete.fileId)
                .build()

            s3Client.deleteObject(deleteObjectRequest)
        } catch (e: Exception) {
            // Log but don't fail the operation - photo already removed from DB
            // S3 cleanup can happen later if needed
            println("Warning: Could not delete photo from S3: ${photoToDelete.fileId}")
        }
    }
}

/**
 * Command to delete a photo from a visit
 */
data class DeleteVisitPhotoCommand(
    val visitId: VisitId,
    val photoId: VisitPhotoId,
    val studioId: StudioId
)
