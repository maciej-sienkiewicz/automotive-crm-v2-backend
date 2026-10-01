package pl.detailing.crm.employee.leaverequest.domain

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.LocalDate
import java.util.UUID

/**
 * Pracownik podpisał i złożył wniosek. Publikowane w transakcji złożenia; powiadomienie
 * do rozpatrujących wychodzi dopiero po commicie (PushEventBridge, AFTER_COMMIT).
 *
 * [employeeUserId] bywa null (wniosek ON_BEHALF dla pracownika bez konta), a
 * [createdByUserId] to administrator, który wniosek wprowadził — ten stoi właśnie przy
 * pracowniku podpisującym na jego urządzeniu, więc „czeka na Twoją decyzję" nie jest dla niego.
 */
data class LeaveRequestSubmittedEvent(
    val studioId: StudioId,
    val requestId: UUID,
    val number: String,
    val employeeUserId: UserId?,
    val createdByUserId: UserId,
    val employeeName: String,
    val kindLabel: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val workingDays: Int
)

/** Wniosek rozpatrzony albo zatwierdzony urlop odwołany — wiadomość do pracownika. */
data class LeaveRequestDecidedEvent(
    val studioId: StudioId,
    val requestId: UUID,
    val employeeUserId: UserId,
    val outcome: LeaveRequestStatus,
    val kindLabel: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val decidedByName: String
)
