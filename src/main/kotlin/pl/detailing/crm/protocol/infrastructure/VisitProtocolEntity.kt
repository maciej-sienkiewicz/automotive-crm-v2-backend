package pl.detailing.crm.protocol.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "visit_protocols",
    indexes = [
        Index(name = "idx_visit_protocols_visit", columnList = "studio_id, visit_id"),
        Index(name = "idx_visit_protocols_template", columnList = "template_id"),
        Index(name = "idx_visit_protocols_consent_template", columnList = "consent_template_id"),
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

    /** Null for consent protocols — use consentTemplateId instead. */
    @Column(name = "template_id", nullable = true, columnDefinition = "uuid")
    val templateId: UUID?,

    /** Null for visit-document protocols — use templateId instead. */
    @Column(name = "consent_template_id", nullable = true, columnDefinition = "uuid")
    val consentTemplateId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 50)
    val stage: ProtocolStage,

    @Column(name = "version", nullable = false)
    val version: Int = 1,

    @Column(name = "consent_definition_id", nullable = true, columnDefinition = "uuid")
    val consentDefinitionId: UUID? = null,

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
        templateId = templateId?.let { ProtocolTemplateId(it) },
        consentTemplateId = consentTemplateId?.let { ConsentTemplateId(it) },
        stage = stage,
        version = version,
        isMandatory = isMandatory,
        status = status,
        consentDefinitionId = consentDefinitionId?.let { ConsentDefinitionId(it) },
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
        fun fromDomain(p: VisitProtocol): VisitProtocolEntity =
            VisitProtocolEntity(
                id = p.id.value,
                studioId = p.studioId.value,
                visitId = p.visitId.value,
                templateId = p.templateId?.value,
                consentTemplateId = p.consentTemplateId?.value,
                stage = p.stage,
                version = p.version,
                isMandatory = p.isMandatory,
                status = p.status,
                consentDefinitionId = p.consentDefinitionId?.value,
                filledPdfS3Key = p.filledPdfS3Key,
                signedPdfS3Key = p.signedPdfS3Key,
                signedAt = p.signedAt,
                signedBy = p.signedBy,
                signatureImageS3Key = p.signatureImageS3Key,
                notes = p.notes,
                createdAt = p.createdAt,
                updatedAt = p.updatedAt
            )
    }
}
