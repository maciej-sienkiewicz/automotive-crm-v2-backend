package pl.detailing.crm.visitcard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.email.domain.EmailAutomationConfigRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.domain.VisitCardChannel

/** Delivery channel configured per studio (studio_settings.visit_card_delivery_channel). */
enum class VisitCardDeliveryChannel {
    EMAIL, SMS, BOTH, NONE;

    companion object {
        fun fromString(value: String?): VisitCardDeliveryChannel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: EMAIL
    }
}

data class SendVisitCardLinkCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    /** Optional override; when null the studio's configured channel is used. */
    val channelOverride: VisitCardDeliveryChannel? = null,
    /**
     * The employee who triggered the send. Captured on the request thread before any
     * coroutine dispatcher switch so the audit entry shows the real person, not "System".
     * Null for automation-triggered sends (no logged-in principal).
     */
    val initiatedBy: AuditActor? = null
)

data class SendVisitCardLinkResult(
    val emailSent: Boolean,
    val smsSent: Boolean,
    val message: String
)

/**
 * Sends the customer their Visit Card link by e-mail and/or SMS, depending on
 * the studio's configured delivery channel. Falls back to the other channel
 * when the preferred one has no usable contact data.
 */
@Service
class SendVisitCardLinkHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val tokenService: VisitCardTokenService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val smsAutomationConfigRepository: SmsAutomationConfigRepository,
    private val emailAutomationConfigRepository: EmailAutomationConfigRepository,
    private val renderer: MessageTemplateRenderer,
    private val properties: VisitCardProperties,
    private val businessEventPublisher: BusinessEventPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }

    suspend fun handle(command: SendVisitCardLinkCommand): SendVisitCardLinkResult = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, command.studioId.value)
            ?: throw EntityNotFoundException("Customer not found: ${visitEntity.customerId}")

        val settings = studioSettingsRepository.findById(command.studioId.value).orElse(null)
        if (settings?.visitCardEnabled == false) {
            return@withContext SendVisitCardLinkResult(false, false, "Karta Wizyty jest wyłączona w ustawieniach")
        }
        val channel = command.channelOverride ?: VisitCardDeliveryChannel.fromString(settings?.visitCardDeliveryChannel)
        if (channel == VisitCardDeliveryChannel.NONE) {
            return@withContext SendVisitCardLinkResult(false, false, "Wysyłka Karty Wizyty jest wyłączona w konfiguracji")
        }

        val token = tokenService.getOrCreateToken(
            command.studioId, command.visitId, pl.detailing.crm.shared.AppointmentId(visitEntity.appointmentId)
        )
        val cardUrl = "${properties.frontendBaseUrl.trimEnd('/')}/vc/$token"

        val hasEmail = !customer.email.isNullOrBlank()
        val hasPhone = !customer.phone.isNullOrBlank()

        // Preferred channel with fallback: never lose the message just because
        // the customer record is missing the preferred contact detail.
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

        val scheduled = visitEntity.scheduledDate.atZone(WARSAW)
        val templateValues = mapOf(
            "imie" to customer.firstName.orEmpty(),
            "nazwisko" to customer.lastName.orEmpty(),
            "imie_nazwisko" to listOfNotNull(customer.firstName, customer.lastName).joinToString(" "),
            "pojazd" to "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}",
            "rejestracja" to visitEntity.licensePlateSnapshot.orEmpty(),
            "numer_wizyty" to visitEntity.visitNumber,
            "data" to DATE_FORMAT.format(scheduled),
            "godzina" to TIME_FORMAT.format(scheduled),
            "link" to cardUrl
        )

        var emailSent = false
        var smsSent = false

        val emailRule = emailAutomationConfigRepository.findByStudioId(command.studioId)?.visitCardLink
        if (sendEmail && emailRule?.sendable == true) {
            val recipient = customer.email!!
            val subject = renderer.render(emailRule.subjectTemplate, templateValues)
            val body = renderer.render(emailRule.bodyTemplate, templateValues)
            val result = communicationGateway.sendEmail(
                customerId = customer.id,
                studioId = command.studioId.value,
                to = recipient,
                subject = subject,
                bodyText = body,
                context = "SendVisitCardLink visit=${command.visitId}"
            )
            emailSent = result.success
            communicationLogService.record(
                RecordCommunicationCommand(
                    studioId = command.studioId,
                    customerId = CustomerId(customer.id),
                    visitId = command.visitId,
                    channel = CommunicationChannel.EMAIL,
                    messageType = CommunicationMessageType.VISIT_CARD_EMAIL,
                    recipientAddress = recipient,
                    subject = subject,
                    bodyContent = body,
                    success = result.success,
                    errorMessage = result.errorMessage,
                    initiatedBy = command.initiatedBy
                )
            )
        }

        val smsRule = smsAutomationConfigRepository.findByStudioId(command.studioId)?.visitCardLink
        if (sendSms && smsRule?.sendable == true) {
            val phone = normalizePolishPhone(customer.phone!!)
            val message = renderer.render(smsRule.messageTemplate, templateValues)
            try {
                val result = communicationGateway.sendTransactionalSms(command.studioId.value, phone, message)
                smsSent = result.success
                communicationLogService.record(
                    RecordCommunicationCommand(
                        studioId = command.studioId,
                        customerId = CustomerId(customer.id),
                        visitId = command.visitId,
                        channel = CommunicationChannel.SMS,
                        messageType = CommunicationMessageType.VISIT_CARD_SMS,
                        recipientAddress = phone,
                        subject = null,
                        bodyContent = message,
                        success = result.success,
                        errorMessage = result.errorMessage,
                        initiatedBy = command.initiatedBy
                    )
                )
            } catch (e: InsufficientSmsCreditsException) {
                logger.warn("SendVisitCardLink: no SMS credits [studioId={} visitId={}]", command.studioId, command.visitId)
                communicationLogService.record(
                    RecordCommunicationCommand(
                        studioId = command.studioId,
                        customerId = CustomerId(customer.id),
                        visitId = command.visitId,
                        channel = CommunicationChannel.SMS,
                        messageType = CommunicationMessageType.VISIT_CARD_SMS,
                        recipientAddress = phone,
                        subject = null,
                        bodyContent = message,
                        success = false,
                        errorMessage = "Brak kredytów SMS",
                        initiatedBy = command.initiatedBy
                    )
                )
            }
        }

        // Live metrics — liczymy realnie wysłane kanały, nie samo żądanie wysyłki.
        // Kanał, który poległ na braku szablonu, zgody albo kredytów, nie jest wysłaną kartą.
        for (ch in listOfNotNull(
            VisitCardChannel.EMAIL.takeIf { emailSent },
            VisitCardChannel.SMS.takeIf { smsSent }
        )) {
            businessEventPublisher.publish(
                tenantId = command.studioId,
                type = BusinessEventType.VISIT_CARD_SENT,
                dimensionValue = ch.name,
                attributes = mapOf("visitId" to command.visitId.value.toString())
            )
        }

        logger.info(
            "SendVisitCardLink: visit={} channel={} emailSent={} smsSent={}",
            command.visitId, channel, emailSent, smsSent
        )

        val templatesReady = (sendEmail && emailRule?.sendable == true) || (sendSms && smsRule?.sendable == true)
        val message = when {
            emailSent && smsSent -> "Karta Wizyty wysłana e-mailem i SMS-em"
            emailSent -> "Karta Wizyty wysłana e-mailem"
            smsSent -> "Karta Wizyty wysłana SMS-em"
            !templatesReady -> "Uzupełnij treść wiadomości w Ustawieniach → Szablony SMS / Szablony email"
            else -> "Nie udało się wysłać Karty Wizyty"
        }
        SendVisitCardLinkResult(emailSent, smsSent, message)
    }
}
