package pl.detailing.crm.visit.domain

import pl.detailing.crm.shared.VisitStatus

/**
 * Visit State Machine - Defines allowed state transitions
 *
 * State Flow:
 * IN_PROGRESS → READY_FOR_PICKUP (when all services are completed)
 * IN_PROGRESS → REJECTED (if visit is rejected)
 * READY_FOR_PICKUP → COMPLETED (when vehicle is handed over to customer)
 * READY_FOR_PICKUP → IN_PROGRESS (if more work is needed)
 * COMPLETED → ARCHIVED (after some time)
 * REJECTED → ARCHIVED (after some time)
 */
object VisitStateMachine {

    private val transitions = mapOf(
        VisitStatus.IN_PROGRESS to setOf(
            VisitStatus.READY_FOR_PICKUP,
            VisitStatus.REJECTED
        ),
        VisitStatus.READY_FOR_PICKUP to setOf(
            VisitStatus.COMPLETED,
            VisitStatus.IN_PROGRESS
        ),
        VisitStatus.COMPLETED to setOf(
            VisitStatus.ARCHIVED
        ),
        VisitStatus.REJECTED to setOf(
            VisitStatus.ARCHIVED
        ),
        VisitStatus.ARCHIVED to emptySet() // Terminal state
    )

    /**
     * Check if transition from current status to target status is allowed
     */
    fun canTransition(from: VisitStatus, to: VisitStatus): Boolean {
        return transitions[from]?.contains(to) ?: false
    }

    /**
     * Get all allowed transitions from current status
     */
    fun getAllowedTransitions(from: VisitStatus): Set<VisitStatus> {
        return transitions[from] ?: emptySet()
    }

    /**
     * Validate transition and throw exception if not allowed
     */
    fun validateTransition(from: VisitStatus, to: VisitStatus) {
        if (!canTransition(from, to)) {
            throw IllegalStateTransitionException(
                "Cannot transition from $from to $to. Allowed transitions: ${getAllowedTransitions(from)}"
            )
        }
    }
}

/**
 * Exception thrown when state transition is not allowed
 */
class IllegalStateTransitionException(message: String) : IllegalStateException(message)
