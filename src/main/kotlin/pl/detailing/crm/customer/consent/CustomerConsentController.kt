package pl.detailing.crm.customer.consent

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.customer.consent.getstatus.GetConsentStatusCommand
import pl.detailing.crm.customer.consent.getstatus.GetConsentStatusHandler
import pl.detailing.crm.customer.consent.getstatus.GetConsentStatusResult
import pl.detailing.crm.customer.consent.sign.SignConsentCommand
import pl.detailing.crm.customer.consent.sign.SignConsentHandler
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerId
import java.time.Instant
import java.util.*

/**
 * REST API controller for customer consent operations.
 *
 * Endpoints:
 * - GET /api/v1/customers/{customerId}/consents/status - Get consent status for a customer
 * - POST /api/v1/customers/{customerId}/consents/{templateId}/sign - Record a consent signature
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/consents")
class CustomerConsentController(
    private val getConsentStatusHandler: GetConsentStatusHandler,
    private val signConsentHandler: SignConsentHandler
) {

    /**
     * Get consent status for a specific customer.
     * Returns all active consent definitions and their current status for the customer.
     */
    @GetMapping("/status")
    fun getConsentStatus(
        @PathVariable customerId: UUID
    ): ResponseEntity<ConsentStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetConsentStatusCommand(
            studioId = principal.studioId,
            customerId = CustomerId(customerId)
        )

        val result = getConsentStatusHandler.handle(command)

        ResponseEntity.ok(ConsentStatusResponse.fromResult(result))
    }

    /**
     * Record a customer's acceptance of a specific consent template.
     * Creates an immutable audit record of the signature.
     *
     * Optionally accepts a request body. If requestAttachmentUpload is true,
     * the response will include a presigned S3 URL so the admin can upload
     * a scanned copy of the signed paper document.
     */
    @PostMapping("/{templateId}/sign")
    fun signConsent(
        @PathVariable customerId: UUID,
        @PathVariable templateId: UUID,
        @RequestBody(required = false) request: SignConsentRequest?
    ): ResponseEntity<SignConsentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = SignConsentCommand(
            studioId = principal.studioId,
            customerId = CustomerId(customerId),
            templateId = ConsentTemplateId(templateId),
            witnessedBy = principal.userId,
            requestAttachmentUpload = request?.requestAttachmentUpload ?: false
        )

        val result = signConsentHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            SignConsentResponse(
                consentId = result.consentId.value,
                signedAt = result.signedAt,
                attachmentUploadUrl = result.attachmentUploadUrl,
                attachmentS3Key = result.attachmentS3Key
            )
        )
    }
}

// Request / Response DTOs

data class SignConsentRequest(
    val requestAttachmentUpload: Boolean = false
)

data class ConsentStatusResponse(
    val consents: List<ConsentStatusItemResponse>
) {
    companion object {
        fun fromResult(result: GetConsentStatusResult): ConsentStatusResponse {
            return ConsentStatusResponse(
                consents = result.consents.map { item ->
                    ConsentStatusItemResponse(
                        definitionId = item.definitionId.value,
                        definitionSlug = item.definitionSlug,
                        definitionName = item.definitionName,
                        status = item.status.name,
                        currentTemplateId = item.currentTemplateId.value,
                        currentVersion = item.currentVersion,
                        signedTemplateId = item.signedTemplateId?.value,
                        signedVersion = item.signedVersion,
                        signedAt = item.signedAt,
                        downloadUrl = item.downloadUrl
                    )
                }
            )
        }
    }
}

data class ConsentStatusItemResponse(
    val definitionId: UUID,
    val definitionSlug: String,
    val definitionName: String,
    val status: String,
    val currentTemplateId: UUID,
    val currentVersion: Int,
    val signedTemplateId: UUID?,
    val signedVersion: Int?,
    val signedAt: Instant?,
    val downloadUrl: String?
)

data class SignConsentResponse(
    val consentId: UUID,
    val signedAt: Instant,
    val attachmentUploadUrl: String? = null,
    val attachmentS3Key: String? = null
)
