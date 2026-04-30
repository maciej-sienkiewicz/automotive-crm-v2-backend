package pl.detailing.crm.smscampaigns.visitready

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class SendVisitReadyForPickupSmsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val smsProvider: SmsProvider,
    private val communicationLogService: CommunicationLogService,
    private val marketingConsentChecker: MarketingConsentChecker
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitReadyForPickupSmsCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
        if (visitEntity == null) {
            logger.warn("SendVisitReadyForPickupSms: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(
            visitEntity.customerId,
            visitEntity.studioId
        )
        if (customerEntity == null) {
            logger.warn("SendVisitReadyForPickupSms: customer not found [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val rawPhone = customerEntity.phone
        if (rawPhone.isNullOrBlank()) {
            logger.debug("SendVisitReadyForPickupSms: customer has no phone [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        if (!marketingConsentChecker.canSend(
                customerId = visitEntity.customerId,
                studioId = visitEntity.studioId,
                channel = MarketingChannel.SMS,
                context = "SendVisitReadyForPickupSms visit=${command.visitId}"
            )) return@withContext

        val phoneNumber = normalizePolishPhone(rawPhone)
        val firstName = customerEntity.firstName ?: "Kliencie"
        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"
        val message = buildMessage(firstName, vehicleName, visitEntity.visitNumber)

        val result = smsProvider.send(phoneNumber, message)

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = command.visitId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.VISIT_READY_FOR_PICKUP_SMS,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SendVisitReadyForPickupSms: SMS sent [to={} visitId={}]",
                phoneNumber, command.visitId
            )
        } else {
            logger.warn(
                "SendVisitReadyForPickupSms: SMS failed [to={} visitId={} error={}]",
                phoneNumber, command.visitId, result.errorMessage
            )
        }
    }

    private fun buildMessage(firstName: String, vehicleName: String, visitNumber: String): String =
        "Drogi/a $firstName, Twój pojazd $vehicleName jest gotowy do odbioru. " +
        "Numer wizyty: $visitNumber. Zapraszamy!"
}

data class SendVisitReadyForPickupSmsCommand(
    val visitId: VisitId,
    val studioId: StudioId
)
