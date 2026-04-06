package pl.detailing.crm.communication

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
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
    val channel: CommunicationChannel,
    val messageType: CommunicationMessageType,
    val recipientAddress: String,
    val subject: String?,
    val bodyContent: String,
    val success: Boolean,
    val errorMessage: String?
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
    private val repository: CommunicationLogJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun record(command: RecordCommunicationCommand) {
        try {
            repository.save(
                CommunicationLogEntity(
                    id = UUID.randomUUID(),
                    studioId = command.studioId.value,
                    customerId = command.customerId.value,
                    visitId = command.visitId?.value,
                    channel = command.channel,
                    messageType = command.messageType,
                    recipientAddress = command.recipientAddress,
                    subject = command.subject,
                    bodyContent = command.bodyContent,
                    status = if (command.success) CommunicationStatus.SENT else CommunicationStatus.FAILED,
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
    }
}
