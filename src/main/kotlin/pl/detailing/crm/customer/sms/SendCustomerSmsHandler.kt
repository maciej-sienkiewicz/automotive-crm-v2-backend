package pl.detailing.crm.customer.sms

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
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.normalizePolishPhone

/**
 * SMS napisany ręcznie z karty klienta — jedyna ścieżka, w której treść pochodzi
 * wprost od użytkownika, a nie z szablonu czy automatu.
 *
 * Wysyłka idzie przez [OutboundCommunicationGateway], więc dziedziczy komplet
 * kontroli (moduł, zgoda marketingowa, kredyty) bez żadnej pracy tutaj, a wynik
 * ląduje w dzienniku komunikacji razem z pozostałymi wiadomościami do klienta.
 */
@Service
class SendCustomerSmsHandler(
    private val customerRepository: CustomerRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendCustomerSmsCommand): SendCustomerSmsResult = withContext(Dispatchers.IO) {
        val message = command.message.trim()
        if (message.isEmpty()) throw ValidationException("Treść SMS-a nie może być pusta")
        if (message.length > MAX_MESSAGE_LENGTH) {
            throw ValidationException("Treść SMS-a nie może przekraczać $MAX_MESSAGE_LENGTH znaków")
        }

        val customer = customerRepository.findByIdAndStudioId(command.customerId.value, command.studioId.value)
            ?: throw NotFoundException("Klient ${command.customerId.value} nie istnieje w tym studiu")

        val rawPhone = customer.phone
        if (rawPhone.isNullOrBlank()) {
            throw ValidationException("Ten klient nie ma zapisanego numeru telefonu")
        }
        val phoneNumber = normalizePolishPhone(rawPhone)

        val result = communicationGateway.sendSms(
            customerId = command.customerId.value,
            studioId = command.studioId.value,
            phoneNumber = phoneNumber,
            message = message,
            context = "SendCustomerSms customer=${command.customerId.value}"
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = command.customerId,
                visitId = null,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.MANUAL_SMS,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info("SendCustomerSms: SMS sent [customerId={}]", command.customerId.value)
        } else {
            logger.warn(
                "SendCustomerSms: SMS failed [customerId={} error={}]",
                command.customerId.value, result.errorMessage
            )
        }

        SendCustomerSmsResult(
            success = result.success,
            phoneNumber = phoneNumber,
            errorMessage = result.errorMessage
        )
    }

    companion object {
        /** Cztery segmenty GSM-7 — dłuższej treści nie da się już rozsądnie wycenić. */
        const val MAX_MESSAGE_LENGTH = 612
    }
}

data class SendCustomerSmsCommand(
    val studioId: StudioId,
    val customerId: CustomerId,
    val message: String
)

data class SendCustomerSmsResult(
    val success: Boolean,
    val phoneNumber: String,
    val errorMessage: String?
)
