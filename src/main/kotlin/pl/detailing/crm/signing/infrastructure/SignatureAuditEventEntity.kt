package pl.detailing.crm.signing.infrastructure

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.*

/**
 * A single link of the hash-chained audit trail of a signing session (Karta Audytowa).
 *
 * Each event stores [eventHash] = SHA-256 over the canonical serialization of its own
 * fields concatenated with [previousEventHash]. Any retroactive modification, deletion
 * or reordering of events breaks the chain and is detectable by a court-appointed expert.
 */
@Entity
@Table(
    name = "signature_audit_events",
    indexes = [
        Index(name = "idx_signature_audit_request", columnList = "request_id, sequence_number")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uq_signature_audit_request_seq", columnNames = ["request_id", "sequence_number"])
    ]
)
class SignatureAuditEventEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "request_id", nullable = false, columnDefinition = "uuid")
    val requestId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "sequence_number", nullable = false)
    val sequenceNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    val eventType: SignatureAuditEventType,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    /** Who triggered the event: employee login/name or "TABLET"/"SYSTEM". */
    @Column(name = "actor", nullable = false, length = 200)
    val actor: String,

    @Column(name = "ip_address", length = 100)
    val ipAddress: String?,

    @Column(name = "user_agent", length = 300)
    val userAgent: String?,

    @Column(name = "details", columnDefinition = "TEXT")
    val details: String?,

    @Column(name = "previous_event_hash", length = 64)
    val previousEventHash: String?,

    @Column(name = "event_hash", nullable = false, length = 64)
    val eventHash: String
)

@Repository
interface SignatureAuditEventRepository : JpaRepository<SignatureAuditEventEntity, UUID> {
    fun findAllByRequestIdOrderBySequenceNumberAsc(requestId: UUID): List<SignatureAuditEventEntity>
    fun findTopByRequestIdOrderBySequenceNumberDesc(requestId: UUID): SignatureAuditEventEntity?
}

/**
 * Appends hash-chained audit events for signature requests.
 *
 * The chain (together with the audit page embedded in the sealed PDF and the qualified
 * timestamp) forms the evidentiary record required to shift the burden of proof under
 * art. 253 KPC when a signer disputes the document.
 */
@Service
class SignatureAuditTrailService(
    private val repository: SignatureAuditEventRepository,
    private val signatureRequestRepository: SignatureRequestRepository
) {
    /**
     * Appends are serialized per signing session by taking a pessimistic write lock
     * on the parent signature_requests row before computing max(sequence_number)+1.
     * Without the lock, concurrent events — e.g. the employee cancelling the request
     * in the CRM while the tablet reports DOCUMENT_DISPLAYED or submits the signature —
     * both read the same latest sequence and the second insert dies on the
     * uq_signature_audit_request_seq unique constraint.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun append(
        requestId: UUID,
        studioId: UUID,
        eventType: SignatureAuditEventType,
        actor: String,
        ipAddress: String? = null,
        userAgent: String? = null,
        details: String? = null,
        occurredAt: Instant = Instant.now()
    ): SignatureAuditEventEntity {
        signatureRequestRepository.lockForAuditAppend(requestId)
        val previous = repository.findTopByRequestIdOrderBySequenceNumberDesc(requestId)
        val sequenceNumber = (previous?.sequenceNumber ?: 0) + 1
        val previousHash = previous?.eventHash

        val canonical = listOf(
            requestId.toString(),
            sequenceNumber.toString(),
            eventType.name,
            occurredAt.toString(),
            actor,
            ipAddress ?: "",
            userAgent ?: "",
            details ?: "",
            previousHash ?: ""
        ).joinToString("|")

        val eventHash = sha256Hex(canonical)

        val event = SignatureAuditEventEntity(
            id = UUID.randomUUID(),
            requestId = requestId,
            studioId = studioId,
            sequenceNumber = sequenceNumber,
            eventType = eventType,
            occurredAt = occurredAt,
            actor = actor,
            ipAddress = ipAddress,
            userAgent = userAgent,
            details = details,
            previousEventHash = previousHash,
            eventHash = eventHash
        )
        return repository.save(event)
    }

    fun eventsFor(requestId: UUID): List<SignatureAuditEventEntity> =
        repository.findAllByRequestIdOrderBySequenceNumberAsc(requestId)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
