package pl.detailing.crm.employee.leaverequest.domain

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ForbiddenException
import java.util.UUID

/**
 * Kto może rozpatrzyć wniosek urlopowy (projekt §2.6).
 *
 * Adnotacja `@RequiresPermission` na kontrolerze nie wystarcza z dwóch powodów: nie zna
 * zakazu samozatwierdzenia i sprawdza uprawnienie przy WEJŚCIU w żądanie, a nie w chwili
 * decyzji. Dlatego handler decyzji woła [basisFor] ponownie, w środku operacji, a ten sam
 * wynik trafia do szczegółów wniosku jako `canDecide` / `decisionBlockedReason` — przycisk
 * na ekranie i decyzja w backendzie nie mogą mieć dwóch różnych reguł.
 */
@Component
class LeaveApprovalPolicy(private val permissions: PermissionCheckService) {

    companion object {
        const val SELF_DECISION = "Własnego wniosku urlopowego nie można rozpatrzyć"
        const val NO_PERMISSION = "Brak uprawnienia: Akceptacja wniosków urlopowych"
    }

    /**
     * Podstawa, na której [actor] rozpatruje wniosek pracownika o koncie [requestEmployeeUserId].
     * Wyjątek = brak prawa do decyzji.
     */
    fun basisFor(actor: UserPrincipal, requestEmployeeUserId: UUID?): ApprovalBasis {
        // 1. Nikt nie rozpatruje własnego wniosku, także właściciel z rekordem pracownika:
        //    zgoda pracodawcy wydana samemu sobie nie jest niczyją zgodą.
        if (requestEmployeeUserId != null && requestEmployeeUserId == actor.userId.value) {
            throw ForbiddenException(SELF_DECISION)
        }
        // 2. Właściciel studia (główny administrator) rozpatruje każdy cudzy wniosek.
        if (actor.isOwner) return ApprovalBasis.OWNER
        // 3. Pozostali: świeże sprawdzenie uprawnienia, bo rola mogła zostać odebrana
        //    między otwarciem szuflady a kliknięciem „Zatwierdź".
        if (permissions.hasPermission(actor.userId, actor.studioId, Permission.EMPLOYEES_LEAVES_APPROVE)) {
            return ApprovalBasis.PERMISSION
        }
        throw ForbiddenException(NO_PERMISSION)
    }

    /** Powód, dla którego [actor] nie może rozpatrzyć wniosku, albo null, gdy może. */
    fun blockedReason(actor: UserPrincipal, requestEmployeeUserId: UUID?): String? =
        try {
            basisFor(actor, requestEmployeeUserId)
            null
        } catch (e: ForbiddenException) {
            e.message
        }
}
