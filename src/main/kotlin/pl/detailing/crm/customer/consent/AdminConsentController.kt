package pl.detailing.crm.customer.consent

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.customer.consent.definition.CreateConsentDefinitionCommand
import pl.detailing.crm.customer.consent.definition.CreateConsentDefinitionHandler
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
 * - POST /api/v1/admin/consents/definitions - Create a new consent definition
 * - POST /api/v1/admin/consents/templates - Upload a new consent template version
 *
 * Security: Only OWNER and MANAGER roles can access these endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/consents")
class AdminConsentController(
    private val createConsentDefinitionHandler: CreateConsentDefinitionHandler,
    private val uploadTemplateHandler: UploadTemplateHandler
) {

    /**
     * Create a new consent definition.
     *
     * This is the first step in adding a new type of consent to the system.
     * After creating a definition, you can upload PDF templates for it.
     *
     * Example: To add "Zgoda na kontakt po godzinie 18":
     * 1. Create definition with slug="kontakt-po-18", name="Zgoda na kontakt po godzinie 18"
     * 2. Upload PDF template using /templates endpoint
     */
    @PostMapping("/definitions")
    fun createDefinition(
        @RequestBody request: CreateConsentDefinitionRequest
    ): ResponseEntity<CreateConsentDefinitionResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Authorization: Only OWNER and MANAGER can create definitions
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create consent definitions")
        }

        val command = CreateConsentDefinitionCommand(
            studioId = principal.studioId,
            slug = request.slug,
            name = request.name,
            description = request.description,
            createdBy = principal.userId
        )

        val result = createConsentDefinitionHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            CreateConsentDefinitionResponse(
                definitionId = result.definitionId.value,
                slug = result.slug,
                name = result.name
            )
        )
    }

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

data class CreateConsentDefinitionRequest(
    val slug: String,           // e.g., "kontakt-po-18"
    val name: String,           // e.g., "Zgoda na kontakt po godzinie 18"
    val description: String?    // Optional description
)

data class CreateConsentDefinitionResponse(
    val definitionId: UUID,
    val slug: String,
    val name: String
)

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
