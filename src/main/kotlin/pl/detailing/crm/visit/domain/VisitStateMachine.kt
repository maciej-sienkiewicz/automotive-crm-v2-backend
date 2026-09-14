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
     * Nazwa statusu tak, jak widzi ją pracownik warsztatu.
     *
     * Komunikat o konflikcie stanu trafia prosto do toasta, a `READY_FOR_PICKUP`
     * w czerwonej ramce nie mówi nic osobie, która właśnie wydaje auto.
     */
    private val labels = mapOf(
        VisitStatus.DRAFT to "Przyjęcie w toku",
        VisitStatus.IN_PROGRESS to "W realizacji",
        VisitStatus.READY_FOR_PICKUP to "Gotowa do odbioru",
        VisitStatus.COMPLETED to "Zakończona",
        VisitStatus.REJECTED to "Odrzucona",
        VisitStatus.ARCHIVED to "Zarchiwizowana"
    )

    fun label(status: VisitStatus): String = labels[status] ?: status.name

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
            throw IllegalStateTransitionException(from, to)
        }
    }
}

/**
 * Przejście niedozwolone w bieżącym stanie wizyty.
 *
 * Prawie zawsze znaczy jedno: przeglądarka pokazywała nieaktualny status. Ktoś inny
 * (druga osoba, tablet, druga karta) zmienił wizytę w międzyczasie, albo to samo
 * żądanie poszło dwa razy. Dlatego [message] jest zdaniem po polsku z instrukcją, co
 * z tym zrobić — leci wprost do toasta — a techniczny opis z nazwami enumów zostaje
 * w [technicalDetail] i trafia wyłącznie do logów.
 *
 * [code] pozwala frontendowi odróżnić „już jest w tym stanie" (nic się nie stało,
 * cel osiągnięty) od „ktoś zmienił wizytę na coś innego" (trzeba spojrzeć na ekran).
 */
class IllegalStateTransitionException private constructor(
    message: String,
    /** Techniczny opis do logów — nazwy enumów zostają tutaj, nie w komunikacie dla użytkownika. */
    val technicalDetail: String,
    val code: String,
    val from: VisitStatus?,
    val to: VisitStatus?
) : IllegalStateException(message) {

    /** Niedozwolone przejście stanu — komunikat i kod wynikają z pary [from] → [to]. */
    constructor(from: VisitStatus, to: VisitStatus) : this(
        message = describe(from, to),
        technicalDetail =
            "Cannot transition from $from to $to. Allowed transitions: ${VisitStateMachine.getAllowedTransitions(from)}",
        code = if (from == to) CODE_ALREADY_IN_STATE else CODE_STATE_CONFLICT,
        from = from,
        to = to
    )

    /** Operacja sprzeczna ze stanem wizyty, ale nie będąca przejściem (np. edycja usług). */
    constructor(message: String) : this(
        message = message,
        technicalDetail = message,
        code = CODE_STATE_CONFLICT,
        from = null,
        to = null
    )

    companion object {
        const val CODE_ALREADY_IN_STATE = "VISIT_ALREADY_IN_STATE"
        const val CODE_STATE_CONFLICT = "VISIT_STATE_CONFLICT"

        private fun describe(from: VisitStatus, to: VisitStatus): String {
            val current = VisitStateMachine.label(from)
            return if (from == to) {
                "Wizyta ma już status \u201E$current\u201D."
            } else {
                "Wizyta ma teraz status \u201E$current\u201D i tej operacji nie można już na niej wykonać. " +
                    "Odśwież widok, aby zobaczyć aktualny stan wizyty."
            }
        }
    }
}
