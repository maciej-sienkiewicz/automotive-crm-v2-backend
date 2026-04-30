package pl.detailing.crm.visit.transitions.confirm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class SendVisitConfirmedSmsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitConfirmedSmsCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
        if (visitEntity == null) {
            logger.warn("SendVisitConfirmedSms: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(visitEntity.customerId, visitEntity.studioId)
        if (customerEntity == null) {
            logger.warn("SendVisitConfirmedSms: customer not found [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val rawPhone = customerEntity.phone
        if (rawPhone.isNullOrBlank()) {
            logger.debug("SendVisitConfirmedSms: customer has no phone [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val phoneNumber = normalizePolishPhone(rawPhone)
        val firstName = customerEntity.firstName ?: "Kliencie"
        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"
        val message = buildMessage(firstName, vehicleName, visitEntity.visitNumber)

        val result = communicationGateway.sendSms(
            customerId = visitEntity.customerId,
            studioId = visitEntity.studioId,
            phoneNumber = phoneNumber,
            message = message,
            context = "SendVisitConfirmedSms visit=${command.visitId}"
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = command.visitId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.VISIT_CONFIRMED_SMS,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info("SendVisitConfirmedSms: sent [to={} visitId={}]", phoneNumber, command.visitId)
        } else {
            logger.warn(
                "SendVisitConfirmedSms: failed [to={} visitId={} error={}]",
                phoneNumber, command.visitId, result.errorMessage
            )
        }
    }

    private fun buildMessage(firstName: String, vehicleName: String, visitNumber: String): String =
        "Drogi/a $firstName, potwierdzamy rozpoczęcie prac nad Twoim pojazdem $vehicleName " +
        "(wizyta nr $visitNumber). O zakończeniu poinformujemy Cię wiadomością. Pozdrawiamy!"
}

data class SendVisitConfirmedSmsCommand(
    val visitId: VisitId,
    val studioId: StudioId
)
