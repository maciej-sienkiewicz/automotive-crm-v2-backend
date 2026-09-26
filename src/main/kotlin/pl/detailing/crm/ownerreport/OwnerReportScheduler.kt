package pl.detailing.crm.ownerreport

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.ownerreport.domain.ReportLength
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportNotificationEntity
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportNotificationLog
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportNotificationRepository
import pl.detailing.crm.ownerreport.pdf.ReportFormat
import pl.detailing.crm.push.notify.PushIcon
import pl.detailing.crm.push.notify.PushNotificationType
import pl.detailing.crm.push.notify.PushNotifier
import pl.detailing.crm.push.notify.PushPayload
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.LocalDate

/**
 * Powiadomienie „Dostępny nowy raport" — w dniu, w którym domknął się okres:
 * w poniedziałek za tydzień, w poniedziałek nowego okresu dwutygodniowego,
 * pierwszego dnia miesiąca za miesiąc.
 *
 * Samo powiadomienie, bez generowania PDF: raport składa się na żądanie, gdy ktoś
 * go otworzy. Setki raportów liczonych co poniedziałek „na zapas" obciążałyby bazę
 * za dokumenty, których część nikt nie otworzy.
 *
 * Uruchamia się co godzinę od 7:00 do 12:00, nie raz: aplikacja wstająca po
 * wdrożeniu o 7:05 nie może zgubić powiadomienia. Ponowne uruchomienia nic nie
 * dublują — (użytkownik, okres) wysłany raz ma wpis w [OwnerReportNotificationLog],
 * a wpis wstawia tylko jedna instancja.
 */
@Component
class OwnerReportScheduler(
    private val notificationRepository: OwnerReportNotificationRepository,
    private val notificationLog: OwnerReportNotificationLog,
    private val pushNotifier: PushNotifier,
    @Value("\${crm.owner-report.enabled:true}") private val enabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${crm.owner-report.cron:0 0 7-12 * * *}", zone = "Europe/Warsaw")
    fun notifyNewReports() {
        if (!enabled) return
        val today = LocalDate.now(ReportPeriod.ZONE)
        notificationRepository.findEnabledInActiveStudios()
            .filter { it.frequency.isDueOn(today) }
            .forEach { setting ->
                // Jedna osoba z błędem nie może zatrzymać powiadomień pozostałych.
                runCatching { notify(setting, today) }
                    .onFailure { logger.error("Owner report: powiadomienie dla {} nie powiodło się", setting.userId, it) }
            }
    }

    private fun notify(setting: OwnerReportNotificationEntity, today: LocalDate) {
        val length = setting.frequency.length ?: return
        val period = ReportPeriod.latestFull(length, today)
        if (!notificationLog.claim(setting.userId, setting.studioId, period.from, period.to)) return

        pushNotifier.notifyUser(
            studioId = StudioId(setting.studioId),
            userId = UserId(setting.userId),
            anyOf = REPORT_ACCESS,
            payload = PushPayload(
                type = PushNotificationType.OWNER_REPORT_READY,
                title = "Dostępny nowy raport",
                body = "${label(length)}: ${ReportFormat.range(period.from, period.to)}",
                // Statystyki otwierają okno raportu od razu na tym okresie.
                url = "/statistics?raport=${length.name}&od=${period.from}",
                icon = PushIcon.APP,
                tag = "owner-report-${length.name}"
            )
        )
    }

    private fun label(length: ReportLength): String = when (length) {
        ReportLength.WEEK -> "Tydzień"
        ReportLength.TWO_WEEKS -> "Dwa tygodnie"
        ReportLength.MONTH -> "Miesiąc"
    }

    companion object {
        /** Ten sam dostęp co do raportu w Statystykach (i do kosztów). */
        val REPORT_ACCESS = listOf(Permission.STATISTICS_VIEW, Permission.FINANCE_VIEW_REPORTS)
    }
}
