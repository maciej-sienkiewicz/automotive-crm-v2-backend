package pl.detailing.crm.vehicle.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.time.Instant

/**
 * Handler for getting complete vehicle gallery including:
 * - Photos attached directly to the vehicle
 * - Photos from all visits associated with this vehicle
 *
 * Results are paginated for efficient loading in UI carousel/gallery.
 */
@Service
class GetVehicleGalleryHandler(
    private val vehicleRepository: VehicleRepository,
    private val visitRepository: VisitRepository,
    private val photoSessionService: PhotoSessionService
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetVehicleGalleryCommand): GetVehicleGalleryResult {
        // 1. Verify vehicle exists with studio isolation
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            id = command.vehicleId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found: ${command.vehicleId}")

        // 2. Get photos directly attached to vehicle
        vehicleEntity.photos.size // Force load
        val directPhotos = vehicleEntity.photos.map { photoEntity ->
            GalleryPhotoInfo(
                id = photoEntity.id.toString(),
                source = PhotoSource.VEHICLE,
                sourceId = command.vehicleId.value.toString(),
                fileName = photoEntity.fileName,
                description = photoEntity.description,
                uploadedAt = photoEntity.uploadedAt,
                thumbnailUrl = photoSessionService.generateDownloadUrl(photoEntity.fileId),
                fullSizeUrl = photoSessionService.generateDownloadUrl(photoEntity.fileId)
            )
        }

        // 3. Get all visits for this vehicle
        val visits = visitRepository.findByVehicleIdAndStudioId(
            vehicleId = command.vehicleId.value,
            studioId = command.studioId.value
        )

        // 4. Collect photos from all visits
        val visitPhotos = visits.flatMap { visit ->
            visit.photos.size // Force load
            visit.photos.map { photoEntity ->
                GalleryPhotoInfo(
                    id = photoEntity.id.toString(),
                    source = PhotoSource.VISIT,
                    sourceId = visit.id.toString(),
                    fileName = photoEntity.fileName,
                    description = photoEntity.description,
                    uploadedAt = photoEntity.uploadedAt,
                    thumbnailUrl = photoSessionService.generateDownloadUrl(photoEntity.fileId),
                    fullSizeUrl = photoSessionService.generateDownloadUrl(photoEntity.fileId),
                    visitNumber = visit.visitNumber
                )
            }
        }

        // 5. Combine all photos and sort by upload date (newest first)
        val allPhotos = (directPhotos + visitPhotos).sortedByDescending { it.uploadedAt }

        // 6. Apply pagination
        val totalPhotos = allPhotos.size
        val startIndex = (command.page - 1) * command.pageSize
        val endIndex = minOf(startIndex + command.pageSize, totalPhotos)

        val paginatedPhotos = if (startIndex < totalPhotos) {
            allPhotos.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        return GetVehicleGalleryResult(
            photos = paginatedPhotos,
            total = totalPhotos,
            page = command.page,
            pageSize = command.pageSize,
            totalPages = (totalPhotos + command.pageSize - 1) / command.pageSize
        )
    }
}

/**
 * Command to get vehicle gallery
 */
data class GetVehicleGalleryCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20
)

/**
 * Result containing paginated gallery photos
 */
data class GetVehicleGalleryResult(
    val photos: List<GalleryPhotoInfo>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

/**
 * Photo source type
 */
enum class PhotoSource {
    VEHICLE,  // Photo attached directly to vehicle
    VISIT     // Photo from a visit
}

/**
 * Gallery photo info with source tracking
 */
data class GalleryPhotoInfo(
    val id: String,
    val source: PhotoSource,
    val sourceId: String,  // vehicleId or visitId
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant,
    val thumbnailUrl: String,
    val fullSizeUrl: String,
    val visitNumber: String? = null  // Only populated for VISIT photos
)
