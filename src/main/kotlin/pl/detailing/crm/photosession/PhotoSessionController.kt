package pl.detailing.crm.photosession

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.SessionPhotoInfo
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/photo-sessions")
class PhotoSessionController(
    private val photoSessionService: PhotoSessionService
) {

    /**
     * Create a new photo upload session
     *
     * POST /api/photo-sessions
     */
    @PostMapping
    fun createSession(
        @RequestBody request: CreateSessionRequest
    ): ResponseEntity<CreateSessionResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val session = photoSessionService.createSession(
            studioId = principal.studioId,
            appointmentId = AppointmentId.fromString(request.appointmentId),
            userId = principal.userId
        )

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(CreateSessionResponse(
                sessionId = session.id.toString(),
                token = session.token,
                expiresAt = session.expiresAt
            ))
    }

    /**
     * Generate presigned upload URL for a photo
     *
     * POST /api/photo-sessions/{sessionId}/upload-url
     */
    @PostMapping("/{sessionId}/upload-url")
    fun generateUploadUrl(
        @PathVariable sessionId: String,
        @RequestBody request: GenerateUploadUrlRequest
    ): ResponseEntity<GenerateUploadUrlResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = photoSessionService.generateUploadUrl(
            sessionId = UUID.fromString(sessionId),
            studioId = principal.studioId,
            fileName = request.fileName,
            photoType = PhotoType.valueOf(request.photoType),
            contentType = request.contentType,
            fileSize = request.fileSize,
            sessionToken = request.sessionToken
        )

        ResponseEntity.ok(GenerateUploadUrlResponse(
            photoId = result.photoId.toString(),
            uploadUrl = result.uploadUrl,
            expiresAt = result.expiresAt
        ))
    }

    /**
     * Get all photos in a session
     *
     * GET /api/photo-sessions/{sessionId}/photos
     */
    @GetMapping("/{sessionId}/photos")
    fun getSessionPhotos(
        @PathVariable sessionId: String
    ): ResponseEntity<GetSessionPhotosResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val photos = photoSessionService.getSessionPhotos(
            sessionId = UUID.fromString(sessionId),
            studioId = principal.studioId
        )

        ResponseEntity.ok(GetSessionPhotosResponse(
            photos = photos.map { it.toResponse() }
        ))
    }

    /**
     * Delete a photo from a session
     *
     * DELETE /api/photo-sessions/{sessionId}/photos/{photoId}
     */
    @DeleteMapping("/{sessionId}/photos/{photoId}")
    fun deleteSessionPhoto(
        @PathVariable sessionId: String,
        @PathVariable photoId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        photoSessionService.deleteSessionPhoto(
            sessionId = UUID.fromString(sessionId),
            photoId = UUID.fromString(photoId),
            studioId = principal.studioId
        )

        ResponseEntity.noContent().build()
    }
}

// Request DTOs

data class CreateSessionRequest(
    val appointmentId: String
)

data class GenerateUploadUrlRequest(
    val fileName: String,
    val photoType: String,  // PhotoType enum name
    val contentType: String,
    val fileSize: Long,
    val sessionToken: String
)

// Response DTOs

data class CreateSessionResponse(
    val sessionId: String,
    val token: String,
    val expiresAt: Instant
)

data class GenerateUploadUrlResponse(
    val photoId: String,
    val uploadUrl: String,
    val expiresAt: Instant
)

data class GetSessionPhotosResponse(
    val photos: List<PhotoResponse>
)

data class PhotoResponse(
    val id: String,
    val photoType: String,
    val fileName: String,
    val fileSize: Long,
    val uploadedAt: Instant,
    val thumbnailUrl: String
)

// Extension function

private fun SessionPhotoInfo.toResponse() = PhotoResponse(
    id = this.id.toString(),
    photoType = this.photoType.name,
    fileName = this.fileName,
    fileSize = this.fileSize,
    uploadedAt = this.uploadedAt,
    thumbnailUrl = this.thumbnailUrl
)
