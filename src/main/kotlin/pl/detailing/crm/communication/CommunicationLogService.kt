package pl.detailing.crm.communication

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

/**
 * Input command for recording a single outbound communication attempt.
 *
 * [visitId] is optional – automation SMS sent before a visit exists (PRE_VISIT trigger)
 * will pass null here.  All other fields are mandatory.
 */
data class RecordCommunicationCommand(
    val studioId: StudioId,
    val customerId: CustomerId,
    val visitId: VisitId?,
    /**
     * The appointment this message was sent for.
     * Set for booking-confirmation and pre-visit/post-visit automation SMS.
     * Null for messages not originating from a specific appointment.
     */
    val appointmentId: AppointmentId? = null,
    val channel: CommunicationChannel,
    val messageType: CommunicationMessageType,
    val recipientAddress: String,
    val subject: String?,
    val bodyContent: String,
    val success: Boolean,
    val errorMessage: String?,
    /** When set, overrides the [success]-based status mapping. */
    val status: CommunicationStatus? = null,
    /**
     * The employee who initiated the send. When set, overrides [AuditActorResolver]
     * so the feed shows the person's name instead of "System". Must be captured on the
     * request thread before any coroutine dispatcher switch, because the security context
     * is thread-local and may not survive the switch to Dispatchers.IO.
     */
    val initiatedBy: AuditActor? = null
)

/**
 * Central service responsible for persisting communication audit entries.
 *
 * Every outbound email or SMS handler must call [record] after dispatching a message,
 * regardless of delivery outcome.  Failures are recorded with [CommunicationStatus.FAILED]
 * so operators can audit what was attempted and why it did not reach the customer.
 *
 * This service is deliberately fire-and-forget — callers must not allow a logging failure
 * to disrupt the business workflow.  Wrap calls in a try/catch where the caller cannot
 * tolerate an exception propagating.
 */
@Service
class CommunicationLogService(
    private val repository: CommunicationLogJpaRepository,
    private val auditService: AuditService,
    private val auditActorResolver: AuditActorResolver
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun record(command: RecordCommunicationCommand) {
        val id = UUID.randomUUID()
        try {
            repository.save(
                CommunicationLogEntity(
                    id = id,
                    studioId = command.studioId.value,
                    customerId = command.customerId.value,
                    visitId = command.visitId?.value,
                    appointmentId = command.appointmentId?.value,
                    channel = command.channel,
                    messageType = command.messageType,
                    recipientAddress = command.recipientAddress,
                    subject = command.subject,
                    bodyContent = command.bodyContent,
                    status = command.status ?: if (command.success) CommunicationStatus.SENT else CommunicationStatus.FAILED,
                    errorMessage = command.errorMessage,
                    sentAt = Instant.now()
                )
            )
        } catch (ex: Exception) {
            logger.error(
                "Failed to persist communication log entry [channel={} type={} customerId={} visitId={}]: {}",
                command.channel, command.messageType, command.customerId, command.visitId, ex.message, ex
            )
        }

        recordAudit(id, command)
    }

    /**
     * Every outbound message in the system passes through [record], so hooking the activity
     * feed in here covers all of them at once — manual sends, automations and campaign
     * dispatch alike — instead of relying on each of the dozens of send paths remembering
     * to log. The communication log itself stays the detailed, per-message record; the
     * audit entry is the one line the owner sees in the company feed.
     */
    private fun recordAudit(id: UUID, command: RecordCommunicationCommand) {
        val succeeded = command.status?.let { it != CommunicationStatus.FAILED } ?: command.success

        auditService.recordSync(
            AuditEvent(
                studioId = command.studioId,
                // Prefer the actor captured before any dispatcher switch; fall back to
                // auditActorResolver for automations/campaign dispatch (no principal).
                actor = command.initiatedBy ?: auditActorResolver.current(),
                module = AuditModule.COMMUNICATION,
                action = when {
                    command.channel == CommunicationChannel.SMS && succeeded -> AuditAction.SMS_SENT
                    command.channel == CommunicationChannel.SMS -> AuditAction.SMS_FAILED
                    succeeded -> AuditAction.EMAIL_SENT
                    else -> AuditAction.EMAIL_FAILED
                },
                entityId = id.toString(),
                entityDisplayName = command.messageType.label,
                changes = listOfNotNull(
                    FieldChange("recipient", null, command.recipientAddress),
                    command.subject?.let { FieldChange("subject", null, it) }
                ),
                metadata = buildMap {
                    put("messageType", command.messageType.name)
                    put("channel", command.channel.name)
                    command.errorMessage?.let { put("error", it) }
                },
                context = AuditContext(
                    customerId = command.customerId,
                    visitId = command.visitId,
                    appointmentId = command.appointmentId
                )
            )
        )
    }
}
