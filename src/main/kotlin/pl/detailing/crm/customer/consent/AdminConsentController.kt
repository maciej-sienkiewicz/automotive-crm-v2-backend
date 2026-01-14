package pl.detailing.crm.customer.consent

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.customer.consent.template.UploadTemplateCommand
import pl.detailing.crm.customer.consent.template.UploadTemplateHandler
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import java.util.*

/**
 * REST API controller for admin consent operations.
 *
 * Endpoints:
 * - POST /api/v1/admin/consents/templates - Upload a new consent template version
 *
 * Security: Only OWNER and MANAGER roles can access these endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/consents")
class AdminConsentController(
    private val uploadTemplateHandler: UploadTemplateHandler
) {

    /**
     * Upload a new consent template version.
     *
     * This creates a new template record and returns a presigned URL for the frontend
     * to upload the PDF file directly to S3.
     *
     * The version number is automatically incremented.
     * If setAsActive is true (default), this becomes the active version and all other
     * templates for this definition are deactivated.
     */
    @PostMapping("/templates")
    fun uploadTemplate(
        @RequestBody request: UploadTemplateRequest
    ): ResponseEntity<UploadTemplateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Authorization: Only OWNER and MANAGER can upload templates
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can upload consent templates")
        }

        val command = UploadTemplateCommand(
            studioId = principal.studioId,
            definitionId = ConsentDefinitionId(request.definitionId),
            requiresResign = request.requiresResign,
            setAsActive = request.setAsActive ?: true,
            createdBy = principal.userId
        )

        val result = uploadTemplateHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            UploadTemplateResponse(
                templateId = result.templateId.value,
                version = result.version,
                uploadUrl = result.uploadUrl,
                s3Key = result.s3Key
            )
        )
    }
}

// Request/Response DTOs

data class UploadTemplateRequest(
    val definitionId: UUID,
    val requiresResign: Boolean,
    val setAsActive: Boolean? = true
)

data class UploadTemplateResponse(
    val templateId: UUID,
    val version: Int,
    val uploadUrl: String,
    val s3Key: String
)
