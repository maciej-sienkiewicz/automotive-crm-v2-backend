package pl.detailing.crm.ownerreport

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.ownerreport.domain.ReportFrequency
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportDispatchLog
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportSettingsRepository
import pl.detailing.crm.ownerreport.pdf.ReportFormat
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.LocalDate

/**
 * Wysyłka raportu właściciela mailem, w poniedziałek rano, za zamknięty tydzień
 * (albo dwa — patrz [ReportFrequency]).
 *
 * Uruchamia się co godzinę od 7:00 do 12:00, nie raz: aplikacja wstająca po
 * wdrożeniu o 7:05 nie może zgubić raportu na cały tydzień. Ponowne uruchomienia
 * nic nie dublują — okres wysłany raz ma wpis w [OwnerReportDispatchLog], a wpis
 * wstawia tylko jedna instancja.
 *
 * Odbiorcy: aktywni właściciele studia. Pracownik z uprawnieniem do raportów
 * pobiera PDF sam z aplikacji; mail z finansami firmy idzie tylko do właściciela.
 */
@Component
class OwnerReportScheduler(
    private val settingsRepository: OwnerReportSettingsRepository,
    private val dispatchLog: OwnerReportDispatchLog,
    private val reportService: OwnerReportService,
    private val userRepository: UserRepository,
    private val emailProvider: EmailProvider,
    private val rolePreviewGuard: RolePreviewOutboundGuard,
    @Value("\${crm.owner-report.enabled:true}") private val enabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${crm.owner-report.cron:0 0 7-12 * * MON}", zone = "Europe/Warsaw")
    fun sendDueReports() {
        if (!enabled) return
        val today = LocalDate.now(ReportPeriod.ZONE)
        settingsRepository.findEnabledForActiveStudios()
            .filter { it.frequency.isDueOn(today) }
            .forEach { settings ->
                // Jedno studio z błędem (brak logo w S3, zepsuty rekord) nie może
                // zatrzymać raportów pozostałych.
                runCatching { sendFor(StudioId(settings.studioId), settings.frequency, today) }
                    .onFailure { logger.error("Owner report: wysyłka dla studia {} nie powiodła się", settings.studioId, it) }
            }
    }

    private fun sendFor(studioId: StudioId, frequency: ReportFrequency, today: LocalDate) {
        val period = ReportPeriod.lastFullWeeks(frequency.weeks, today)
        val recipients = userRepository.findActiveByStudioId(studioId.value)
            .filter { it.isOwner }
            .map { it.email.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (recipients.isEmpty()) {
            logger.warn("Owner report: studio {} nie ma aktywnego właściciela z adresem e-mail", studioId.value)
            return
        }
        if (!dispatchLog.claim(studioId.value, period.from, period.to)) return

        val delivered = try {
            val file = reportService.generate(studioId, period)
            val subject = "Raport ${if (frequency == ReportFrequency.BIWEEKLY) "dwutygodniowy" else "tygodniowy"}: " +
                ReportFormat.range(period.from, period.to)
            val body = """
                Dzień dobry,

                w załączniku raport z działania studia za okres ${ReportFormat.range(period.from, period.to)}:
                sprzedaż i koszty, wizyty i rezerwacje, komunikacja z klientami oraz marketing.

                Raport za dowolny okres pobierzesz też w aplikacji. Wysyłkę mailem wyłączysz w ustawieniach raportu.
            """.trimIndent()
            val attachment = EmailAttachment(file.fileName, file.bytes, "application/pdf")
            recipients.count { to ->
                // Zapytanie o studia pomija piaskownice, ale bezpiecznik jest ostatnim słowem:
                // z piaskownicy podglądu roli nic nie może wyjść do prawdziwej skrzynki.
                if (rolePreviewGuard.intercepts(studioId.value, SimulatedEffectChannel.EMAIL, to, subject)) {
                    return@count true
                }
                val result = emailProvider.send(to = to, subject = subject, bodyText = body, attachments = listOf(attachment))
                if (!result.success) logger.warn("Owner report: nie wysłano do {}: {}", to, result.errorMessage)
                result.success
            }
        } catch (e: Exception) {
            dispatchLog.release(studioId.value, period.from, period.to)
            throw e
        }

        if (delivered == 0) {
            // Nic nie wyszło — zwalniamy okres, następne uruchomienie spróbuje ponownie.
            dispatchLog.release(studioId.value, period.from, period.to)
            return
        }
        dispatchLog.recordRecipients(studioId.value, period.from, period.to, delivered)
        logger.info("Owner report sent: studio={} period={}..{} recipients={}", studioId.value, period.from, period.to, delivered)
    }
}
