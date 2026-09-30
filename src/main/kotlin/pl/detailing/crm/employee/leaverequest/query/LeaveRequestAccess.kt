package pl.detailing.crm.employee.leaverequest.query

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/**
 * Kto widzi który wniosek. Jedno miejsce, bo każda ścieżka ma tu tę samą odpowiedź
 * i ten sam komunikat — 404, a nie 403, żeby cudzy identyfikator nie zdradzał, czy
 * taki wniosek w ogóle istnieje.
 */
@Component
class LeaveRequestAccess(
    private val employeeRepository: EmployeeRepository,
    private val leaveRequestRepository: LeaveRequestRepository
) {
    companion object {
        const val NO_EMPLOYEE = "Twoje konto nie jest powiązane z pracownikiem"
        const val NOT_FOUND = "Nie znaleziono wniosku urlopowego"
    }

    /** Rekord pracownika zalogowanego konta — samoobsługa nie przyjmuje employeeId z żądania. */
    fun employeeOf(studioId: StudioId, userId: UserId): EmployeeEntity =
        employeeRepository.findByStudioIdAndUserId(studioId.value, userId.value)
            ?: throw NotFoundException(NO_EMPLOYEE)

    /** Własny wniosek pracownika (także szkic). */
    fun ownRequest(studioId: StudioId, employeeId: UUID, requestId: UUID): LeaveRequestEntity =
        leaveRequestRepository.findByIdAndStudioId(requestId, studioId.value)
            ?.takeIf { it.employeeId == employeeId }
            ?: throw NotFoundException(NOT_FOUND)

    /**
     * Wniosek widziany przez rozpatrujących — bez szkiców: dokument bez podpisu
     * pracownika nie jest jeszcze niczym, co można by rozpatrywać.
     */
    fun submittedRequest(studioId: StudioId, requestId: UUID): LeaveRequestEntity =
        leaveRequestRepository.findByIdAndStudioId(requestId, studioId.value)
            ?.takeIf { it.status != LeaveRequestStatus.DRAFT }
            ?: throw NotFoundException(NOT_FOUND)
}
