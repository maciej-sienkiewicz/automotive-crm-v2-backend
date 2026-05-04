package pl.detailing.crm.email.visitready

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.template.EmailTemplateContext
import pl.detailing.crm.email.template.EmailTemplateProcessor
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class SendVisitReadyForPickupEmailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val studioRepository: StudioRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val emailTemplateConfigHandler: GetEmailTemplateConfigHandler,
    private val emailTemplateProcessor: EmailTemplateProcessor
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitReadyForPickupEmailCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
        if (visitEntity == null) {
            logger.warn("SendVisitReadyForPickupEmail: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(
            visitEntity.customerId,
            visitEntity.studioId
        )
        if (customerEntity == null) {
            logger.warn("SendVisitReadyForPickupEmail: customer not found [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val recipientEmail = customerEntity.email
        if (recipientEmail.isNullOrBlank()) return@withContext

        val templateConfig = emailTemplateConfigHandler.handle(command.studioId)
        val rule = templateConfig.visitReadyForPickup

        val studioName = studioRepository.findById(command.studioId.value).map { it.name }.orElse("")
        val firstName = customerEntity.firstName ?: "Kliencie"
        val fullName = listOfNotNull(customerEntity.firstName, customerEntity.lastName)
            .joinToString(" ").ifBlank { "Kliencie" }
        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"

        val context = EmailTemplateContext(
            firstName = firstName,
            fullName = fullName,
            studioName = studioName,
            vehicleName = vehicleName,
            licensePlate = visitEntity.licensePlateSnapshot,
            visitNumber = visitEntity.visitNumber
        )

        val subject = emailTemplateProcessor.process(rule.subjectTemplate, context)
        val body = emailTemplateProcessor.process(rule.bodyTemplate, context)

        val result = communicationGateway.sendEmail(
            customerId = visitEntity.customerId,
            studioId = visitEntity.studioId,
            to = recipientEmail,
            subject = subject,
            bodyText = body,
            context = "SendVisitReadyForPickupEmail visit=${command.visitId}"
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = command.visitId,
                channel = CommunicationChannel.EMAIL,
                messageType = CommunicationMessageType.VISIT_READY_FOR_PICKUP_EMAIL,
                recipientAddress = recipientEmail,
                subject = subject,
                bodyContent = body,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info("SendVisitReadyForPickupEmail: email sent [to={} visitId={}]", recipientEmail, command.visitId)
        }
    }
}

data class SendVisitReadyForPickupEmailCommand(
    val visitId: VisitId,
    val studioId: StudioId
)
