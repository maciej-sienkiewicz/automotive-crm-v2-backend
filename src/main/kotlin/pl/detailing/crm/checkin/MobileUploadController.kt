package pl.detailing.crm.checkin

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.checkin.qr.AnnotationPointData
import pl.detailing.crm.checkin.qr.AnnotationStrokeData
import pl.detailing.crm.checkin.qr.CheckinDamagePointsService
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.DamagePointData
import pl.detailing.crm.checkin.qr.DamagePointPhotoData
import pl.detailing.crm.checkin.qr.DamagePointsResult
import pl.detailing.crm.checkin.qr.UploadContextTokenService
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * Session-free controller for mobile QR photo uploads.
 *
 * Authentication is performed exclusively via the [X-Upload-Token] header.
 * There is NO JSESSIONID / Spring Security context involved; access to this
 * controller is permitted in [SecurityConfig] without authentication.
 *
 * Tenant isolation is enforced by reading the tenantId ONLY from the Redis
 * token metadata — never from the request itself.  This guarantees that
 * photos from one salon (tenant) can never be placed into another salon's
 * checkin folder.
 */
@RestController
@RequestMapping("/api/mobile/checkin")
class MobileUploadController(
    private val uploadContextTokenService: UploadContextTokenService,
    private val checkinPhotoService: CheckinPhotoService,
    private val checkinDamagePointsService: CheckinDamagePointsService
) {

    /**
     * Upload a photo file from a mobile device.
     *
     * ```
     * POST /api/mobile/checkin/photos
     * X-Upload-Token: <token>
     * Content-Type: multipart/form-data
     *
     * photo=<file>
     * ```
     *
     * Returns 201 Created with the photo metadata on success.
     * Returns 403 Forbidden if the token is invalid or expired.
     */
    @PostMapping("/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPhoto(
        @RequestHeader("X-Upload-Token") token: String,
        @RequestPart("photo") file: MultipartFile
    ): ResponseEntity<MobileUploadResponse> = runBlocking {
        val metadata = uploadContextTokenService.validateToken(token)
            ?: throw ForbiddenException("Nieprawidłowy lub wygasły token przesyłania zdjęć")

        if (file.isEmpty) {
            throw ValidationException("Przesłany plik jest pusty")
        }

        val contentType = file.contentType
            ?: throw ValidationException("Brak nagłówka Content-Type pliku")

        val result = checkinPhotoService.uploadPhoto(
            metadata = metadata,
            fileName = file.originalFilename ?: file.name,
            contentType = contentType,
            fileBytes = file.bytes
        )

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                MobileUploadResponse(
                    photoId = result.photoId,
                    fileName = result.fileName,
                    checkinId = metadata.checkinId,
                    uploadedAt = result.uploadedAt
                )
            )
    }

    /**
     * Retrieve the upload context for a given token.
     * Useful for the mobile page to show which checkin/salon is associated
     * with the scanned QR code before the user starts uploading.
     *
     * ```
     * GET /api/mobile/checkin/context
     * X-Upload-Token: <token>
     * ```
     */
    @GetMapping("/context")
    fun getUploadContext(
        @RequestHeader("X-Upload-Token") token: String
    ): ResponseEntity<MobileContextResponse> {
        val metadata = uploadContextTokenService.validateToken(token)
            ?: throw ForbiddenException("Nieprawidłowy lub wygasły token przesyłania zdjęć")

        return ResponseEntity.ok(
            MobileContextResponse(
                checkinId = metadata.checkinId,
                tenantId = metadata.tenantId
            )
        )
    }

    /**
     * Save (replace) the full list of damage points for the current check-in.
     *
     * PUT /api/mobile/checkin/damage-points
     * X-Upload-Token: <token>
     *
     * Replaces all existing damage points with the provided list.
     * An empty list clears all previously saved points.
     * After saving, broadcasts a CHECKIN_DAMAGE_UPDATED WebSocket event.
     */
    @PutMapping("/damage-points")
    fun saveDamagePoints(
        @RequestHeader("X-Upload-Token") token: String,
        @RequestBody request: MobileDamagePointsRequest
    ): ResponseEntity<MobileDamagePointsResponse> {
        val metadata = uploadContextTokenService.validateToken(token)
            ?: throw ForbiddenException("Nieprawidłowy lub wygasły token przesyłania zdjęć")

        request.damagePoints.forEach { point ->
            if (point.x < 0.0 || point.x > 100.0 || point.y < 0.0 || point.y > 100.0) {
                throw UnprocessableEntityException(
                    "Wartości x i y muszą być w zakresie 0–100 (punkt id=${point.id})"
                )
            }
            point.photos.orEmpty().forEach { photo ->
                photo.strokes.orEmpty().forEach { stroke ->
                    stroke.points.orEmpty().forEach { p ->
                        if (p.x < 0.0 || p.x > 100.0 || p.y < 0.0 || p.y > 100.0) {
                            throw UnprocessableEntityException(
                                "Współrzędne adnotacji muszą być w zakresie 0–100 (punkt id=${point.id}, zdjęcie=${photo.photoId})"
                            )
                        }
                    }
                }
            }
        }

        val vehicleType = request.vehicleType?.takeIf { it in ALLOWED_VEHICLE_TYPES }

        // Resolve stable S3 keys for the referenced photos (single prefix listing)
        val tempKeysByPhotoId = checkinPhotoService.listTempPhotoKeys(
            tenantId = metadata.tenantId,
            checkinId = metadata.checkinId
        )

        val result = checkinDamagePointsService.saveDamagePoints(
            tenantId = metadata.tenantId,
            checkinId = metadata.checkinId,
            vehicleType = vehicleType,
            damagePoints = request.damagePoints.map { point ->
                DamagePointData(
                    id = point.id,
                    x = point.x,
                    y = point.y,
                    note = point.note,
                    photos = point.photos.orEmpty().map { photo ->
                        DamagePointPhotoData(
                            photoId = photo.photoId,
                            s3Key = tempKeysByPhotoId[photo.photoId],
                            strokes = photo.strokes.orEmpty().map { stroke ->
                                AnnotationStrokeData(
                                    color = stroke.color,
                                    width = stroke.width,
                                    points = stroke.points.orEmpty().map { AnnotationPointData(x = it.x, y = it.y) }
                                )
                            }
                        )
                    }
                )
            }
        )

        return ResponseEntity.ok(result.toResponse(checkinPhotoService))
    }

    /**
     * Retrieve the current damage points for the check-in associated with the token.
     *
     * GET /api/mobile/checkin/damage-points
     * X-Upload-Token: <token>
     *
     * Returns an empty list with savedAt=null if no points have been saved yet.
     */
    @GetMapping("/damage-points")
    fun getDamagePoints(
        @RequestHeader("X-Upload-Token") token: String
    ): ResponseEntity<MobileDamagePointsResponse> {
        val metadata = uploadContextTokenService.validateToken(token)
            ?: throw ForbiddenException("Nieprawidłowy lub wygasły token przesyłania zdjęć")

        val result = checkinDamagePointsService.getDamagePoints(
            tenantId = metadata.tenantId,
            checkinId = metadata.checkinId
        )

        return ResponseEntity.ok(result.toResponse(checkinPhotoService))
    }
}

