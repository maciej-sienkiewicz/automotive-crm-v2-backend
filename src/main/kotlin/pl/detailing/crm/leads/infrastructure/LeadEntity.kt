package pl.detailing.crm.leads.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.domain.LeadCategory
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "leads",
    indexes = [
        Index(name = "idx_leads_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_leads_studio_created", columnList = "studio_id, created_at"),
        Index(name = "idx_leads_contact", columnList = "studio_id, contact_identifier"),
        Index(name = "idx_leads_thread", columnList = "thread_id"),
        Index(name = "idx_leads_appointment", columnList = "appointment_id")
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

    /** E-mail address or phone number the inquiry came from. */
    @Column(name = "contact_identifier", nullable = false, columnDefinition = "text")
    val contactIdentifier: String,

    @Column(name = "customer_name", columnDefinition = "text")
    var customerName: String?,

    @Column(name = "initial_message", columnDefinition = "text")
    var initialMessage: String?,

    /** Suma pozycji usługowych w groszach (denormalizowana z lead_service_items). */
    @Column(name = "estimated_value", nullable = false)
    var estimatedValue: Long,

    @Column(name = "requires_verification", nullable = false)
    var requiresVerification: Boolean,

    @Column(name = "vehicle_brand", columnDefinition = "text")
    var vehicleBrand: String?,

    @Column(name = "vehicle_model", columnDefinition = "text")
    var vehicleModel: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_detection_status", nullable = false, length = 20)
    var vehicleDetectionStatus: LeadVehicleDetectionStatus = LeadVehicleDetectionStatus.DONE,

    @Column(name = "customer_id", columnDefinition = "uuid")
    var customerId: UUID?,

    @Column(name = "appointment_id", columnDefinition = "uuid")
    var appointmentId: UUID?,

    @Column(name = "visit_id", columnDefinition = "uuid")
    var visitId: UUID?,

    @Column(name = "assigned_user_id", columnDefinition = "uuid")
    var assignedUserId: UUID?,

    @Column(name = "assigned_user_name", columnDefinition = "text")
    var assignedUserName: String?,

    /** Free-text note attached to a lost lead; the aggregable reason lives in [lostReasonCode]. */
    @Column(name = "lost_reason", length = 500)
    var lostReason: String?,

    @Column(name = "stagnant_alert_sent_at", columnDefinition = "timestamp with time zone")
    var stagnantAlertSentAt: Instant?,

    /** Conversation behind an e-mail lead (comm_threads.id); null for phone/manual leads. */
    @Column(name = "thread_id", columnDefinition = "uuid")
    var threadId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    var category: LeadCategory? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason_code", length = 30)
    var lostReasonCode: LeadLostReason? = null,

    /** First OUTBOUND reply in the lead's thread — basis of the response-time analytics. */
    @Column(name = "first_response_at", columnDefinition = "timestamp with time zone")
    var firstResponseAt: Instant? = null,

    /** Set when the lead reaches a terminal status (COMPLETED / LOST / NO_SHOW). */
    @Column(name = "closed_at", columnDefinition = "timestamp with time zone")
    var closedAt: Instant? = null,

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
        vehicleDetectionStatus = vehicleDetectionStatus,
        customerId = customerId?.let { CustomerId(it) },
        appointmentId = appointmentId?.let { AppointmentId(it) },
        visitId = visitId?.let { VisitId(it) },
        assignedUserId = assignedUserId?.let { UserId(it) },
        assignedUserName = assignedUserName,
        lostReason = lostReason,
        stagnantAlertSentAt = stagnantAlertSentAt,
        threadId = threadId,
        category = category,
        lostReasonCode = lostReasonCode,
        firstResponseAt = firstResponseAt,
        closedAt = closedAt,
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
            vehicleDetectionStatus = lead.vehicleDetectionStatus,
            customerId = lead.customerId?.value,
            appointmentId = lead.appointmentId?.value,
            visitId = lead.visitId?.value,
            assignedUserId = lead.assignedUserId?.value,
            assignedUserName = lead.assignedUserName,
            lostReason = lead.lostReason,
            stagnantAlertSentAt = lead.stagnantAlertSentAt,
            threadId = lead.threadId,
            category = lead.category,
            lostReasonCode = lead.lostReasonCode,
            firstResponseAt = lead.firstResponseAt,
            closedAt = lead.closedAt,
            createdAt = lead.createdAt,
            updatedAt = lead.updatedAt
        )
    }
}
