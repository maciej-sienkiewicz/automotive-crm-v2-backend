package pl.detailing.crm.leads

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.delete.BulkDeleteLeadsHandler
import pl.detailing.crm.leads.delete.DeleteLeadHandler
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Usuwanie zbiorcze ma jedną regułę, wokół której wszystko się kręci: jedna sprawa,
 * której nie da się usunąć, NIE wycofuje pozostałych. Zaznaczyłeś dziesięć, jedna ma
 * wizytę — dziewięć znika, a o tej jednej dowiadujesz się wprost.
 */
class BulkDeleteLeadsHandlerTest {

    private val deleteLeadHandler = mockk<DeleteLeadHandler>()
    private val handler = BulkDeleteLeadsHandler(deleteLeadHandler)

    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UUID.randomUUID()

    private fun run(ids: List<UUID>, deleteAppointments: Boolean = false) =
        handler.handle(studioId, ids, userId, "Maciej Sienkiewicz", deleteAppointments)

    @Test
    fun `usuwa wszystkie wskazane sprawy`() {
        val ids = List(3) { UUID.randomUUID() }
        every { deleteLeadHandler.handle(any(), any(), any(), any(), any()) } just Runs

        val result = run(ids)

        assertEquals(3, result.requested)
        assertEquals(3, result.deleted)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `sprawa z wizyta jest pomijana, reszta znika`() {
        val withVisit = UUID.randomUUID()
        val ok1 = UUID.randomUUID()
        val ok2 = UUID.randomUUID()
        every { deleteLeadHandler.handle(any(), any(), any(), any(), any()) } just Runs
        every { deleteLeadHandler.handle(any(), withVisit, any(), any(), any()) } throws
            ConflictException("Ten lead ma już wizytę — usuń najpierw wizytę w module wizyt")

        val result = run(listOf(ok1, withVisit, ok2))

        assertEquals(2, result.deleted)
        assertEquals(1, result.skipped.size)
        assertEquals(withVisit.toString(), result.skipped.first().leadId)
        // Powód jedzie do interfejsu: „nie udało się usunąć 1 sprawy" bez powodu jest
        // komunikatem, z którym nie da się nic zrobić.
        assertTrue(result.skipped.first().reason.contains("wizytę"))
    }

    @Test
    fun `sprawa usunieta w miedzyczasie nie psuje calosci`() {
        val gone = UUID.randomUUID()
        val alive = UUID.randomUUID()
        every { deleteLeadHandler.handle(any(), any(), any(), any(), any()) } just Runs
        every { deleteLeadHandler.handle(any(), gone, any(), any(), any()) } throws
            NotFoundException("Nie znaleziono leada")

        val result = run(listOf(gone, alive))

        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped.size)
    }

    @Test
    fun `ten sam identyfikator dwa razy liczy sie raz`() {
        val id = UUID.randomUUID()
        every { deleteLeadHandler.handle(any(), any(), any(), any(), any()) } just Runs

        val result = run(listOf(id, id, id))

        assertEquals(1, result.requested)
        assertEquals(1, result.deleted)
        verify(exactly = 1) { deleteLeadHandler.handle(any(), id, any(), any(), any()) }
    }

    /** Kolejność kolejki: przy przerwaniu w połowie znika to, co było na górze listy. */
    @Test
    fun `idzie po sprawach w kolejnosci, w ktorej przyszly`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()
        every { deleteLeadHandler.handle(any(), any(), any(), any(), any()) } just Runs

        run(listOf(first, second, third))

        verifyOrder {
            deleteLeadHandler.handle(any(), first, any(), any(), any())
            deleteLeadHandler.handle(any(), second, any(), any(), any())
            deleteLeadHandler.handle(any(), third, any(), any(), any())
        }
    }

    @Test
    fun `decyzja o rezerwacjach dotyczy calej paczki`() {
        val ids = List(2) { UUID.randomUUID() }
        every { deleteLeadHandler.handle(any(), any(), any(), any(), any()) } just Runs

        run(ids, deleteAppointments = true)

        verify(exactly = 2) { deleteLeadHandler.handle(any(), any(), any(), any(), true) }
    }

    @Test
    fun `pusta lista i paczka ponad sufit sa odrzucane`() {
        assertThrows(ValidationException::class.java) { run(emptyList()) }
        assertThrows(ValidationException::class.java) { run(List(101) { UUID.randomUUID() }) }
    }
}
