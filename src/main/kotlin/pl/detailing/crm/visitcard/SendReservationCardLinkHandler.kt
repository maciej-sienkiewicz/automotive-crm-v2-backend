package pl.detailing.crm.visitcard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SendReservationCardLinkCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    /** Optional override; when null the studio's configured channel is used. */
    val channelOverride: VisitCardDeliveryChannel? = null
)

/**
 * Sends the customer their card link for a reservation (appointment), before
 * check-in. Mirrors [SendVisitCardLinkHandler]: channel comes from the studio's
 * visit-card delivery configuration, with fallback to the other channel when the
 * preferred contact detail is missing.
 */
@Service
class SendReservationCardLinkHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val studioRepository: StudioRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val tokenService: VisitCardTokenService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val properties: VisitCardProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }

    suspend fun handle(command: SendReservationCardLinkCommand): SendVisitCardLinkResult = withContext(Dispatchers.IO) {
        val appointment = appointmentRepository.findByIdAndStudioId(command.appointmentId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Appointment not found: ${command.appointmentId}")

        val customer = customerRepository.findByIdAndStudioId(appointment.customerId, command.studioId.value)
            ?: throw EntityNotFoundException("Customer not found: ${appointment.customerId}")

        val settings = studioSettingsRepository.findById(command.studioId.value).orElse(null)
        if (settings?.visitCardEnabled == false) {
            return@withContext SendVisitCardLinkResult(false, false, "Karta Wizyty jest wyłączona w ustawieniach")
        }
        val channel = command.channelOverride ?: VisitCardDeliveryChannel.fromString(settings?.visitCardDeliveryChannel)
        if (channel == VisitCardDeliveryChannel.NONE) {
            return@withContext SendVisitCardLinkResult(false, false, "Wysyłka Karty Wizyty jest wyłączona w konfiguracji")
        }

        val studioName = settings?.name?.takeIf { it.isNotBlank() }
            ?: studioRepository.findByStudioId(command.studioId.value)?.name
            ?: "Studio detailingu"

        val token = tokenService.getOrCreateTokenForAppointment(command.studioId, command.appointmentId)
        val cardUrl = "${properties.frontendBaseUrl.trimEnd('/')}/vc/$token"

        val hasEmail = !customer.email.isNullOrBlank()
        val hasPhone = !customer.phone.isNullOrBlank()

        val sendEmail = when (channel) {
            VisitCardDeliveryChannel.EMAIL -> hasEmail
            VisitCardDeliveryChannel.SMS -> !hasPhone && hasEmail
            VisitCardDeliveryChannel.BOTH -> hasEmail
            VisitCardDeliveryChannel.NONE -> false
        }
        val sendSms = when (channel) {
            VisitCardDeliveryChannel.EMAIL -> !hasEmail && hasPhone
            VisitCardDeliveryChannel.SMS -> hasPhone
            VisitCardDeliveryChannel.BOTH -> hasPhone
            VisitCardDeliveryChannel.NONE -> false
        }

        if (!sendEmail && !sendSms) {
            return@withContext SendVisitCardLinkResult(false, false, "Klient nie ma adresu e-mail ani numeru telefonu")
        }

        val scheduledAt = DATE_FORMAT.format(appointment.startDateTime.atZone(WARSAW))

        var emailSent = false
        var smsSent = false

        if (sendEmail) {
            val recipient = customer.email!!
            val subject = "Twoja rezerwacja $scheduledAt — $studioName"
            val body = buildString {
                appendLine("Dzień dobry${customer.firstName?.let { " $it" } ?: ""},")
                appendLine()
                appendLine("przygotowaliśmy stronę Twojej rezerwacji (termin: $scheduledAt).")
                appendLine()
                appendLine("Znajdziesz na niej szczegóły rezerwacji i zakres usług z wyceną, a po przyjęciu pojazdu także dokumentację zdjęciową i dokumenty:")
                appendLine(cardUrl)
                appendLine()
                appendLine("Pozdrawiamy,")
                append(studioName)
            }
            val result = communicationGateway.sendEmail(
                customerId = customer.id,
                studioId = command.studioId.value,
                to = recipient,
                subject = subject,
                bodyText = body,
                context = "SendReservationCardLink appointment=${command.appointmentId}"
            )
            emailSent = result.success
            communicationLogService.record(
                RecordCommunicationCommand(
                    studioId = command.studioId,
                    customerId = CustomerId(customer.id),
                    visitId = null,
                    appointmentId = command.appointmentId,
                    channel = CommunicationChannel.EMAIL,
                    messageType = CommunicationMessageType.VISIT_CARD_EMAIL,
                    recipientAddress = recipient,
                    subject = subject,
                    bodyContent = body,
                    success = result.success,
                    errorMessage = result.errorMessage
                )
            )
        }

        if (sendSms) {
            val phone = normalizePolishPhone(customer.phone!!)
            val message = "$studioName: strona Twojej rezerwacji ($scheduledAt): $cardUrl"
            try {
                val result = communicationGateway.sendTransactionalSms(command.studioId.value, phone, message)
                smsSent = result.success
                communicationLogService.record(
                    RecordCommunicationCommand(
                        studioId = command.studioId,
                        customerId = CustomerId(customer.id),
                        visitId = null,
                        appointmentId = command.appointmentId,
                        channel = CommunicationChannel.SMS,
                        messageType = CommunicationMessageType.VISIT_CARD_SMS,
                        recipientAddress = phone,
                        subject = null,
                        bodyContent = message,
                        success = result.success,
                        errorMessage = result.errorMessage
                    )
                )
            } catch (e: InsufficientSmsCreditsException) {
                logger.warn(
                    "SendReservationCardLink: no SMS credits [studioId={} appointmentId={}]",
                    command.studioId, command.appointmentId
                )
                communicationLogService.record(
                    RecordCommunicationCommand(
                        studioId = command.studioId,
                        customerId = CustomerId(customer.id),
                        visitId = null,
                        appointmentId = command.appointmentId,
                        channel = CommunicationChannel.SMS,
                        messageType = CommunicationMessageType.VISIT_CARD_SMS,
                        recipientAddress = phone,
                        subject = null,
                        bodyContent = message,
                        success = false,
                        errorMessage = "Brak kredytów SMS"
                    )
                )
            }
        }

        logger.info(
            "SendReservationCardLink: appointment={} channel={} emailSent={} smsSent={}",
            command.appointmentId, channel, emailSent, smsSent
        )

        val message = when {
            emailSent && smsSent -> "Karta Wizyty wysłana e-mailem i SMS-em"
            emailSent -> "Karta Wizyty wysłana e-mailem"
            smsSent -> "Karta Wizyty wysłana SMS-em"
            else -> "Nie udało się wysłać Karty Wizyty"
        }
        SendVisitCardLinkResult(emailSent, smsSent, message)
    }
}
