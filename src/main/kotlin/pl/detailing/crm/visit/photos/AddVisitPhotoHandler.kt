package pl.detailing.crm.visit.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.VisitPhoto
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.time.Instant
import java.util.UUID

/**
 * Handler for adding a photo to an existing visit.
 *
 * This generates a presigned upload URL and adds the photo metadata to the visit.
 * Frontend should upload the image to the returned URL.
 */
@Service
class AddVisitPhotoHandler(
    private val visitRepository: VisitRepository,
    private val photoSessionService: PhotoSessionService
) {

    @Transactional
    suspend fun handle(command: AddVisitPhotoCommand): AddVisitPhotoResult {
        // 1. Find visit with studio isolation
        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        // 2. Force load photos
        visitEntity.photos.size

        // 3. Convert to domain
        val visit = visitEntity.toDomain()

        // 4. Generate unique file ID for S3
        val photoId = VisitPhotoId.random()
        val fileId = "${command.studioId.value}/visits/${command.visitId.value}/photos/${photoId.value}"

        // 5. Create new photo
        val newPhoto = VisitPhoto(
            id = photoId,
            fileId = fileId,
            fileName = command.fileName,
            description = command.description,
            uploadedAt = Instant.now()
        )

        // 6. Add photo to visit
        val updatedPhotos = visit.photos + newPhoto

        // 7. Update visit entity
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

        // 8. Generate presigned upload URL
        val uploadUrl = photoSessionService.generateSimpleUploadUrl(fileId, "image/jpeg")

        return AddVisitPhotoResult(
            photoId = photoId.value.toString(),
            uploadUrl = uploadUrl,
            fileId = fileId
        )
    }
}

/**
 * Command to add a photo to a visit
 */
data class AddVisitPhotoCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val fileName: String,
    val description: String?
)

/**
 * Result containing upload URL for the new photo
 */
data class AddVisitPhotoResult(
    val photoId: String,
    val uploadUrl: String,
    val fileId: String
)
