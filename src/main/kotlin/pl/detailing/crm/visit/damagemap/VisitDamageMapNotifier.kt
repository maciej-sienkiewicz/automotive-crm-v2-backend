package pl.detailing.crm.visit.damagemap

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult

/**
 * „Poinformuj klienta o zmianach: TAK/NIE" — strona TAK.
 *
 * Kanał nie jest wyborem użytkownika, tylko konsekwencją tego, co mamy w kartotece:
 * mapa uszkodzeń to załącznik, a załącznik idzie mailem. SMS jest planem awaryjnym
 * (sam link nie wchodzi — nie ma publicznego adresu na plik) i mówi klientowi
 * tylko, ŻE dokument się zmienił; bez maila to jedyna rzecz, jaką da się przekazać
 * uczciwie.
 *
 * Wysyłka nigdy nie wywraca aktualizacji mapy: punkty są już zapisane, a nieudany
 * SMS nie może cofnąć faktu, że na pojeździe jest nowa rysa. Wynik wraca do UI i
 * ląduje w historii komunikacji, więc „nie doszło" jest widoczne, a nie zgadywane.
 */
@Service
class VisitDamageMapNotifier(
    private val gateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(VisitDamageMapNotifier::class.java)
    }

    fun notifyCustomer(request: DamageMapNotificationRequest): DamageMapNotificationResult {
        val emailOutcome = request.recipientEmail
            ?.takeIf { it.isNotBlank() }
            ?.let { sendEmail(request, it) }

        // SMS tylko wtedy, gdy maila nie ma albo nie doszedł: dwa powiadomienia o
        // jednej rysie brzmią jak dwie rysy.
        val smsOutcome = if (emailOutcome?.sent == true) null else {
            request.recipientPhone
                ?.takeIf { it.isNotBlank() }
                ?.let { sendSms(request, it) }
        }

        val message = buildString {
            when {
                emailOutcome?.sent == true -> append("E-mail z aktualną mapą uszkodzeń wysłany na ${emailOutcome.address}.")
                smsOutcome?.sent == true -> append("SMS o aktualizacji mapy uszkodzeń wysłany na ${smsOutcome.address}.")
                emailOutcome != null || smsOutcome != null -> {
                    append("Nie udało się powiadomić klienta")
                    val reason = emailOutcome?.error ?: smsOutcome?.error
                    if (reason != null) append(": $reason")
                    append(".")
                }
                else -> append("Klient nie ma ani adresu e-mail, ani numeru telefonu — powiadomienie nie zostało wysłane.")
            }
        }

        return DamageMapNotificationResult(
            emailSent = emailOutcome?.sent == true,
            smsSent = smsOutcome?.sent == true,
            message = message
        )
    }

    private fun sendEmail(request: DamageMapNotificationRequest, address: String): ChannelOutcome {
        val subject = "Aktualizacja mapy uszkodzeń — wizyta ${request.visitNumber}"
        /*
         * Zdanie o dostarczeniu dokłada TU, a nie w treści od operatora: on pisze
         * wiadomość, nie wiedząc, czy pójdzie mailem (z załącznikiem) czy SMS-em
         * (bez). „Dokument w załączniku" wpisane po jego stronie byłoby kłamstwem
         * dokładnie w tym drugim przypadku.
         */
        val body = request.messageBody
            ?.let { operatorText ->
                if (request.pdfBytes != null) "$operatorText\n\nAktualny dokument znajduje się w załączniku."
                else operatorText
            }
            ?: defaultEmailBody(request)

        val attachments = request.pdfBytes
            ?.let {
                listOf(
                    EmailAttachment(
                        fileName = "mapa_uszkodzen_${request.visitNumber}.pdf",
                        content = it,
                        contentType = "application/pdf"
                    )
                )
            }
            ?: emptyList()

        val result = gateway.sendEmail(
            customerId = request.customerId.value,
            studioId = request.studioId.value,
            to = address,
            subject = subject,
            bodyText = body,
            attachments = attachments,
            context = "VisitDamageMapUpdated visit=${request.visitId.value}",
            // Zmiana w dokumencie opisującym stan pojazdu jest informacją, na którą
            // klient może chcieć zareagować jeszcze w trakcie wizyty — okno wysyłki
            // przesunęłoby ją na następny dzień.
            delivery = DeliveryPolicy.IMMEDIATE
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = request.studioId,
                customerId = request.customerId,
                visitId = request.visitId,
                channel = CommunicationChannel.EMAIL,
                messageType = CommunicationMessageType.VISIT_DAMAGE_MAP_UPDATED_EMAIL,
                recipientAddress = address,
                subject = subject,
                bodyContent = body,
                success = result.success,
                errorMessage = result.errorMessage,
                queuedMessageId = result.queuedMessageId
            )
        )

        if (!result.success) {
            logger.warn(
                "Damage-map e-mail failed [visit={} to={}]: {}",
                request.visitId, address, result.errorMessage
            )
        }
        return ChannelOutcome(result.success, address, result.errorMessage)
    }

    private fun sendSms(request: DamageMapNotificationRequest, phone: String): ChannelOutcome {
        val normalized = normalizePolishPhone(phone)
        val body = request.messageBody ?: defaultSmsBody(request)

        val result = try {
            gateway.sendTransactionalSms(
                request.studioId.value,
                normalized,
                body,
                delivery = DeliveryPolicy.IMMEDIATE
            )
        } catch (e: InsufficientSmsCreditsException) {
            logger.warn("Damage-map SMS blocked — no credits [studio={}]", request.studioId.value)
            SmsDeliveryResult.failure("Brak kredytów SMS")
        }

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = request.studioId,
                customerId = request.customerId,
                visitId = request.visitId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.VISIT_DAMAGE_MAP_UPDATED_SMS,
                recipientAddress = normalized,
                subject = null,
                bodyContent = body,
                success = result.success,
                errorMessage = result.errorMessage,
                queuedMessageId = result.queuedMessageId
            )
        )

        if (!result.success) {
            logger.warn(
                "Damage-map SMS failed [visit={} to={}]: {}",
                request.visitId, normalized, result.errorMessage
            )
        }
        return ChannelOutcome(result.success, normalized, result.errorMessage)
    }

    private fun defaultEmailBody(request: DamageMapNotificationRequest): String = buildString {
        append(request.customerFirstName?.let { "Dzień dobry, $it," } ?: "Dzień dobry,")
        append("\n\n")
        append("w trakcie prac nad Państwa pojazdem ${request.vehicleLabel} ")
        append("zaktualizowaliśmy mapę uszkodzeń dla wizyty ${request.visitNumber}. ")
        append(describeChange(request))
        // Warunkowo: PDF-a mogło nie udać się wygenerować (patrz
        // UpdateVisitDamageMapHandler — punkty zapisują się nawet wtedy), a wtedy
        // maila nadal wysyłamy i nie może obiecywać załącznika, którego nie ma.
        if (request.pdfBytes != null) {
            append("\n\nAktualny dokument znajduje się w załączniku.")
        } else {
            append("\n\nAktualny dokument pokażemy przy odbiorze pojazdu.")
        }
        append("\n\nW razie pytań prosimy o kontakt.")
    }

    private fun defaultSmsBody(request: DamageMapNotificationRequest): String = buildString {
        append("Wizyta ${request.visitNumber}: zaktualizowalismy mape uszkodzen pojazdu. ")
        append(describeChangeAscii(request))
        // SMS-em załącznika nie wyślemy, a linku do pliku nie mamy (mapa nie ma
        // publicznego adresu), więc jedyna uczciwa obietnica to przekazanie
        // dokumentu przy odbiorze.
        append(" Dokument pokazemy przy odbiorze pojazdu.")
    }

    /**
     * Liczba punktów, nie ich treść: klient ma dowiedzieć się, że dokument się
     * zmienił i o ile, a nie przeczytać w mailu „głęboka rysa na masce" bez obrazka
     * obok.
     */
    private fun describeChange(request: DamageMapNotificationRequest): String {
        val added = request.pointsAfter - request.pointsBefore
        return when {
            added > 0 -> "Na mapie odnotowaliśmy ${added} ${pointWord(added)} więcej niż przy przyjęciu (łącznie ${request.pointsAfter})."
            added < 0 -> "Mapa zawiera teraz ${request.pointsAfter} ${pointWord(request.pointsAfter)}."
            else -> "Zmieniliśmy opis oznaczeń; liczba punktów pozostała bez zmian (${request.pointsAfter})."
        }
    }

    private fun describeChangeAscii(request: DamageMapNotificationRequest): String {
        val added = request.pointsAfter - request.pointsBefore
        return when {
            added > 0 -> "Nowych oznaczen: $added (lacznie ${request.pointsAfter})."
            added < 0 -> "Oznaczen na mapie: ${request.pointsAfter}."
            else -> "Poprawilismy opisy oznaczen."
        }
    }

    private fun pointWord(count: Int): String {
        val last = count % 10
        val lastTwo = count % 100
        return if (last in 2..4 && (lastTwo < 12 || lastTwo > 14)) "punkty" else "punktów"
    }

    private data class ChannelOutcome(val sent: Boolean, val address: String, val error: String?)
}

data class DamageMapNotificationRequest(
    val studioId: StudioId,
    val visitId: VisitId,
    val visitNumber: String,
    val customerId: CustomerId,
    val customerFirstName: String?,
    val recipientEmail: String?,
    val recipientPhone: String?,
    val vehicleLabel: String,
    val pointsBefore: Int,
    val pointsAfter: Int,
    /** Załącznik z aktualną mapą; null, gdy pliku nie udało się wygenerować. */
    val pdfBytes: ByteArray?,
    /** Treść wpisana przez operatora; null = tekst domyślny. */
    val messageBody: String?
)

data class DamageMapNotificationResult(
    val emailSent: Boolean,
    val smsSent: Boolean,
    val message: String
)
