package pl.detailing.crm.visit.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.time.Instant

/**
 * Handler for getting visit photos with presigned download URLs
 */
@Service
class GetVisitPhotosHandler(
    private val visitRepository: VisitRepository,
    private val photoSessionService: PhotoSessionService
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetVisitPhotosCommand): GetVisitPhotosResult {
        // 1. Fetch visit entity with studio isolation and eager-load photos
        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        // 2. Convert to domain model (photos already loaded)
        val visit = visitEntity.toDomain()

        // 3. Get photos and generate presigned URLs
        val photoResponses = visit.photos.map { photo ->
            VisitPhotoInfo(
                id = photo.id.value.toString(),
                fileName = photo.fileName,
                description = photo.description,
                uploadedAt = photo.uploadedAt,
                thumbnailUrl = photoSessionService.generateDownloadUrl(photo.fileId),
                fullSizeUrl = photoSessionService.generateDownloadUrl(photo.fileId)
            )
        }

        return GetVisitPhotosResult(photos = photoResponses)
    }
}

/**
 * Command to get visit photos
 */
data class GetVisitPhotosCommand(
    val visitId: VisitId,
    val studioId: StudioId
)

/**
 * Result containing photos with presigned URLs
 */
data class GetVisitPhotosResult(
    val photos: List<VisitPhotoInfo>
)

/**
 * Photo info with presigned URLs
 */
data class VisitPhotoInfo(
    val id: String,
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant,
    val thumbnailUrl: String,
    val fullSizeUrl: String
)
