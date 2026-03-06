package pl.detailing.crm.checkin

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.UploadContextTokenService
import pl.detailing.crm.shared.ForbiddenException
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
    private val checkinPhotoService: CheckinPhotoService
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
}

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
