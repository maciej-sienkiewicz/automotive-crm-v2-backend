package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Visit protocol represents an instance of a protocol for a specific visit.
 *
 * Lifecycle:
 * 1. PENDING: Protocol generated, waiting for data and PDF generation
 * 2. READY_FOR_SIGNATURE: PDF filled with CRM data, ready to be signed
 * 3. SIGNED: Signature applied and PDF flattened (immutable)
 *
 * Once SIGNED, the protocol cannot be modified.
 */
data class VisitProtocol(
    val id: VisitProtocolId,
    val studioId: StudioId,
    val visitId: VisitId,
    val templateId: ProtocolTemplateId,
    val stage: ProtocolStage,
    val version: Int,                   // Version number for protocol regeneration (1, 2, 3...)
    val isMandatory: Boolean,
    val status: VisitProtocolStatus,
    val filledPdfS3Key: String?,        // S3 key for the filled PDF (before signature)
    val signedPdfS3Key: String?,        // S3 key for the signed and flattened PDF
    val signedAt: Instant?,
    val signedBy: String?,              // Name of the person who signed
    val signatureImageS3Key: String?,   // S3 key for the signature image
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        if (status == VisitProtocolStatus.SIGNED) {
            requireNotNull(signedPdfS3Key) { "Signed PDF S3 key is required for SIGNED status" }
            requireNotNull(signedAt) { "Signed at timestamp is required for SIGNED status" }
            requireNotNull(signedBy) { "Signed by is required for SIGNED status" }
        }
    }

    fun markAsReadyForSignature(filledPdfS3Key: String): VisitProtocol {
        require(status == VisitProtocolStatus.PENDING) {
            "Can only mark PENDING protocols as ready for signature"
        }
        return copy(
            status = VisitProtocolStatus.READY_FOR_SIGNATURE,
            filledPdfS3Key = filledPdfS3Key,
            updatedAt = Instant.now()
        )
    }

    fun sign(
        signedPdfS3Key: String,
        signedBy: String,
        signatureImageS3Key: String,
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

    /**
     * Check if this protocol can be modified
     */
    fun isImmutable(): Boolean = status == VisitProtocolStatus.SIGNED
}
