package pl.detailing.crm.protocol.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.*

/**
 * JPA entity for VisitProtocol.
 * Represents an instance of a protocol for a specific visit.
 */
@Entity
@Table(
    name = "visit_protocols",
    indexes = [
        Index(name = "idx_visit_protocols_visit", columnList = "studio_id, visit_id"),
        Index(name = "idx_visit_protocols_template", columnList = "template_id"),
        Index(name = "idx_visit_protocols_status", columnList = "visit_id, status"),
        Index(name = "idx_visit_protocols_stage", columnList = "visit_id, stage")
    ]
)
class VisitProtocolEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "template_id", nullable = false, columnDefinition = "uuid")
    val templateId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 50)
    val stage: ProtocolStage,

    @Column(name = "is_mandatory", nullable = false)
    val isMandatory: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: VisitProtocolStatus,

    @Column(name = "filled_pdf_s3_key", length = 500)
    var filledPdfS3Key: String? = null,

    @Column(name = "signed_pdf_s3_key", length = 500)
    var signedPdfS3Key: String? = null,

    @Column(name = "signed_at")
    var signedAt: Instant? = null,

    @Column(name = "signed_by", length = 200)
    var signedBy: String? = null,

    @Column(name = "signature_image_s3_key", length = 500)
    var signatureImageS3Key: String? = null,

    @Column(name = "notes", columnDefinition = "TEXT")
    var notes: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): VisitProtocol = VisitProtocol(
        id = VisitProtocolId(id),
        studioId = StudioId(studioId),
        visitId = VisitId(visitId),
        templateId = ProtocolTemplateId(templateId),
        stage = stage,
        isMandatory = isMandatory,
        status = status,
        filledPdfS3Key = filledPdfS3Key,
        signedPdfS3Key = signedPdfS3Key,
        signedAt = signedAt,
        signedBy = signedBy,
        signatureImageS3Key = signatureImageS3Key,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(visitProtocol: VisitProtocol): VisitProtocolEntity =
            VisitProtocolEntity(
                id = visitProtocol.id.value,
                studioId = visitProtocol.studioId.value,
                visitId = visitProtocol.visitId.value,
                templateId = visitProtocol.templateId.value,
                stage = visitProtocol.stage,
                isMandatory = visitProtocol.isMandatory,
                status = visitProtocol.status,
                filledPdfS3Key = visitProtocol.filledPdfS3Key,
                signedPdfS3Key = visitProtocol.signedPdfS3Key,
                signedAt = visitProtocol.signedAt,
                signedBy = visitProtocol.signedBy,
                signatureImageS3Key = visitProtocol.signatureImageS3Key,
                notes = visitProtocol.notes,
                createdAt = visitProtocol.createdAt,
                updatedAt = visitProtocol.updatedAt
            )
    }
}