val ALLOWED_VEHICLE_TYPES = setOf("cabrio", "coupe", "hatchback", "kombi", "sedan", "suv", "van")

private fun DamagePointsResult.toResponse(checkinPhotoService: CheckinPhotoService) =
    MobileDamagePointsResponse(
        checkinId = checkinId,
        vehicleType = vehicleType,
        damagePoints = damagePoints.map { point ->
            MobileDamagePointDto(
                id = point.id,
                x = point.x,
                y = point.y,
                note = point.note,
                photos = point.photos.map { photo ->
                    MobileDamagePointPhotoDto(
                        photoId = photo.photoId,
                        thumbnailUrl = photo.s3Key?.let { key ->
                            runCatching { checkinPhotoService.generateDownloadUrl(key) }.getOrNull()
                        },
                        strokes = photo.strokes.map { stroke ->
                            MobileAnnotationStrokeDto(
                                color = stroke.color,
                                width = stroke.width,
                                points = stroke.points.map { MobileAnnotationPointDto(x = it.x, y = it.y) }
                            )
                        }
                    )
                }
            )
        },
        savedAt = savedAt
    )

// ----- DTOs -----

data class MobileUploadResponse(
    val photoId: String,
    val fileName: String,
    val checkinId: String,
    val uploadedAt: Instant
)

data class MobileContextResponse(
    val checkinId: String,
    val tenantId: String
)

data class MobileDamagePointDto(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String?,
    val photos: List<MobileDamagePointPhotoDto>? = null
)

data class MobileDamagePointPhotoDto(
    val photoId: String,
    /** Presigned download URL — response only, ignored on input */
    val thumbnailUrl: String? = null,
    val strokes: List<MobileAnnotationStrokeDto>? = null
)

data class MobileAnnotationStrokeDto(
    val color: String = "#EF4444",
    val width: Double = 1.0,
    val points: List<MobileAnnotationPointDto>? = null
)

data class MobileAnnotationPointDto(
    val x: Double,
    val y: Double
)

data class MobileDamagePointsRequest(
    val damagePoints: List<MobileDamagePointDto>,
    /** Vehicle body type the points were placed on (sedan, suv, ...) */
    val vehicleType: String? = null
)

data class MobileDamagePointsResponse(
    val checkinId: String,
    val vehicleType: String? = null,
    val damagePoints: List<MobileDamagePointDto>,
    val savedAt: Instant?
)
