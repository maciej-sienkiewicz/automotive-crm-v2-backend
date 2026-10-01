package pl.detailing.crm.employee.leaverequest.query

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.ForbiddenException
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
        const val EMPLOYEE_NOT_FOUND = "Nie znaleziono pracownika"
        const val OWN_REQUEST_ON_BEHALF = "Własny wniosek złóż w zakładce Urlop"
    }

    /** Rekord pracownika zalogowanego konta — samoobsługa nie przyjmuje employeeId z żądania. */
    fun employeeOf(studioId: StudioId, userId: UserId): EmployeeEntity =
        employeeRepository.findByStudioIdAndUserId(studioId.value, userId.value)
            ?: throw NotFoundException(NO_EMPLOYEE)

    /**
     * Pracownik, w którego imieniu [actor] wprowadza wniosek: z tego studia (cudzy
     * identyfikator → 404) i nie sam [actor]. Własny wniosek wprowadzony „w imieniu"
     * ominąłby samoobsługę — podpis na urządzeniu studia i od razu decyzja tej samej
     * osoby nad wnioskiem, którego wnioskodawcą jest ona sama.
     */
    fun employeeForOnBehalf(studioId: StudioId, employeeId: UUID, actor: UserId): EmployeeEntity {
        val employee = employeeInStudio(studioId, employeeId)
        if (employee.userId != null && employee.userId == actor.value) throw ForbiddenException(OWN_REQUEST_ON_BEHALF)
        return employee
    }

    /** Pracownik tego studia; cudzy albo nieistniejący identyfikator → 404. */
    fun employeeInStudio(studioId: StudioId, employeeId: UUID): EmployeeEntity =
        employeeRepository.findByIdAndStudioId(employeeId, studioId.value)
            ?: throw NotFoundException(EMPLOYEE_NOT_FOUND)

    /**
     * Własny wniosek pracownika (także szkic). Bez szkicu wprowadzonego w jego imieniu:
     * ten należy do wprowadzającego, dopóki pracownik nie podpisze go osobiście na jego
     * urządzeniu — podpisany z samoobsługi przeczyłby temu, co jest na dokumencie.
     */
    fun ownRequest(studioId: StudioId, employeeId: UUID, requestId: UUID): LeaveRequestEntity =
        leaveRequestRepository.findByIdAndStudioId(requestId, studioId.value)
            ?.takeIf { it.employeeId == employeeId && !it.isOnBehalfDraft() }
            ?: throw NotFoundException(NOT_FOUND)

    /**
     * Wniosek widziany przez rozpatrujących — bez szkiców: dokument bez podpisu
     * pracownika nie jest jeszcze niczym, co można by rozpatrywać.
     */
    fun submittedRequest(studioId: StudioId, requestId: UUID): LeaveRequestEntity =
        leaveRequestRepository.findByIdAndStudioId(requestId, studioId.value)
            ?.takeIf { it.status != LeaveRequestStatus.DRAFT }
            ?: throw NotFoundException(NOT_FOUND)

    /**
     * Wniosek wprowadzony przez [creator] w imieniu pracownika, w dowolnym stanie (stan
     * sprawdza wołający, żeby odpowiedzieć 409, a nie 404, na drugi podpis). Inny
     * administrator dostaje 404: podpis osobisty odbywa się na urządzeniu wprowadzającego
     * i tak jest opisany na karcie podpisów.
     */
    fun onBehalfRequest(studioId: StudioId, requestId: UUID, creator: UserId): LeaveRequestEntity =
        leaveRequestRepository.findByIdAndStudioId(requestId, studioId.value)
            ?.takeIf { it.origin == LeaveRequestOrigin.ON_BEHALF && it.createdBy == creator.value }
            ?: throw NotFoundException(NOT_FOUND)

    /** Szkic ON_BEHALF wprowadzony przez [creator] albo null — dokument H1 dla rozpatrującego. */
    fun onBehalfDraftOrNull(studioId: StudioId, requestId: UUID, creator: UserId): LeaveRequestEntity? =
        leaveRequestRepository.findByIdAndStudioId(requestId, studioId.value)
            ?.takeIf { it.isOnBehalfDraft() && it.createdBy == creator.value }

    private fun LeaveRequestEntity.isOnBehalfDraft() =
        status == LeaveRequestStatus.DRAFT && origin == LeaveRequestOrigin.ON_BEHALF
}
