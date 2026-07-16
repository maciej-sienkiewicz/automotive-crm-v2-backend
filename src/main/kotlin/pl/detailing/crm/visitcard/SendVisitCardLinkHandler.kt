package pl.detailing.crm.visitcard

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
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val channelOverride: VisitCardDeliveryChannel? = null
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

    suspend fun handle(command: SendVisitCardLinkCommand): SendVisitCardLinkResult = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, command.studioId.value)
            ?: throw EntityNotFoundException("Customer not found: ${visitEntity.customerId}")

        val settings = studioSettingsRepository.findById(command.studioId.value).orElse(null)
        val channel = command.channelOverride ?: VisitCardDeliveryChannel.fromString(settings?.visitCardDeliveryChannel)
        if (channel == VisitCardDeliveryChannel.NONE) {
            return@withContext SendVisitCardLinkResult(false, false, "Wysyłka Karty Wizyty jest wyłączona w konfiguracji")
        }

        val studioName = settings?.name?.takeIf { it.isNotBlank() }
            ?: studioRepository.findByStudioId(command.studioId.value)?.name
            ?: "Studio detailingu"

        val token = tokenService.getOrCreateToken(command.studioId, command.visitId)
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

        val scheduledAt = DATE_FORMAT.format(visitEntity.scheduledDate.atZone(WARSAW))

        var emailSent = false
        var smsSent = false

        if (sendEmail) {
            val recipient = customer.email!!
            val subject = "Karta wizyty ${visitEntity.visitNumber} — $studioName"
            val body = buildString {
                appendLine("Dzień dobry${customer.firstName?.let { " $it" } ?: ""},")
                appendLine()
                appendLine("przygotowaliśmy Kartę Wizyty dla Twojego pojazdu ${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot} (wizyta ${visitEntity.visitNumber}, termin: $scheduledAt).")
                appendLine()
                appendLine("Znajdziesz na niej szczegóły rezerwacji, zakres usług z wyceną oraz — w trakcie wizyty — dokumentację zdjęciową i dokumenty:")
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
                    errorMessage = result.errorMessage
                )
            )
        }

        if (sendSms) {
            val phone = normalizePolishPhone(customer.phone!!)
            val message = "$studioName: Karta Twojej wizyty ${visitEntity.visitNumber} " +
                "(${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}): $cardUrl"
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
                        errorMessage = result.errorMessage
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
                        errorMessage = "Brak kredytów SMS"
                    )
                )
            }
        }

        logger.info(
            "SendVisitCardLink: visit={} channel={} emailSent={} smsSent={}",
            command.visitId, channel, emailSent, smsSent
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
