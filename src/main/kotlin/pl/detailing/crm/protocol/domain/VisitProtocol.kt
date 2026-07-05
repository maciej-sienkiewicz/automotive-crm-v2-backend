package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * An instance of a signable document generated for a specific visit stage.
 *
 * Two kinds:
 *  - Visit document: templateId is set, PDF is auto-filled with CRM data (PENDING → READY → SIGNED).
 *  - Consent document: consentTemplateId is set, PDF is served as-is (starts READY_FOR_SIGNATURE).
 *
 * Exactly one of templateId / consentTemplateId must be non-null.
 * Once SIGNED the protocol is immutable.
 */
data class VisitProtocol(
    val id: VisitProtocolId,
    val studioId: StudioId,
    val visitId: VisitId,
    val templateId: ProtocolTemplateId?,           // null for consent protocols
    val consentTemplateId: ConsentTemplateId?,      // null for visit-document protocols
    val stage: ProtocolStage,
    val version: Int,
    val status: VisitProtocolStatus,
    val consentDefinitionId: ConsentDefinitionId?,  // set when this protocol captures a customer consent
    val filledPdfS3Key: String?,
    val signedPdfS3Key: String?,
    val signedAt: Instant?,
    val signedBy: String?,
    val signatureImageS3Key: String?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        require(templateId != null || consentTemplateId != null) {
            "Either templateId or consentTemplateId must be set"
        }
        require(templateId == null || consentTemplateId == null) {
            "Only one of templateId or consentTemplateId may be set"
        }
        if (status == VisitProtocolStatus.SIGNED) {
            requireNotNull(signedPdfS3Key) { "signedPdfS3Key required for SIGNED status" }
            requireNotNull(signedAt) { "signedAt required for SIGNED status" }
            requireNotNull(signedBy) { "signedBy required for SIGNED status" }
        }
    }

    fun markAsReadyForSignature(filledPdfS3Key: String): VisitProtocol {
        require(status == VisitProtocolStatus.PENDING) {
            "Can only mark PENDING protocols as READY_FOR_SIGNATURE"
        }
        return copy(
            status = VisitProtocolStatus.READY_FOR_SIGNATURE,
            filledPdfS3Key = filledPdfS3Key,
            updatedAt = Instant.now()
        )
    }

    /**
     * [signatureImageS3Key] is null for the eIDAS tablet flow: the signature bitmap is
     * processed exclusively in RAM and destroyed after being merged into the sealed PDF —
     * no standalone signature image is ever persisted.
     */
    fun sign(
        signedPdfS3Key: String,
        signedBy: String,
        signatureImageS3Key: String?,
        notes: String?
    ): VisitProtocol {
        require(status == VisitProtocolStatus.READY_FOR_SIGNATURE) {
            "Can only sign protocols that are READY_FOR_SIGNATURE"
        }
        return copy(
            status = VisitProtocolStatus.SIGNED,
            signedPdfS3Key = signedPdfS3Key,
            signedAt = Instant.now(),
            signedBy = signedBy,
            signatureImageS3Key = signatureImageS3Key,
            notes = notes,
            updatedAt = Instant.now()
        )
    }

    fun isImmutable(): Boolean = status == VisitProtocolStatus.SIGNED
}
