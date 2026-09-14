package pl.detailing.crm.communication

import org.springframework.stereotype.Service
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class GetCustomerCommunicationCommand(
    val customerId: CustomerId,
    val studioId: StudioId
)

data class CustomerCommunicationLogItem(
    val id: String,
    val visitId: String?,
    val channel: pl.detailing.crm.shared.CommunicationChannel,
    val messageType: pl.detailing.crm.shared.CommunicationMessageType,
    val messageTypeLabel: String,
    val recipientAddress: String,
    val subject: String?,
    val bodyContent: String,
    val status: pl.detailing.crm.shared.CommunicationStatus,
    val errorMessage: String?,
    val sentAt: Instant,
    /** Dla QUEUED: kiedy dispatcher kolejki wyśle wiadomość. Null dla pozostałych statusów. */
    val scheduledFor: Instant? = null
)

data class GetCustomerCommunicationResult(
    val customerId: String,
    val entries: List<CustomerCommunicationLogItem>
)

/**
 * Returns the full communication history for a customer across all visits,
 * including communication not tied to any specific visit (visitId = null).
 *
 * Results are ordered newest-first.
 */
@Service
class GetCustomerCommunicationHandler(
    private val customerRepository: CustomerRepository,
    private val communicationLogJpaRepository: CommunicationLogJpaRepository,
    private val scheduledForResolver: QueuedScheduleResolver
) {

    fun handle(command: GetCustomerCommunicationCommand): GetCustomerCommunicationResult {
        customerRepository.findByIdAndStudioId(command.customerId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Customer not found [customerId=${command.customerId}]")

        val logs = communicationLogJpaRepository
            .findByCustomerIdAndStudioId(command.customerId.value, command.studioId.value)
        val scheduledFor = scheduledForResolver.resolve(logs)
        val entries = logs
            .map { entity ->
                CustomerCommunicationLogItem(
                    id = entity.id.toString(),
                    visitId = entity.visitId?.toString(),
                    channel = entity.channel,
                    messageType = entity.messageType,
                    messageTypeLabel = entity.messageType.label,
                    recipientAddress = entity.recipientAddress,
                    subject = entity.subject,
                    bodyContent = entity.bodyContent,
                    status = entity.status,
                    errorMessage = entity.errorMessage,
                    sentAt = entity.sentAt,
                    scheduledFor = scheduledFor[entity.id]
                )
            }

        return GetCustomerCommunicationResult(
            customerId = command.customerId.toString(),
            entries = entries
        )
    }
}
