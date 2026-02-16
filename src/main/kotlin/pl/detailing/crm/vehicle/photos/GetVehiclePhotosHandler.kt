package pl.detailing.crm.vehicle.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.time.Instant

/**
 * Handler for getting vehicle photos (directly attached to vehicle) with presigned download URLs
 */
@Service
class GetVehiclePhotosHandler(
    private val vehicleRepository: VehicleRepository,
    private val photoSessionService: PhotoSessionService
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetVehiclePhotosCommand): GetVehiclePhotosResult {
        // 1. Fetch vehicle entity with studio isolation and eager-load photos
        val vehicleEntity = vehicleRepository.findByIdAndStudioIdWithPhotos(
            id = command.vehicleId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found: ${command.vehicleId}")

        // 2. Get photos and generate presigned URLs
        val photoResponses = vehicleEntity.photos.map { photoEntity ->
            VehiclePhotoInfo(
                id = photoEntity.id.toString(),
                fileName = photoEntity.fileName,
                description = photoEntity.description,
                uploadedAt = photoEntity.uploadedAt,
                thumbnailUrl = photoSessionService.generateDownloadUrl(photoEntity.fileId),
                fullSizeUrl = photoSessionService.generateDownloadUrl(photoEntity.fileId)
            )
        }

        return GetVehiclePhotosResult(photos = photoResponses)
    }
}

/**
 * Command to get vehicle photos
 */
data class GetVehiclePhotosCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId
)

/**
 * Result containing photos with presigned URLs
 */
data class GetVehiclePhotosResult(
    val photos: List<VehiclePhotoInfo>
)

/**
 * Photo info with presigned URLs
 */
data class VehiclePhotoInfo(
    val id: String,
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant,
    val thumbnailUrl: String,
    val fullSizeUrl: String
)
