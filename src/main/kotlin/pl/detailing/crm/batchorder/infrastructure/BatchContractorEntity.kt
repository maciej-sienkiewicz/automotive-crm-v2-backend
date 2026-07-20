package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.batchorder.domain.BatchContractor
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "batch_contractors",
    indexes = [
        Index(name = "idx_batch_contractors_studio_id", columnList = "studio_id"),
        Index(name = "idx_batch_contractors_studio_active", columnList = "studio_id, is_active")
    ]
)
class BatchContractorEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    var name: String,

    @Column(name = "tax_id", length = 50)
    var taxId: String?,

    @Column(name = "address", columnDefinition = "TEXT")
    var address: String?,

    @Column(name = "contact_person_name", length = 255)
    var contactPersonName: String?,

    @Column(name = "email", length = 255)
    var email: String?,

    @Column(name = "phone", length = 50)
    var phone: String?,

    @Column(name = "notes", columnDefinition = "TEXT")
    var notes: String?,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): BatchContractor = BatchContractor(
        id = BatchContractorId(id),
        studioId = StudioId(studioId),
        name = name,
        taxId = taxId,
        address = address,
        contactPersonName = contactPersonName,
        email = email,
        phone = phone,
        notes = notes,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(contractor: BatchContractor): BatchContractorEntity =
            BatchContractorEntity(
                id = contractor.id.value,
                studioId = contractor.studioId.value,
                name = contractor.name,
                taxId = contractor.taxId,
                address = contractor.address,
                contactPersonName = contractor.contactPersonName,
                email = contractor.email,
                phone = contractor.phone,
                notes = contractor.notes,
                isActive = contractor.isActive,
                createdAt = contractor.createdAt,
                updatedAt = contractor.updatedAt
            )
    }
}
