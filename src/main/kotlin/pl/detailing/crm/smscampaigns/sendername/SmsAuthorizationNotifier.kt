package pl.detailing.crm.smscampaigns.sendername

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Powiadamia obsługę o nowym upoważnieniu nadawcy SMS.
 *
 * Studio, które podpisało upoważnienie, czeka aż ktoś je przejrzy i włączy nazwę
 * nadawcy — a to jest ruch ręczny po naszej stronie. Bez powiadomienia dokument
 * leżałby w S3 do czasu, aż ktoś sam zajrzy do bazy; z nim wpada tam, gdzie już
 * trafiają zgłoszenia problemów (`support.report.recipient-email`), razem z samym
 * plikiem w załączniku.
 *
 * Wysyłka jest świadomie „best effort": nieudany mail nie może przewrócić
 * podpisania ani wgrania dokumentu — plik jest już zapisany, a brak powiadomienia
 * zostaje w logach.
 */
@Service
class SmsAuthorizationNotifier(
    private val emailProvider: EmailProvider,
    private val studioRepository: StudioRepository,
    @Value("\${support.report.recipient-email}") private val recipientEmail: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.of("Europe/Warsaw"))
    }

    /** Skąd wziął się dokument — to zmienia tylko treść maila, nie sam fakt wysyłki. */
    enum class Source(val label: String) {
        SIGNED_IN_APP("podpisane elektronicznie w aplikacji"),
        UPLOADED("wgrany skan podpisanego dokumentu")
    }

    fun notifyAuthorizationSubmitted(
        principal: UserPrincipal,
        senderName: String?,
        source: Source,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String
    ) {
        val studioName = studioRepository.findByStudioId(principal.studioId.value)?.name ?: "(nieznane studio)"

        val body = buildString {
            appendLine("Nowe upoważnienie nadawcy SMS")
            appendLine("=============================")
            appendLine()
            appendLine("Data: ${TIMESTAMP_FORMATTER.format(Instant.now())}")
            appendLine("Studio: $studioName (id: ${principal.studioId.value})")
            appendLine("Przesłał: ${principal.fullName} (${principal.email})")
            appendLine("Nazwa nadawcy: ${senderName ?: "(brak)"}")
            appendLine("Źródło: ${source.label}")
            appendLine()
            appendLine("Dokument w załączniku. Po weryfikacji u operatora zatwierdź nazwę nadawcy")
            appendLine("dla tego studia — dopóki nie jest zatwierdzona, SMS-y wychodzą z numeru domyślnego.")
        }

        val result = try {
            emailProvider.send(
                to = recipientEmail,
                subject = "[Upoważnienie SMS] $studioName — ${senderName ?: "brak nazwy"}",
                bodyText = body,
                attachments = listOf(
                    EmailAttachment(fileName = fileName, content = fileBytes, contentType = contentType)
                )
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to dispatch SMS authorization email [studioId={}, recipient={}]: {}",
                principal.studioId, recipientEmail, e.message, e
            )
            return
        }

        if (!result.success) {
            logger.warn(
                "Failed to dispatch SMS authorization email [studioId={}, recipient={}, reason={}]",
                principal.studioId, recipientEmail, result.errorMessage
            )
        } else {
            logger.info(
                "SMS authorization email sent [studioId={}, recipient={}, source={}]",
                principal.studioId, recipientEmail, source
            )
        }
    }
}
