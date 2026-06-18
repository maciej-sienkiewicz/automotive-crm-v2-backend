package pl.detailing.crm.leads.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "leads",
    indexes = [
        Index(name = "idx_leads_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_leads_studio_created", columnList = "studio_id, created_at"),
        Index(name = "idx_leads_studio_verification", columnList = "studio_id, requires_verification"),
        Index(name = "idx_leads_contact", columnList = "contact_identifier")
    ]
)
class LeadEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    val source: LeadSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: LeadStatus,

    @Column(name = "contact_identifier", nullable = false, columnDefinition = "text")
    val contactIdentifier: String,

    @Column(name = "customer_name", nullable = true, columnDefinition = "text")
    var customerName: String?,

    @Column(name = "initial_message", nullable = true, columnDefinition = "text")
    var initialMessage: String?,

    @Column(name = "estimated_value", nullable = false)
    var estimatedValue: Long,

    @Column(name = "requires_verification", nullable = false)
    var requiresVerification: Boolean,

    @Column(name = "vehicle_brand", nullable = true, columnDefinition = "text")
    var vehicleBrand: String?,

    @Column(name = "vehicle_model", nullable = true, columnDefinition = "text")
    var vehicleModel: String?,

    @Column(name = "customer_id", nullable = true, columnDefinition = "uuid")
    var customerId: UUID?,

    @Column(name = "appointment_id", nullable = true, columnDefinition = "uuid")
    var appointmentId: UUID?,

    @Column(name = "visit_id", nullable = true, columnDefinition = "uuid")
    var visitId: UUID?,

    @Column(name = "assigned_user_id", nullable = true, columnDefinition = "uuid")
    var assignedUserId: UUID?,

    @Column(name = "assigned_user_name", nullable = true, columnDefinition = "text")
    var assignedUserName: String?,

    @Column(name = "lost_reason", nullable = true, length = 500)
    var lostReason: String?,

    @Column(name = "stagnant_alert_sent_at", nullable = true, columnDefinition = "timestamp with time zone")
    var stagnantAlertSentAt: Instant?,

    @Column(name = "new_activity_at", nullable = true, columnDefinition = "timestamp with time zone")
    var newActivityAt: Instant?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Lead = Lead(
        id = LeadId(id),
        studioId = StudioId(studioId),
        source = source,
        status = status,
        contactIdentifier = contactIdentifier,
        customerName = customerName,
        initialMessage = initialMessage,
        estimatedValue = estimatedValue,
        requiresVerification = requiresVerification,
        vehicleBrand = vehicleBrand,
        vehicleModel = vehicleModel,
        customerId = customerId?.let { CustomerId(it) },
        appointmentId = appointmentId?.let { AppointmentId(it) },
        visitId = visitId?.let { VisitId(it) },
        assignedUserId = assignedUserId?.let { UserId(it) },
        assignedUserName = assignedUserName,
        lostReason = lostReason,
        stagnantAlertSentAt = stagnantAlertSentAt,
        newActivityAt = newActivityAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(lead: Lead): LeadEntity = LeadEntity(
            id = lead.id.value,
            studioId = lead.studioId.value,
            source = lead.source,
            status = lead.status,
            contactIdentifier = lead.contactIdentifier,
            customerName = lead.customerName,
            initialMessage = lead.initialMessage,
            estimatedValue = lead.estimatedValue,
            requiresVerification = lead.requiresVerification,
            vehicleBrand = lead.vehicleBrand,
            vehicleModel = lead.vehicleModel,
            customerId = lead.customerId?.value,
            appointmentId = lead.appointmentId?.value,
            visitId = lead.visitId?.value,
            assignedUserId = lead.assignedUserId?.value,
            assignedUserName = lead.assignedUserName,
            lostReason = lead.lostReason,
            stagnantAlertSentAt = lead.stagnantAlertSentAt,
            newActivityAt = lead.newActivityAt,
            createdAt = lead.createdAt,
            updatedAt = lead.updatedAt
        )
    }
}
