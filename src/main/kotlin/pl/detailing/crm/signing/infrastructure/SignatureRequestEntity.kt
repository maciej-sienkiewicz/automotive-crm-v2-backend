package pl.detailing.crm.signing.infrastructure

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "signature_requests",
    indexes = [
        Index(name = "idx_signature_requests_studio", columnList = "studio_id, status"),
        Index(name = "idx_signature_requests_protocol", columnList = "protocol_id"),
        Index(name = "idx_signature_requests_visit", columnList = "studio_id, visit_id"),
        Index(name = "idx_signature_requests_tablet", columnList = "studio_id, tablet_id, status"),
        Index(name = "idx_signature_requests_link_token", columnList = "link_token")
    ]
)
class SignatureRequestEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "protocol_id", nullable = false, columnDefinition = "uuid")
    val protocolId: UUID,

    @Column(name = "tablet_id", length = 100)
    val tabletId: String?,

    // Nullable for rows created before the SMS-link channel existed; null = TABLET
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    val channel: SignatureChannel? = null,

    @Column(name = "signer_phone", length = 30)
    val signerPhone: String? = null,

    @Column(name = "link_token", length = 100, unique = true)
    val linkToken: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: SignatureRequestStatus,

    @Column(name = "document_s3_key", nullable = false, length = 500)
    val documentS3Key: String,

    @Column(name = "document_sha256", nullable = false, length = 64)
    val documentSha256: String,

    @Column(name = "document_name", nullable = false, length = 300)
    val documentName: String,

    @Column(name = "signer_name", nullable = false, length = 200)
    val signerName: String,

    @Column(name = "declaration_text", nullable = false, columnDefinition = "TEXT")
    val declarationText: String,

    @Column(name = "requested_by", nullable = false, columnDefinition = "uuid")
    val requestedBy: UUID,

    @Column(name = "requested_by_name", nullable = false, length = 200)
    val requestedByName: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "displayed_at")
    var displayedAt: Instant? = null,

    @Column(name = "declaration_accepted_at")
    var declarationAcceptedAt: Instant? = null,

    @Column(name = "signed_at")
    var signedAt: Instant? = null,

    @Column(name = "sealed_at")
    var sealedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "signer_ip_address", length = 100)
    var signerIpAddress: String? = null,

    @Column(name = "signer_device", length = 300)
    var signerDevice: String? = null,

    @Column(name = "signed_pdf_s3_key", length = 500)
    var signedPdfS3Key: String? = null,

    @Column(name = "seal_applied", nullable = false)
    var sealApplied: Boolean = false,

    @Column(name = "timestamp_applied", nullable = false)
    var timestampApplied: Boolean = false,

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    var failureReason: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    fun toDomain(): SignatureRequest = SignatureRequest(
        id = SignatureRequestId(id),
        studioId = StudioId(studioId),
        visitId = VisitId(visitId),
        protocolId = VisitProtocolId(protocolId),
        tabletId = tabletId,
        channel = channel ?: SignatureChannel.TABLET,
        signerPhone = signerPhone,
        linkToken = linkToken,
        status = status,
        documentS3Key = documentS3Key,
        documentSha256 = documentSha256,
        documentName = documentName,
        signerName = signerName,
        declarationText = declarationText,
        requestedBy = UserId(requestedBy),
        requestedByName = requestedByName,
        createdAt = createdAt,
        expiresAt = expiresAt,
        displayedAt = displayedAt,
        declarationAcceptedAt = declarationAcceptedAt,
        signedAt = signedAt,
        sealedAt = sealedAt,
        completedAt = completedAt,
        signerIpAddress = signerIpAddress,
        signerDevice = signerDevice,
        signedPdfS3Key = signedPdfS3Key,
        sealApplied = sealApplied,
        timestampApplied = timestampApplied,
        failureReason = failureReason,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(r: SignatureRequest): SignatureRequestEntity = SignatureRequestEntity(
            id = r.id.value,
            studioId = r.studioId.value,
            visitId = r.visitId.value,
            protocolId = r.protocolId.value,
            tabletId = r.tabletId,
            channel = r.channel,
            signerPhone = r.signerPhone,
            linkToken = r.linkToken,
            status = r.status,
            documentS3Key = r.documentS3Key,
            documentSha256 = r.documentSha256,
            documentName = r.documentName,
            signerName = r.signerName,
            declarationText = r.declarationText,
            requestedBy = r.requestedBy.value,
            requestedByName = r.requestedByName,
            createdAt = r.createdAt,
            expiresAt = r.expiresAt,
            displayedAt = r.displayedAt,
            declarationAcceptedAt = r.declarationAcceptedAt,
            signedAt = r.signedAt,
            sealedAt = r.sealedAt,
            completedAt = r.completedAt,
            signerIpAddress = r.signerIpAddress,
            signerDevice = r.signerDevice,
            signedPdfS3Key = r.signedPdfS3Key,
            sealApplied = r.sealApplied,
            timestampApplied = r.timestampApplied,
            failureReason = r.failureReason,
            updatedAt = r.updatedAt
        )
    }
}

@Repository
interface SignatureRequestRepository : JpaRepository<SignatureRequestEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): SignatureRequestEntity?

    /** Resolve an SMS signing link — the token itself is the credential. */
    fun findByLinkToken(linkToken: String): SignatureRequestEntity?

    /**
     * Pessimistic write lock on the request row, used to serialize concurrent
     * audit-trail appends for the same signing session (see SignatureAuditTrailService).
     */
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SignatureRequestEntity r WHERE r.id = :id")
    fun lockForAuditAppend(@Param("id") id: UUID): SignatureRequestEntity?

    /**
     * Newest active (not yet signed/cancelled) request routed to the given tablet
     * or to any tablet in the studio (tabletId IS NULL).
     */
    @Query(
        """
        SELECT r FROM SignatureRequestEntity r
        WHERE r.studioId = :studioId
          AND r.status IN ('PENDING_DISPLAY', 'DISPLAYED')
          AND r.expiresAt > :now
          AND (r.tabletId IS NULL OR r.tabletId = :tabletId)
          AND (r.channel IS NULL OR r.channel = pl.detailing.crm.signing.domain.SignatureChannel.TABLET)
        ORDER BY r.createdAt DESC
        """
    )
    fun findActiveForTablet(
        @Param("studioId") studioId: UUID,
        @Param("tabletId") tabletId: String,
        @Param("now") now: Instant
    ): List<SignatureRequestEntity>

    @Query(
        """
        SELECT r FROM SignatureRequestEntity r
        WHERE r.studioId = :studioId
          AND r.protocolId = :protocolId
          AND r.status IN ('PENDING_DISPLAY', 'DISPLAYED')
          AND r.expiresAt > :now
        """
    )
    fun findActiveForProtocol(
        @Param("studioId") studioId: UUID,
        @Param("protocolId") protocolId: UUID,
        @Param("now") now: Instant
    ): List<SignatureRequestEntity>
}
