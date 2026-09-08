package pl.detailing.crm.visit.transitions.markready

import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.email.visitready.SendVisitReadyForPickupEmailHandler
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.smscampaigns.visitready.SendVisitReadyForPickupSmsHandler
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * „Oznacz jako gotowe" na wizycie, która JUŻ jest gotowa do odbioru, nie jest błędem.
 *
 * Zgłoszenie z warsztatu: pracownik widział czerwone „Cannot transition from
 * READY_FOR_PICKUP to READY_FOR_PICKUP" — bo kolega (albo tablet, albo drugie
 * kliknięcie) zdążył sekundę wcześniej. Cel został osiągnięty, więc odpowiadamy 200
 * i stanem bieżącym. Warunek: żadnych efektów ubocznych po raz drugi — klient nie ma
 * dostać drugiego SMS-a „auto gotowe".
 */
class MarkVisitReadyForPickupIdempotencyTest {

    private val visitRepository: VisitRepository = mockk()
    private val auditService: AuditService = mockk(relaxed = true)
    private val emailHandler: SendVisitReadyForPickupEmailHandler = mockk(relaxed = true)
    private val smsHandler: SendVisitReadyForPickupSmsHandler = mockk(relaxed = true)

    private val handler = MarkVisitReadyForPickupHandler(visitRepository, auditService, emailHandler, smsHandler)

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    private fun givenVisitInStatus(status: VisitStatus): VisitId {
        val visit = VisitFixtures.visit(studioId = studioId, status = status)
        val entity = VisitEntity.fromDomain(visit)
        every { visitRepository.findByIdAndStudioId(visit.id.value, studioId.value) } returns entity
        every { visitRepository.save(any()) } answers { firstArg() }
        return visit.id
    }

    private fun command(visitId: VisitId, sms: Boolean = true, email: Boolean = true) =
        MarkVisitReadyForPickupCommand(
            studioId = studioId, userId = userId, visitId = visitId,
            sendSms = sms, sendEmail = email, userName = "Anna Kowalska"
        )

    @Test
    fun `wizyta juz gotowa do odbioru konczy sie sukcesem, nie bledem`() {
        val visitId = givenVisitInStatus(VisitStatus.READY_FOR_PICKUP)

        val result = handler.handle(command(visitId))

        assertTrue(result.alreadyInTargetState)
        assertEquals(VisitStatus.READY_FOR_PICKUP, result.newStatus)
        assertEquals(visitId, result.visitId)
    }

    @Test
    fun `powtorka nie wysyla klientowi drugiego SMS-a ani maila`() {
        val visitId = givenVisitInStatus(VisitStatus.READY_FOR_PICKUP)

        handler.handle(command(visitId, sms = true, email = true))

        coVerify(exactly = 0) { smsHandler.handle(any()) }
        coVerify(exactly = 0) { emailHandler.handle(any()) }
    }

    @Test
    fun `powtorka nie zapisuje wizyty ani drugiego wpisu w audycie`() {
        val visitId = givenVisitInStatus(VisitStatus.READY_FOR_PICKUP)

        handler.handle(command(visitId))

        verify(exactly = 0) { visitRepository.save(any()) }
        verify(exactly = 0) { auditService.logSync(any()) }
    }

    @Test
    fun `pierwsze przejscie z realizacji dziala jak dotad`() {
        val visitId = givenVisitInStatus(VisitStatus.IN_PROGRESS)

        val result = handler.handle(command(visitId, sms = false, email = false))

        assertFalse(result.alreadyInTargetState)
        assertEquals(VisitStatus.READY_FOR_PICKUP, result.newStatus)
        verify(exactly = 1) { visitRepository.save(any()) }
        verify(exactly = 1) { auditService.logSync(any()) }
    }

    @Test
    fun `idempotencja dotyczy tylko stanu docelowego - zamknieta wizyta nadal jest konfliktem`() {
        val visitId = givenVisitInStatus(VisitStatus.COMPLETED)

        val ex = org.junit.jupiter.api.assertThrows<pl.detailing.crm.visit.domain.IllegalStateTransitionException> {
            handler.handle(command(visitId))
        }

        assertEquals(pl.detailing.crm.visit.domain.IllegalStateTransitionException.CODE_STATE_CONFLICT, ex.code)
        coVerify(exactly = 0) { smsHandler.handle(any()) }
    }
}
