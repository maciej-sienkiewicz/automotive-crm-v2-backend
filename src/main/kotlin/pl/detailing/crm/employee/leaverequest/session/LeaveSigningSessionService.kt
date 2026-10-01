package pl.detailing.crm.employee.leaverequest.session

import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.shared.ConflictException
import java.util.UUID

/**
 * Wydawanie sesji podpisu: pracownikowi dla szkicu (H1), rozpatrującemu dla wersji
 * podpisanej przez pracownika (H2). Sesja zawsze wskazuje wersję, którą serwer uważa
 * za aktualną — klient nie wybiera, co podpisuje.
 */
@Service
class LeaveSigningSessionService(
    private val access: LeaveRequestAccess,
    private val policy: LeaveApprovalPolicy,
    private val sessions: LeaveSigningSessions
) {
    fun forEmployee(principal: UserPrincipal, requestId: UUID): SigningSession {
        val employee = access.employeeOf(principal.studioId, principal.userId)
        val request = access.ownRequest(principal.studioId, employee.id, requestId)
        if (request.status != LeaveRequestStatus.DRAFT) {
            throw ConflictException("Ten wniosek jest już złożony albo wycofany — nie wymaga podpisu")
        }
        return sessions.issueEmployee(request.id, request.documentSha256)
    }

    /**
     * Sesja podpisu osobistego dla szkicu ON_BEHALF — wyłącznie dla administratora, który
     * go wprowadził (na jego urządzeniu pracownik podpisuje). Ten sam token co
     * w samoobsłudze: szkic ma jednego podpisującego, niezależnie od ścieżki.
     */
    fun forEmployeeInPerson(principal: UserPrincipal, requestId: UUID): SigningSession {
        val request = access.onBehalfRequest(principal.studioId, requestId, principal.userId)
        if (request.status != LeaveRequestStatus.DRAFT) {
            throw ConflictException("Ten wniosek jest już podpisany albo porzucony — nie wymaga podpisu pracownika")
        }
        return sessions.issueEmployee(request.id, request.documentSha256)
    }

    /** Polityka przed statusem, jak przy samej decyzji: własny wniosek to 403 w każdym stanie. */
    fun forDecision(principal: UserPrincipal, requestId: UUID): SigningSession {
        val request = access.submittedRequest(principal.studioId, requestId)
        policy.basisFor(principal, request.employeeUserId)
        if (request.status != LeaveRequestStatus.PENDING) throw DecideLeaveRequestHandler.alreadyDecided(request)
        val sha256 = request.employeeSignedSha256
            ?: throw ConflictException("Wniosek nie ma wersji podpisanej przez pracownika")
        return sessions.issueDecision(request.id, principal.userId, sha256)
    }
}
