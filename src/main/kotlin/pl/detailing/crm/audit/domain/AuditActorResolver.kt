package pl.detailing.crm.audit.domain

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper

/**
 * Works out who is acting when the emitting code does not already know.
 *
 * Most handlers are given the user in their command and should keep passing it explicitly —
 * that is always accurate. This exists for the shared, low-level services that sit under
 * many flows (sending a message, for instance): they are reached both from a request an
 * employee triggered and from a scheduled job, and threading a user parameter through every
 * caller would be worse than asking here.
 *
 * The authentication is a thread local, so a caller that has already moved onto another
 * dispatcher looks unauthenticated. That resolves to [AuditActor.system], which is the safe
 * direction to be wrong in: an event may be attributed to the system when a person
 * triggered it, never to a person who did not act.
 */
@Component
class AuditActorResolver {

    fun current(fallback: AuditActor = AuditActor.system()): AuditActor = try {
        val principal = SecurityContextHelper.getCurrentUser()
        AuditActor.employee(principal.userId, principal.fullName)
    } catch (e: Exception) {
        fallback
    }
}
