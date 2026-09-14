package pl.detailing.crm.visitcard.upsell

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.VisitCardProperties
import pl.detailing.crm.visitcard.VisitCardTokenService
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import java.time.Instant
import java.util.UUID

/** Wynik powiadomienia klienta o nowej propozycji — do pokazania pracownikowi obok sugestii. */
data class UpsellNotificationResult(
    /** Wiadomość przyjęta (wysłana od ręki albo zakolejkowana na okno wysyłki). */
    val sent: Boolean,
    val queued: Boolean,
    val scheduledFor: Instant?,
    /** Czytelny komunikat dla pracownika: co się stało albo dlaczego nie poszło. */
    val message: String
)

/**
 * SMS „Upselling": pracownik dodał propozycje usług na Karcie Wizyty i poprosił,
 * żeby klient się o tym dowiedział.
 *
 * Jedna wiadomość na całą listę, nie jedna na usługę: pracownik ogląda auto raz
 * i widzi kilka rzeczy do zrobienia, a klient ma dostać jednego SMS-a wymieniającego
 * wszystko, nie trzy pod rząd — każdy za osobny kredyt.
 *
 * To informacja z linkiem do karty, nie prośba o zgodę — zgoda („odpisz TAK") idzie
 * osobną ścieżką dopiero wtedy, gdy klient sam wybierze usługę na karcie. Dlatego
 * wiadomość idzie domyślną polityką bramki i poza godzinami komunikacji czeka
 * na okno wysyłki: klient nie stoi przy ladzie, nikt na nią nie czeka.
 *
 * Każda blokada (brak numeru, wyłączony szablon, brak modułu, brak kredytów) wraca
 * jako komunikat w [UpsellNotificationResult], nie jako wyjątek: sugestia jest już
 * zapisana i ma zostać, a pracownik ma zobaczyć, dlaczego klient nie dostał SMS-a.
 */
@Service
class NotifyUpsellSuggestionHandler(
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val smsAutomationConfigRepository: SmsAutomationConfigRepository,
    private val renderer: MessageTemplateRenderer,
    private val tokenService: VisitCardTokenService,
    private val properties: VisitCardProperties,
    private val gateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun notifyForVisit(
        visitId: VisitId,
        studioId: StudioId,
        suggestions: List<VisitUpsellSuggestionEntity>
    ): UpsellNotificationResult {
        if (suggestions.isEmpty()) return failed("Brak propozycji do zgłoszenia klientowi")
        val visit = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: return failed("Nie znaleziono wizyty")
        val token = tokenService.getOrCreateToken(studioId, visitId, AppointmentId(visit.appointmentId))
        return notify(
            studioId = studioId,
            customerId = visit.customerId,
            visitId = visitId,
            appointmentId = AppointmentId(visit.appointmentId),
            token = token,
            suggestions = suggestions
        )
    }

    fun notifyForAppointment(
        appointmentId: AppointmentId,
        studioId: StudioId,
        suggestions: List<VisitUpsellSuggestionEntity>
    ): UpsellNotificationResult {
        if (suggestions.isEmpty()) return failed("Brak propozycji do zgłoszenia klientowi")
        val appointment = appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value)
            ?: return failed("Nie znaleziono rezerwacji")
        val token = tokenService.getOrCreateTokenForAppointment(studioId, appointmentId)
        return notify(
            studioId = studioId,
            customerId = appointment.customerId,
            visitId = null,
            appointmentId = appointmentId,
            token = token,
            suggestions = suggestions
        )
    }

    private fun notify(
        studioId: StudioId,
        customerId: UUID,
        visitId: VisitId?,
        appointmentId: AppointmentId?,
        token: String,
        suggestions: List<VisitUpsellSuggestionEntity>
    ): UpsellNotificationResult {
        val rule = smsAutomationConfigRepository.findByStudioId(studioId)?.upsellSuggestion
        if (rule == null || !rule.enabled || rule.messageTemplate.isBlank()) {
            return failed("Szablon SMS „Propozycja dodatkowych usług” jest wyłączony w ustawieniach komunikacji")
        }

        val customer = customerRepository.findByIdAndStudioId(customerId, studioId.value)
            ?: return failed("Nie znaleziono klienta")
        val rawPhone = customer.phone?.takeIf { it.isNotBlank() }
            ?: return failed("Klient nie ma zapisanego numeru telefonu")
        val phone = normalizePolishPhone(rawPhone)

        val cardUrl = "${properties.frontendBaseUrl.trimEnd('/')}/vc/$token"
        val message = renderer.render(
            rule.messageTemplate,
            mapOf(
                "imie" to customer.firstName.orEmpty(),
                "nazwisko" to customer.lastName.orEmpty(),
                "uslugi" to suggestions.joinToString(", ") { it.serviceName },
                "link" to cardUrl
            )
        )

        val result = try {
            gateway.sendSms(
                customerId = customerId,
                studioId = studioId.value,
                phoneNumber = phone,
                message = message,
                context = "UpsellSuggestion suggestions=${suggestions.joinToString(",") { it.id.toString() }}"
            )
        } catch (e: InsufficientSmsCreditsException) {
            logger.warn(
                "Upsell suggestion SMS blocked — no credits | studio={} suggestions={}",
                studioId, suggestions.size
            )
            record(studioId, customerId, visitId, appointmentId, phone, message, success = false, error = "Brak kredytów SMS", queuedMessageId = null)
            return failed("Brak kredytów SMS — klient nie został powiadomiony")
        }

        record(
            studioId, customerId, visitId, appointmentId, phone, message,
            success = result.success, error = result.errorMessage, queuedMessageId = result.queuedMessageId
        )

        return when {
            !result.success -> failed("Nie udało się wysłać SMS-a: ${result.errorMessage ?: "błąd dostawcy"}")
            result.queued -> UpsellNotificationResult(
                sent = true, queued = true, scheduledFor = result.scheduledFor,
                message = "SMS do klienta czeka na godziny wysyłki"
            )
            else -> UpsellNotificationResult(sent = true, queued = false, scheduledFor = null, message = "SMS do klienta został wysłany")
        }
    }

    private fun record(
        studioId: StudioId,
        customerId: UUID,
        visitId: VisitId?,
        appointmentId: AppointmentId?,
        phone: String,
        message: String,
        success: Boolean,
        error: String?,
        queuedMessageId: UUID?
    ) {
        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = studioId,
                customerId = CustomerId(customerId),
                visitId = visitId,
                appointmentId = appointmentId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.VISIT_CARD_UPSELL_SUGGESTION_SMS,
                recipientAddress = phone,
                subject = null,
                bodyContent = message,
                success = success,
                errorMessage = error,
                queuedMessageId = queuedMessageId
            )
        )
    }

    private fun failed(reason: String) = UpsellNotificationResult(sent = false, queued = false, scheduledFor = null, message = reason)
}
