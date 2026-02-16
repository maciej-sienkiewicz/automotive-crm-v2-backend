package pl.detailing.crm.vehicle.photos

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.VehiclePhoto
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.vehicle.infrastructure.VehiclePhotoEntity
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.time.Instant

/**
 * Handler for adding a photo directly to a vehicle (not associated with a visit).
 *
 * This generates a presigned upload URL and adds the photo metadata to the vehicle.
 * Frontend should upload the image to the returned URL.
 */
@Service
class AddVehiclePhotoHandler(
    private val vehicleRepository: VehicleRepository,
    private val photoSessionService: PhotoSessionService
) {

    @Transactional
    suspend fun handle(command: AddVehiclePhotoCommand): AddVehiclePhotoResult {
        // 1. Find vehicle with studio isolation
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            id = command.vehicleId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found: ${command.vehicleId}")

        // 2. Force load photos
        vehicleEntity.photos.size

        // 3. Generate unique file ID for S3
        val photoId = VehiclePhotoId.random()
        val fileId = "${command.studioId.value}/vehicles/${command.vehicleId.value}/photos/${photoId.value}"

        // 4. Create new photo entity
        val newPhotoEntity = VehiclePhotoEntity(
            id = photoId.value,
            vehicle = vehicleEntity,
            fileId = fileId,
            fileName = command.fileName,
            description = command.description,
            uploadedAt = Instant.now()
        )

        // 5. Add photo to vehicle
        vehicleEntity.photos.add(newPhotoEntity)

        vehicleRepository.save(vehicleEntity)

        // 6. Generate presigned upload URL
        val uploadUrl = photoSessionService.generateSimpleUploadUrl(fileId, "image/jpeg")

        return AddVehiclePhotoResult(
            photoId = photoId.value.toString(),
            uploadUrl = uploadUrl,
            fileId = fileId
        )
    }
}

/**
 * Command to add a photo to a vehicle
 */
data class AddVehiclePhotoCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val fileName: String,
    val description: String?
)

/**
 * Result containing upload URL for the new photo
 */
data class AddVehiclePhotoResult(
    val photoId: String,
    val uploadUrl: String,
    val fileId: String
)
