package pl.detailing.crm.customer.consent

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.customer.consent.getstatus.GetConsentStatusCommand
import pl.detailing.crm.customer.consent.getstatus.GetConsentStatusHandler
import pl.detailing.crm.customer.consent.getstatus.GetConsentStatusResult
import pl.detailing.crm.customer.consent.revoke.RevokeConsentCommand
import pl.detailing.crm.customer.consent.revoke.RevokeConsentHandler
import pl.detailing.crm.customer.consent.sign.SignConsentCommand
import pl.detailing.crm.customer.consent.sign.SignConsentHandler
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.CustomerConsentId
import pl.detailing.crm.shared.ProtocolStage
import java.time.Instant
import java.util.*

/**
 * Customer-facing consent endpoints.
 *
 * Base URL: /api/v1/customers/{customerId}/consents
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/consents")
class CustomerConsentController(
    private val getConsentStatusHandler: GetConsentStatusHandler,
    private val signConsentHandler: SignConsentHandler,
    private val revokeConsentHandler: RevokeConsentHandler
) {

    /**
     * Get all consent statuses for a customer.
     *
     * Returns every active consent definition with its current status:
     * - VALID: customer signed the active version
     * - OUTDATED: customer signed an older version, re-sign not required
     * - REQUIRED: never signed, or active version requires re-sign
     *
     * Inactive definitions are included only if the customer has previously signed them.
     */
    @GetMapping
    fun getConsentStatus(
        @PathVariable customerId: UUID
    ): ResponseEntity<ConsentStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getConsentStatusHandler.handle(
            GetConsentStatusCommand(studioId = principal.studioId, customerId = CustomerId(customerId))
        )
        ResponseEntity.ok(ConsentStatusResponse.fromResult(result))
    }

    /**
     * Record a customer signature on a specific consent template version.
     * Creates an immutable audit record. Optionally returns a presigned S3 URL
     * to upload a scanned paper copy.
     */
    @PostMapping("/{templateId}/sign")
    fun signConsent(
        @PathVariable customerId: UUID,
        @PathVariable templateId: UUID,
        @RequestBody(required = false) request: SignConsentRequest?
    ): ResponseEntity<SignConsentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = signConsentHandler.handle(
            SignConsentCommand(
                studioId = principal.studioId,
                customerId = CustomerId(customerId),
                templateId = ConsentTemplateId(templateId),
                witnessedBy = principal.userId,
                requestAttachmentUpload = request?.requestAttachmentUpload ?: false
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(
            SignConsentResponse(
                consentId = result.consentId.value,
                signedAt = result.signedAt,
                attachmentUploadUrl = result.attachmentUploadUrl,
                attachmentS3Key = result.attachmentS3Key
            )
        )
    }

    /**
     * Revoke a previously recorded consent.
     * The record is preserved for audit purposes; the customer's status returns to REQUIRED.
     */
    @DeleteMapping("/{consentId}")
    fun revokeConsent(
        @PathVariable customerId: UUID,
        @PathVariable consentId: UUID
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        revokeConsentHandler.handle(
            RevokeConsentCommand(studioId = principal.studioId, consentId = CustomerConsentId(consentId))
        )
        ResponseEntity.noContent().build()
    }
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class SignConsentRequest(
    val requestAttachmentUpload: Boolean = false
)

data class ConsentStatusResponse(
    val consents: List<ConsentStatusItemResponse>
) {
    companion object {
        fun fromResult(result: GetConsentStatusResult): ConsentStatusResponse =
            ConsentStatusResponse(
                consents = result.consents.map { item ->
                    ConsentStatusItemResponse(
                        definitionId = item.definitionId.value,
                        definitionSlug = item.definitionSlug,
                        definitionName = item.definitionName,
                        isDefinitionActive = item.isDefinitionActive,
                        stage = item.stage,
                        isMandatory = item.isMandatory,
                        displayOrder = item.displayOrder,
                        status = item.status.name,
                        currentTemplateId = item.currentTemplateId?.value,
                        currentVersion = item.currentVersion,
                        signedTemplateId = item.signedTemplateId?.value,
                        signedVersion = item.signedVersion,
                        signedAt = item.signedAt,
                        downloadUrl = item.downloadUrl,
                        consentId = item.consentId?.value
                    )
                }
            )
    }
}

data class ConsentStatusItemResponse(
    val definitionId: UUID,
    val definitionSlug: String,
    val definitionName: String,
    val isDefinitionActive: Boolean,
    val stage: ProtocolStage?,         // null when definition is inactive
    val isMandatory: Boolean,
    val displayOrder: Int,
    val status: String,                // VALID | OUTDATED | REQUIRED
    val currentTemplateId: UUID?,
    val currentVersion: Int?,
    val signedTemplateId: UUID?,
    val signedVersion: Int?,
    val signedAt: Instant?,
    val downloadUrl: String?,
    val consentId: UUID?
)

data class SignConsentResponse(
    val consentId: UUID,
    val signedAt: Instant,
    val attachmentUploadUrl: String? = null,
    val attachmentS3Key: String? = null
)
