package pl.detailing.crm.communication

import org.springframework.stereotype.Service
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

data class GetVisitCommunicationCommand(
    val visitId: VisitId,
    val studioId: StudioId
)

data class CommunicationLogItem(
    val id: String,
    val channel: CommunicationChannel,
    val messageType: CommunicationMessageType,
    val messageTypeLabel: String,
    val recipientAddress: String,
    val subject: String?,
    val bodyContent: String,
    val status: CommunicationStatus,
    val errorMessage: String?,
    val sentAt: Instant
)

data class GetVisitCommunicationResult(
    val visitId: String,
    val entries: List<CommunicationLogItem>
)

/**
 * Returns all outbound communication entries associated with a specific visit.
 * Verifies visit existence within the studio before querying logs.
 */
@Service
class GetVisitCommunicationHandler(
    private val visitRepository: VisitRepository,
    private val communicationLogJpaRepository: CommunicationLogJpaRepository
) {

    fun handle(command: GetVisitCommunicationCommand): GetVisitCommunicationResult {
        visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit not found [visitId=${command.visitId}]")

        val entries = communicationLogJpaRepository
            .findByVisitIdAndStudioId(command.visitId.value, command.studioId.value)
            .map { it.toItem() }

        return GetVisitCommunicationResult(
            visitId = command.visitId.toString(),
            entries = entries
        )
    }
}

internal fun CommunicationLogEntity.toItem() = CommunicationLogItem(
    id = id.toString(),
    channel = channel,
    messageType = messageType,
    messageTypeLabel = messageType.label,
    recipientAddress = recipientAddress,
    subject = subject,
    bodyContent = bodyContent,
    status = status,
    errorMessage = errorMessage,
    sentAt = sentAt
)
