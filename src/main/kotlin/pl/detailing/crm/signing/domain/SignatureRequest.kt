package pl.detailing.crm.signing.domain

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * A tablet signing session for a single visit protocol (eIDAS "simple electronic signature"
 * hardened against the copy-paste / replay objection).
 *
 * The request is the cryptographic anchor of the WYSIWYS principle
 * (What You See Is What You Sign):
 *  - [documentSha256] is computed by the backend over the EXACT bytes of the filled PDF
 *    at the moment the employee clicks "Poproś o podpis".
 *  - The tablet must echo the same hash (computed over the bytes it displayed) when
 *    submitting the signature. A mismatch aborts the signing session.
 *  - [challenge] is a single-use, cryptographically random nonce bound to this request.
 *    It is consumed atomically at submission, making replays of a captured submit
 *    request impossible.
 *
 * Lifecycle: PENDING_DISPLAY → DISPLAYED → COMPLETED
 *            (or → CANCELLED / DECLINED / EXPIRED / FAILED)
 * A COMPLETED request is immutable.
 */
data class SignatureRequest(
    val id: SignatureRequestId,
    val studioId: StudioId,
    val visitId: VisitId,
    val protocolId: VisitProtocolId,
    /** Tablet the request is routed to; null = any paired tablet in this studio. */
    val tabletId: String?,
    /** How the document is presented to the signer: studio tablet or a tokenized SMS link. */
    val channel: SignatureChannel = SignatureChannel.TABLET,
    /** Phone number the signing link was sent to (SMS_LINK channel only). */
    val signerPhone: String? = null,
    /** Unguessable token embedded in the SMS link (SMS_LINK channel only). */
    val linkToken: String? = null,
    val status: SignatureRequestStatus,
    /** S3 key of the exact PDF revision presented for signature. */
    val documentS3Key: String,
    /** SHA-256 (hex, lowercase) of the exact PDF bytes presented for signature. */
    val documentSha256: String,
    /** Human-readable document name shown on the tablet. */
    val documentName: String,
    /** Full name of the person expected to sign (customer / person handing off the vehicle). */
    val signerName: String,
    /** The exact declaration text the signer must accept before signing. */
    val declarationText: String,
    /** Employee (CRM user) who requested the signature. */
    val requestedBy: UserId,
    val requestedByName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val displayedAt: Instant?,
    val declarationAcceptedAt: Instant?,
    val signedAt: Instant?,
    val sealedAt: Instant?,
    val completedAt: Instant?,
    /** IP address of the device that submitted the signature. */
    val signerIpAddress: String?,
    /** User-Agent / device name of the tablet that submitted the signature. */
    val signerDevice: String?,
    val signedPdfS3Key: String?,
    /** True when a qualified electronic seal (PAdES) was applied to the final PDF. */
    val sealApplied: Boolean,
    /** True when a qualified RFC 3161 timestamp was embedded in the seal. */
    val timestampApplied: Boolean,
    val failureReason: String?,
    val updatedAt: Instant
) {
    fun isExpired(now: Instant = Instant.now()): Boolean =
        now.isAfter(expiresAt) && status in setOf(
            SignatureRequestStatus.PENDING_DISPLAY,
            SignatureRequestStatus.DISPLAYED
        )

    fun isTerminal(): Boolean = status in setOf(
        SignatureRequestStatus.COMPLETED,
        SignatureRequestStatus.CANCELLED,
        SignatureRequestStatus.DECLINED,
        SignatureRequestStatus.EXPIRED,
        SignatureRequestStatus.FAILED
    )

    fun markDisplayed(now: Instant = Instant.now()): SignatureRequest {
        require(status == SignatureRequestStatus.PENDING_DISPLAY || status == SignatureRequestStatus.DISPLAYED) {
            "Dokument może zostać wyświetlony tylko dla aktywnego żądania podpisu"
        }
        return copy(
            status = SignatureRequestStatus.DISPLAYED,
            displayedAt = displayedAt ?: now,
            updatedAt = now
        )
    }

    fun complete(
        signedPdfS3Key: String,
        declarationAcceptedAt: Instant,
        signedAt: Instant,
        sealedAt: Instant?,
        signerIpAddress: String?,
        signerDevice: String?,
        sealApplied: Boolean,
        timestampApplied: Boolean,
        now: Instant = Instant.now()
    ): SignatureRequest {
        require(status == SignatureRequestStatus.DISPLAYED) {
            "Podpis można przyjąć tylko dla dokumentu, który został wyświetlony na tablecie"
        }
        return copy(
            status = SignatureRequestStatus.COMPLETED,
            declarationAcceptedAt = declarationAcceptedAt,
            signedAt = signedAt,
            sealedAt = sealedAt,
            completedAt = now,
            signerIpAddress = signerIpAddress,
            signerDevice = signerDevice,
            signedPdfS3Key = signedPdfS3Key,
            sealApplied = sealApplied,
            timestampApplied = timestampApplied,
            updatedAt = now
        )
    }

    fun cancel(now: Instant = Instant.now()): SignatureRequest {
        require(!isTerminal()) { "Żądanie podpisu jest już zakończone" }
        return copy(status = SignatureRequestStatus.CANCELLED, updatedAt = now)
    }

    fun decline(reason: String?, now: Instant = Instant.now()): SignatureRequest {
        require(!isTerminal()) { "Żądanie podpisu jest już zakończone" }
        return copy(
            status = SignatureRequestStatus.DECLINED,
            failureReason = reason,
            updatedAt = now
        )
    }

    fun expire(now: Instant = Instant.now()): SignatureRequest =
        copy(status = SignatureRequestStatus.EXPIRED, updatedAt = now)

    fun fail(reason: String, now: Instant = Instant.now()): SignatureRequest =
        copy(status = SignatureRequestStatus.FAILED, failureReason = reason, updatedAt = now)
}

/** Where the signer sees and signs the document. */
enum class SignatureChannel {
    TABLET,     // Studio tablet paired via X-Tablet-Token
    SMS_LINK    // Customer's own phone, opened from a tokenized link sent by SMS
}

enum class SignatureRequestStatus {
    PENDING_DISPLAY,  // Created, waiting for the tablet to fetch & display the document
    DISPLAYED,        // Tablet fetched the exact PDF bytes (hash re-verified at delivery)
    COMPLETED,        // Signature accepted, PDF sealed and stored (immutable)
    DECLINED,         // Customer refused to sign on the tablet
    CANCELLED,        // Employee cancelled the request in the CRM
    EXPIRED,          // TTL elapsed without a signature
    FAILED            // Hash mismatch / replay attempt / processing error
}

/**
 * Typed audit-trail events forming the evidentiary chain of a signing session.
 * Persisted as a hash-chained sequence (see SignatureAuditEventEntity).
 */
enum class SignatureAuditEventType {
    REQUEST_CREATED,        // Employee clicked "Poproś o podpis"; document hash computed
    DOCUMENT_DELIVERED,     // Exact PDF bytes streamed to the tablet; hash re-verified
    DECLARATION_ACCEPTED,   // Signer ticked the "Oświadczam, że zapoznałem się..." checkbox
    SIGNATURE_SUBMITTED,    // Tablet submitted the signature packet
    HASH_VERIFIED,          // Server confirmed tablet hash == expected document hash
    DOCUMENT_SEALED,        // Qualified seal + timestamp applied
    REQUEST_COMPLETED,      // Final PDF stored, protocol marked SIGNED
    REQUEST_CANCELLED,
    REQUEST_DECLINED,
    REQUEST_FAILED
}
