package pl.detailing.crm.vehicle

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VehiclePhotoId
import pl.detailing.crm.vehicle.photos.*
import java.time.Instant

/**
 * Controller for vehicle photo operations
 */
@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/photos")
class VehiclePhotoController(
    private val getVehiclePhotosHandler: GetVehiclePhotosHandler,
    private val addVehiclePhotoHandler: AddVehiclePhotoHandler,
    private val deleteVehiclePhotoHandler: DeleteVehiclePhotoHandler,
    private val getVehicleGalleryHandler: GetVehicleGalleryHandler
) {

    /**
     * Get photos directly attached to the vehicle (not from visits)
     * GET /api/vehicles/{vehicleId}/photos
     */
    @GetMapping
    fun getVehiclePhotos(
        @PathVariable vehicleId: String
    ): ResponseEntity<VehiclePhotosResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehiclePhotosCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId
        )

        val result = getVehiclePhotosHandler.handle(command)

        ResponseEntity.ok(VehiclePhotosResponse(
            photos = result.photos.map { photo ->
                VehiclePhotoResponse(
                    id = photo.id,
                    fileName = photo.fileName,
                    description = photo.description,
                    uploadedAt = photo.uploadedAt,
                    thumbnailUrl = photo.thumbnailUrl,
                    fullSizeUrl = photo.fullSizeUrl
                )
            }
        ))
    }

    /**
     * Get complete vehicle gallery (photos from vehicle + all visits)
     * GET /api/vehicles/{vehicleId}/photos/gallery?page=1&pageSize=20
     */
    @GetMapping("/gallery")
    fun getVehicleGallery(
        @PathVariable vehicleId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") pageSize: Int
    ): ResponseEntity<VehicleGalleryResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehicleGalleryCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, pageSize)) // Limit page size to 100
        )

        val result = getVehicleGalleryHandler.handle(command)

        ResponseEntity.ok(VehicleGalleryResponse(
            photos = result.photos.map { photo ->
                GalleryPhotoResponse(
                    id = photo.id,
                    source = photo.source.name,
                    sourceId = photo.sourceId,
                    fileName = photo.fileName,
                    description = photo.description,
                    uploadedAt = photo.uploadedAt,
                    thumbnailUrl = photo.thumbnailUrl,
                    fullSizeUrl = photo.fullSizeUrl,
                    visitNumber = photo.visitNumber
                )
            },
            pagination = GalleryPaginationMetadata(
                total = result.total,
                page = result.page,
                pageSize = result.pageSize,
                totalPages = result.totalPages
            )
        ))
    }

    /**
     * Add a photo directly to the vehicle (not associated with a visit)
     * POST /api/vehicles/{vehicleId}/photos
     */
    @PostMapping
    fun addVehiclePhoto(
        @PathVariable vehicleId: String,
        @RequestBody request: AddVehiclePhotoRequest
    ): ResponseEntity<AddVehiclePhotoResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AddVehiclePhotoCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            fileName = request.fileName,
            description = request.description
        )

        val result = addVehiclePhotoHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(AddVehiclePhotoResponse(
            photoId = result.photoId,
            uploadUrl = result.uploadUrl,
            fileId = result.fileId
        ))
    }

    /**
     * Delete a photo from the vehicle
     * DELETE /api/vehicles/{vehicleId}/photos/{photoId}
     */
    @DeleteMapping("/{photoId}")
    fun deleteVehiclePhoto(
        @PathVariable vehicleId: String,
        @PathVariable photoId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = DeleteVehiclePhotoCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            photoId = VehiclePhotoId.fromString(photoId),
            studioId = principal.studioId
        )

        deleteVehiclePhotoHandler.handle(command)

        ResponseEntity.noContent().build()
    }
}

/**
 * Response for vehicle photos list
 */
data class VehiclePhotosResponse(
    val photos: List<VehiclePhotoResponse>
)

/**
 * Individual vehicle photo response with presigned URLs
 */
data class VehiclePhotoResponse(
    val id: String,
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant,
    val thumbnailUrl: String,
    val fullSizeUrl: String
)

/**
 * Response for vehicle gallery (all photos from vehicle and visits)
 */
data class VehicleGalleryResponse(
    val photos: List<GalleryPhotoResponse>,
    val pagination: GalleryPaginationMetadata
)

/**
 * Gallery photo response with source tracking
 */
data class GalleryPhotoResponse(
    val id: String,
    val source: String,  // "VEHICLE" or "VISIT"
    val sourceId: String,  // vehicleId or visitId
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant,
    val thumbnailUrl: String,
    val fullSizeUrl: String,
    val visitNumber: String?  // Only populated for VISIT photos
)

/**
 * Pagination metadata for gallery
 */
data class GalleryPaginationMetadata(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

/**
 * Request to add a photo to a vehicle
 */
data class AddVehiclePhotoRequest(
    val fileName: String,
    val description: String?
)

/**
 * Response when adding a photo to a vehicle
 */
data class AddVehiclePhotoResponse(
    val photoId: String,
    val uploadUrl: String,
    val fileId: String
)
