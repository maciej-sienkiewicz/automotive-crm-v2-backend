package pl.detailing.crm.communication

import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
import pl.detailing.crm.customer.infrastructure.CustomerRepository
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
     * Set when the gateway queued the message for the send window instead of sending it
     * (`result.queuedMessageId`). The entry is recorded as [CommunicationStatus.QUEUED] and
     * later resolved to SENT / FAILED by the queue dispatcher through [CommunicationLogService.recordQueuedOutcome].
     */
    val queuedMessageId: UUID? = null,
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
    private val auditActorResolver: AuditActorResolver,
    private val customerRepository: CustomerRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Zapis w WŁASNEJ transakcji, z natychmiastowym flushem.
     *
     * Jedno i drugie jest konieczne, żeby `catch` niżej cokolwiek dawał. Bez nich
     * `repository.save()` tylko planuje INSERT, a ten wykonuje się przy commicie
     * transakcji wywołującego — czyli poza tym blokiem. Błąd nie trafiał więc do
     * `catch`, tylko wywracał operację biznesową, której ten wpis miał być wyłącznie
     * opisem. Tak właśnie SMS z linkiem do podpisu potrafił dojść do klienta, podczas
     * gdy żądanie podpisu znikało razem z rollbackiem (patrz V100__sync_enum_check_constraints.sql).
     *
     * Wiadomość wysłana jest faktem, którego nie da się cofnąć. Zapis o niej nie ma
     * prawa ani zniknąć razem z transakcją wywołującego, ani jej przewrócić — to ten
     * sam układ, który moduł audytu ma od dawna (patrz AuditLogWriter).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(command: RecordCommunicationCommand) {
        val id = UUID.randomUUID()
        try {
            repository.saveAndFlush(
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
                    status = resolveStatus(command),
                    errorMessage = command.errorMessage,
                    sentAt = Instant.now(),
                    queuedMessageId = command.queuedMessageId
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
     * Kolejność: jawny [RecordCommunicationCommand.status] wygrywa zawsze (nadawca wie lepiej),
     * potem „przyjęta do kolejki", na końcu zwykłe wysłano / nie wysłano.
     */
    private fun resolveStatus(command: RecordCommunicationCommand): CommunicationStatus = when {
        command.status != null -> command.status
        command.queuedMessageId != null && command.success -> CommunicationStatus.QUEUED
        command.success -> CommunicationStatus.SENT
        else -> CommunicationStatus.FAILED
    }

    /**
     * Domknięcie wpisu QUEUED przez dispatcher kolejki: wiadomość faktycznie wyszła (albo
     * ostatecznie nie wyszła). Zmienia status i chwilę wysyłki na istniejącym wpisie —
     * kartoteka klienta ma pokazywać jedną wiadomość, nie „w kolejce" plus „wysłano".
     * Aktywność dostaje osobny wpis SMS_SENT / SMS_FAILED, bo dopiero teraz to się stało.
     *
     * Własna transakcja z tego samego powodu co [record]: dispatcher nie trzyma żadnej.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordQueuedOutcome(queuedMessageId: UUID, success: Boolean, errorMessage: String?) {
        val entries = try {
            repository.findAllByQueuedMessageId(queuedMessageId)
        } catch (ex: Exception) {
            logger.error("Failed to load communication log for queued message {}: {}", queuedMessageId, ex.message, ex)
            return
        }
        if (entries.isEmpty()) {
            logger.warn("No communication log entry for queued message {} — outcome not recorded", queuedMessageId)
            return
        }
        val status = if (success) CommunicationStatus.SENT else CommunicationStatus.FAILED
        try {
            repository.resolveQueued(queuedMessageId, CommunicationStatus.QUEUED, status, errorMessage, Instant.now())
        } catch (ex: Exception) {
            logger.error("Failed to resolve queued communication log entry {}: {}", queuedMessageId, ex.message, ex)
            return
        }
        entries.filter { it.status == CommunicationStatus.QUEUED }.forEach { entry ->
            recordAudit(
                entry.id,
                RecordCommunicationCommand(
                    studioId = StudioId(entry.studioId),
                    customerId = CustomerId(entry.customerId),
                    visitId = entry.visitId?.let { VisitId(it) },
                    appointmentId = entry.appointmentId?.let { AppointmentId(it) },
                    channel = entry.channel,
                    messageType = entry.messageType,
                    recipientAddress = entry.recipientAddress,
                    subject = entry.subject,
                    bodyContent = entry.bodyContent,
                    success = success,
                    errorMessage = errorMessage,
                    status = status
                )
            )
        }
    }

    /**
     * Every outbound message in the system passes through [record], so hooking the activity
     * feed in here covers all of them at once — manual sends, automations and campaign
     * dispatch alike — instead of relying on each of the dozens of send paths remembering
     * to log. The communication log itself stays the detailed, per-message record; the
     * audit entry is the one line the owner sees in the company feed.
     */
    private fun recordAudit(id: UUID, command: RecordCommunicationCommand) {
        val status = resolveStatus(command)
        val succeeded = status != CommunicationStatus.FAILED
        val queued = status == CommunicationStatus.QUEUED

        val customer = try {
            customerRepository.findByIdAndStudioId(command.customerId.value, command.studioId.value)
        } catch (ex: Exception) {
            logger.warn("Could not load customer {} for communication audit: {}", command.customerId, ex.message)
            null
        }
        val customerName = customer?.let {
            listOfNotNull(it.firstName, it.lastName).joinToString(" ").takeIf { n -> n.isNotBlank() }
        }

        auditService.recordSync(
            AuditEvent(
                studioId = command.studioId,
                // Prefer the actor captured before any dispatcher switch; fall back to
                // auditActorResolver for automations/campaign dispatch (no principal).
                actor = command.initiatedBy ?: auditActorResolver.current(),
                module = AuditModule.COMMUNICATION,
                action = when {
                    command.channel == CommunicationChannel.SMS && queued -> AuditAction.SMS_QUEUED
                    command.channel == CommunicationChannel.SMS && succeeded -> AuditAction.SMS_SENT
                    command.channel == CommunicationChannel.SMS -> AuditAction.SMS_FAILED
                    queued -> AuditAction.EMAIL_QUEUED
                    succeeded -> AuditAction.EMAIL_SENT
                    else -> AuditAction.EMAIL_FAILED
                },
                entityId = id.toString(),
                entityDisplayName = command.messageType.label,
                changes = listOfNotNull(
                    FieldChange("recipient", null, command.recipientAddress),
                    command.subject?.let { FieldChange("subject", null, it) },
                    if (!succeeded) command.errorMessage?.let { FieldChange("failureReason", null, it) } else null
                ),
                metadata = buildMap {
                    put("messageType", command.messageType.name)
                    put("channel", command.channel.name)
                    put("status", status.name)
                    command.queuedMessageId?.let { put("queuedMessageId", it.toString()) }
                    command.errorMessage?.let { put("error", it) }
                },
                context = AuditContext(
                    customerId = command.customerId,
                    customerName = customerName,
                    visitId = command.visitId,
                    appointmentId = command.appointmentId
                )
            )
        )
    }
}
