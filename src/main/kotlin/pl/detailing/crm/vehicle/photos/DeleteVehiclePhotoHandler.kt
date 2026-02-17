package pl.detailing.crm.vehicle.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.util.UUID

/**
 * Handler for deleting a photo from a vehicle.
 *
 * This removes the photo from the vehicle and deletes the file from S3.
 */
@Service
class DeleteVehiclePhotoHandler(
    private val vehicleRepository: VehicleRepository,
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: DeleteVehiclePhotoCommand): Unit = withContext(Dispatchers.IO) {
        // 1. Find vehicle with studio isolation
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            id = command.vehicleId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found: ${command.vehicleId}")

        // 2. Force load photos
        vehicleEntity.photos.size

        // 3. Find the photo to delete
        val photoToDelete = vehicleEntity.photos.find { it.id == command.photoId.value }
            ?: throw EntityNotFoundException("Photo not found: ${command.photoId}")

        val deletedFileName = photoToDelete.fileName

        // 4. Remove photo from list
        vehicleEntity.photos.remove(photoToDelete)

        vehicleRepository.save(vehicleEntity)

        val displayName = listOfNotNull(vehicleEntity.brand, vehicleEntity.model, vehicleEntity.licensePlate).joinToString(" ")

        if (command.userId != null) {
            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.VEHICLE,
                entityId = command.vehicleId.value.toString(),
                entityDisplayName = displayName,
                action = AuditAction.PHOTO_DELETED,
                changes = listOf(FieldChange("fileName", deletedFileName, null)),
                metadata = mapOf("photoId" to command.photoId.value.toString())
            ))
        }

        // 5. Delete file from S3
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
 * Command to delete a photo from a vehicle
 */
data class DeleteVehiclePhotoCommand(
    val vehicleId: VehicleId,
    val photoId: VehiclePhotoId,
    val studioId: StudioId,
    val userId: UserId? = null,
    val userName: String? = null
)
