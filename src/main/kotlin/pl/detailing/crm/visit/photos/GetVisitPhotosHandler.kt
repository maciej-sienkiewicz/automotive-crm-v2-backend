package pl.detailing.crm.visit.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.visit.domain.VisitId
import pl.detailing.crm.visit.domain.VisitPhoto
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

    suspend fun handle(command: GetVisitPhotosCommand): GetVisitPhotosResult = withContext(Dispatchers.IO) {
        // 1. Fetch visit
        val visit = visitRepository.findById(command.visitId)
            ?: throw NotFoundException("Visit not found")

        // 2. Verify studio access
        if (visit.studioId != command.studioId) {
            throw ForbiddenException("Access denied to this visit")
        }

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

        GetVisitPhotosResult(photos = photoResponses)
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
