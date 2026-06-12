package pl.detailing.crm.leads.services

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(
    name = "lead_service_tags",
    indexes = [
        Index(name = "idx_lead_service_tags_lead", columnList = "lead_id"),
        Index(name = "idx_lead_service_tags_studio_service", columnList = "studio_id, service_id")
    ]
)
class LeadServiceTagEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "service_id", nullable = true, columnDefinition = "uuid")
    val serviceId: UUID?,

    @Column(name = "service_name", nullable = false, length = 200)
    val serviceName: String
)
