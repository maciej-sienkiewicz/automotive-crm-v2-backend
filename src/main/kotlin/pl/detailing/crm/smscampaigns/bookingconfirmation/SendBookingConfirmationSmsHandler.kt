package pl.detailing.crm.smscampaigns.bookingconfirmation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscampaigns.template.SmsTemplateContext
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor

@Service
class SendBookingConfirmationSmsHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val smsProvider: SmsProvider,
    private val communicationLogService: CommunicationLogService,
    private val configRepository: SmsAutomationConfigRepository,
    private val templateProcessor: SmsTemplateProcessor
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendBookingConfirmationSmsCommand): Unit = withContext(Dispatchers.IO) {
        val config = configRepository.findByStudioId(command.studioId)
            ?: SmsAutomationConfig.defaultFor(command.studioId)

        if (!config.bookingConfirmation.enabled) {
            logger.debug("SendBookingConfirmationSms: rule disabled [studioId={}]", command.studioId)
            return@withContext
        }

        val appointment = appointmentRepository.findByIdAndStudioId(
            command.appointmentId.value,
            command.studioId.value
        )
        if (appointment == null) {
            logger.warn(
                "SendBookingConfirmationSms: appointment not found [appointmentId={}]",
                command.appointmentId
            )
            return@withContext
        }

        val customer = customerRepository.findByIdAndStudioId(
            appointment.customerId,
            appointment.studioId
        )
        if (customer == null) {
            logger.warn(
                "SendBookingConfirmationSms: customer not found [customerId={}]",
                appointment.customerId
            )
            return@withContext
        }

        val rawPhone = customer.phone
        if (rawPhone.isNullOrBlank()) {
            logger.debug(
                "SendBookingConfirmationSms: customer has no phone [customerId={}]",
                appointment.customerId
            )
            return@withContext
        }

        val phoneNumber = normalizePolishPhone(rawPhone)
        val message = templateProcessor.process(
            config.bookingConfirmation.messageTemplate,
            SmsTemplateContext(
                firstName = customer.firstName ?: "Kliencie",
                appointmentStart = appointment.startDateTime,
                studioName = command.studioName
            )
        )

        val result = smsProvider.send(phoneNumber, message)

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = CustomerId(appointment.customerId),
                visitId = null,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.SMS_BOOKING_CONFIRMATION,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SendBookingConfirmationSms: SMS sent [to={} appointmentId={}]",
                phoneNumber, command.appointmentId
            )
        } else {
            logger.warn(
                "SendBookingConfirmationSms: SMS failed [to={} appointmentId={} error={}]",
                phoneNumber, command.appointmentId, result.errorMessage
            )
        }
    }
}

data class SendBookingConfirmationSmsCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val studioName: String
)
