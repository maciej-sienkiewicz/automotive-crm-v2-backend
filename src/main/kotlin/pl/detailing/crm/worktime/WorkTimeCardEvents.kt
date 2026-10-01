package pl.detailing.crm.worktime

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.YearMonth

/*
 * Zdarzenia karty czasu pracy, z których powstają powiadomienia push. Publikowane
 * w transakcji, wysyłane po commicie (PushEventBridge, AFTER_COMMIT): powiadomienia
 * nie da się cofnąć, więc nie może wyprzedzić zapisu, który ogłasza.
 */

/** Pracownik złożył kartę — do osób z EMPLOYEES_MANAGE, bez składającego. */
data class WorkTimeCardSubmittedEvent(
    val studioId: StudioId,
    val employeeUserId: UserId,
    val employeeName: String,
    val period: YearMonth
)

/** Decyzja w karcie pracownika — do pracownika. */
data class WorkTimeCardDecidedEvent(
    val studioId: StudioId,
    val employeeUserId: UserId,
    val period: YearMonth,
    val outcome: Outcome,
    /** Notatka zwrotu; null przy zatwierdzeniu. */
    val note: String?,
    val decidedByName: String?
) {
    enum class Outcome { APPROVED, RETURNED }
}

/** Menedżer przypomina o niezłożonej karcie — do pracownika. */
data class WorkTimeCardReminderEvent(
    val studioId: StudioId,
    val employeeUserId: UserId,
    val period: YearMonth
)
