package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.VisitStatus

/**
 * Komunikat konfliktu stanu trafia prosto do toasta w warsztacie.
 *
 * Regresja, którą te testy trzymają: pracownik dostawał czerwone
 * „Cannot transition from READY_FOR_PICKUP to READY_FOR_PICKUP. Allowed transitions:
 * [COMPLETED, IN_PROGRESS]" — po angielsku, z nazwami stałych enuma, bez informacji,
 * co ma z tym zrobić. Nazwy enumów mają zostać w logu, a użytkownik ma dostać zdanie.
 */
class VisitStateConflictMessageTest {

    @Test
    fun `komunikat jest po polsku i nie zawiera nazw enumow`() {
        val ex = assertThrows<IllegalStateTransitionException> {
            VisitStateMachine.validateTransition(VisitStatus.READY_FOR_PICKUP, VisitStatus.READY_FOR_PICKUP)
        }

        val message = ex.message!!
        VisitStatus.entries.forEach { status ->
            assertFalse(message.contains(status.name), "komunikat nie może zawierać ${status.name}: $message")
        }
        assertTrue(message.contains("Gotowa do odbioru"), message)
    }

    @Test
    fun `techniczny opis z nazwami enumow zostaje - do logu`() {
        val ex = assertThrows<IllegalStateTransitionException> {
            VisitStateMachine.validateTransition(VisitStatus.READY_FOR_PICKUP, VisitStatus.READY_FOR_PICKUP)
        }

        assertTrue(ex.technicalDetail.contains("READY_FOR_PICKUP"), ex.technicalDetail)
        assertTrue(ex.technicalDetail.contains("COMPLETED"), ex.technicalDetail)
    }

    @Test
    fun `ten sam stan i inny stan to dwa rozne kody - frontend inaczej je pokazuje`() {
        val sameState = assertThrows<IllegalStateTransitionException> {
            VisitStateMachine.validateTransition(VisitStatus.COMPLETED, VisitStatus.COMPLETED)
        }
        val otherState = assertThrows<IllegalStateTransitionException> {
            VisitStateMachine.validateTransition(VisitStatus.COMPLETED, VisitStatus.IN_PROGRESS)
        }

        assertEquals(IllegalStateTransitionException.CODE_ALREADY_IN_STATE, sameState.code)
        assertEquals(IllegalStateTransitionException.CODE_STATE_CONFLICT, otherState.code)
    }

    @Test
    fun `konflikt z innym stanem mowi, co zrobic`() {
        val ex = assertThrows<IllegalStateTransitionException> {
            VisitStateMachine.validateTransition(VisitStatus.COMPLETED, VisitStatus.READY_FOR_PICKUP)
        }

        assertTrue(ex.message!!.contains("Zakończona"), ex.message)
        assertTrue(ex.message!!.contains("Odśwież"), ex.message)
    }

    @Test
    fun `kazdy status ma polska nazwe`() {
        VisitStatus.entries.forEach { status ->
            assertFalse(VisitStateMachine.label(status) == status.name, "brak etykiety dla $status")
        }
    }

    @Test
    fun `blokada edycji uslug tez nie pokazuje nazwy enuma`() {
        val visit = VisitFixtures.visit(status = VisitStatus.COMPLETED)
        val ex = assertThrows<IllegalStateTransitionException> {
            visit.approveService(visit.serviceItems.first().id, pl.detailing.crm.shared.UserId.random())
        }

        assertFalse(ex.message!!.contains("COMPLETED"), ex.message)
        assertTrue(ex.message!!.contains("Zakończona"), ex.message)
    }
}
