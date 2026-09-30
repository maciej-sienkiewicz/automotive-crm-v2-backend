package pl.detailing.crm.employee.leaverequest

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Wniosek w kształcie kontraktu (`LeaveRequestSummary` / `LeaveRequestDetail`).
 *
 * Nazwiska pracownika i zastępcy są czytane na bieżąco z kadr (zmiana nazwiska po ślubie
 * ma być widoczna w kolejce), a osoba rozpatrująca — z migawki decyzji, bo tak jest
 * wydrukowana na podpisanym dokumencie.
 *
 * `canDecide` / `decisionBlockedReason` / `canCancel` pochodzą z [LeaveApprovalPolicy] —
 * tej samej, którą handler decyzji sprawdza w chwili zapisu. Przycisk na ekranie nie może
 * obiecywać czegoś, czego backend potem odmówi.
 */
@Component
class LeaveRequestPresenter(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val policy: LeaveApprovalPolicy
) {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    fun summaries(studioId: UUID, requests: List<LeaveRequestEntity>): List<LeaveRequestSummaryResponse> {
        if (requests.isEmpty()) return emptyList()
        val names = employeeNames(studioId)
        return requests.map { summary(it, names) }
    }

    fun detail(principal: UserPrincipal, request: LeaveRequestEntity): LeaveRequestDetailResponse {
        val studioId = principal.studioId.value
        val names = employeeNames(studioId)
        val s = summary(request, names)
        val blockedReason = policy.blockedReason(principal, request.employeeUserId)
        val today = LocalDate.now(warsaw)
        return LeaveRequestDetailResponse(
            id = s.id,
            number = s.number,
            employeeId = s.employeeId,
            employeeName = s.employeeName,
            leaveType = s.leaveType,
            onDemand = s.onDemand,
            startDate = s.startDate,
            endDate = s.endDate,
            workingDays = s.workingDays,
            status = s.status,
            reason = s.reason,
            substituteEmployeeId = s.substituteEmployeeId,
            substituteName = s.substituteName,
            createdAt = s.createdAt,
            employeeSignedAt = s.employeeSignedAt,
            decidedAt = s.decidedAt,
            decidedByName = s.decidedByName,
            decisionNote = s.decisionNote,
            cancelReason = s.cancelReason,
            employeeSignatureMethod = request.employeeSignatureMethod?.name,
            decisionSignatureMethod = request.decisionSignatureMethod?.name,
            decidedByBasis = request.decidedByBasis?.name,
            decidedByRoleName = request.decidedByRoleName,
            overlappingAbsences = overlapping(request, names),
            canDecide = request.status == LeaveRequestStatus.PENDING && blockedReason == null,
            decisionBlockedReason = blockedReason,
            canCancel = request.status == LeaveRequestStatus.APPROVED && today.isBefore(request.startDate) &&
                blockedReason == null
        )
    }

    /**
     * Kto inny jest nieobecny w tym terminie: wpisy w employee_leaves (urlopy i L4) oraz
     * wnioski oczekujące. To informacja dla obu stron — pracownik widzi ją przy składaniu,
     * rozpatrujący przy decyzji — a nie blokada.
     */
    private fun overlapping(request: LeaveRequestEntity, names: Map<UUID, String>): List<OverlappingAbsenceResponse> {
        val leaves = employeeLeaveRepository.findOverlappingRange(request.studioId, request.startDate, request.endDate)
            .filter { it.employeeId != request.employeeId }
            .map {
                OverlappingAbsenceResponse(
                    employeeId = it.employeeId.toString(),
                    employeeName = names[it.employeeId] ?: "",
                    startDate = it.startDate.toString(),
                    endDate = it.endDate.toString(),
                    kind = OverlappingAbsenceKind.LEAVE
                )
            }
        val pending = leaveRequestRepository.findPendingOverlapping(request.studioId, request.startDate, request.endDate)
            .filter { it.employeeId != request.employeeId && it.id != request.id }
            .map {
                OverlappingAbsenceResponse(
                    employeeId = it.employeeId.toString(),
                    employeeName = names[it.employeeId] ?: "",
                    startDate = it.startDate.toString(),
                    endDate = it.endDate.toString(),
                    kind = OverlappingAbsenceKind.PENDING_REQUEST
                )
            }
        return (leaves + pending).sortedWith(compareBy({ it.startDate }, { it.employeeName }))
    }

    private fun summary(request: LeaveRequestEntity, names: Map<UUID, String>) = LeaveRequestSummaryResponse(
        id = request.id.toString(),
        number = request.number,
        employeeId = request.employeeId.toString(),
        employeeName = names[request.employeeId] ?: "",
        leaveType = request.leaveType.name,
        onDemand = request.onDemand,
        startDate = request.startDate.toString(),
        endDate = request.endDate.toString(),
        workingDays = request.workingDays,
        status = request.status.name,
        reason = request.reason,
        substituteEmployeeId = request.substituteEmployeeId?.toString(),
        substituteName = request.substituteEmployeeId?.let { names[it] },
        createdAt = request.createdAt.toString(),
        employeeSignedAt = request.employeeSignedAt?.toString(),
        decidedAt = request.decidedAt?.toString(),
        decidedByName = request.decidedByName,
        decisionNote = request.decisionNote,
        cancelReason = request.cancelReason
    )

    private fun employeeNames(studioId: UUID): Map<UUID, String> =
        employeeRepository.findByStudioId(studioId).associate { it.id to "${it.firstName} ${it.lastName}".trim() }
}
