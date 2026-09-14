package pl.detailing.crm.leads.attachment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Załącznik wiadomości, z której powstał lead — patrz V119__lead_attachments.sql.
 *
 * Wiersz jest WSKAZANIEM na [attachmentId] w `comm_attachments`, nie kopią pliku.
 * Metadane są zdenormalizowane, żeby lista załączników leada nie ciągnęła bajtów.
 */
@Entity
@Table(
    name = "lead_attachments",
    indexes = [
        Index(name = "idx_lead_attachments_unique", columnList = "lead_id, attachment_id", unique = true),
        Index(name = "idx_lead_attachments_lead", columnList = "lead_id, received_at")
    ]
)
class LeadAttachmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    /** Wiadomość źródłowa — po niej oś czasu wiesza plik pod właściwym wpisem. */
    @Column(name = "message_id", nullable = false, columnDefinition = "uuid")
    val messageId: UUID,

    @Column(name = "attachment_id", nullable = false, columnDefinition = "uuid")
    val attachmentId: UUID,

    @Column(name = "file_name", nullable = false, length = 500)
    val fileName: String,

    @Column(name = "content_type", nullable = false, length = 255)
    val contentType: String,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /** Czas nadejścia wiadomości, nie czas podpięcia. */
    @Column(name = "received_at", nullable = false, columnDefinition = "timestamp with time zone")
    val receivedAt: Instant,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)

@Repository
interface LeadAttachmentRepository : JpaRepository<LeadAttachmentEntity, UUID> {
    fun findByLeadIdOrderByReceivedAtAsc(leadId: UUID): List<LeadAttachmentEntity>
    fun existsByLeadIdAndAttachmentId(leadId: UUID, attachmentId: UUID): Boolean
}
