package pl.detailing.crm.visit.arrival

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Przebieg z sekcji „Stan przy przyjęciu" ma być edytowalny: literówka spisana przy
 * ladzie nie może zostać na protokole. Każde pole osobno, z audytem „przed / po".
 */
class UpdateArrivalStateHandlerTest {

    private val visitRepository: VisitRepository = mockk()
    private val auditService: AuditService = mockk { coEvery { log(any()) } returns Unit }
    private val handler = UpdateArrivalStateHandler(visitRepository, auditService)

    private val studioId = StudioId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())

    private fun visit(mileage: Long? = 12_400, keys: Boolean = true, documents: Boolean = false): VisitEntity =
        mockk<VisitEntity>(relaxed = true).also {
            every { it.mileageAtArrival } returns mileage
            every { it.keysHandedOver } returns keys
            every { it.documentsHandedOver } returns documents
            every { it.visitNumber } returns "WIZ/2026/09/001"
            every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns it
            every { visitRepository.save(it) } returns it
        }

    private fun command(mileage: Long? = null, keys: Boolean? = null, documents: Boolean? = null) =
        UpdateArrivalStateCommand(visitId, studioId, userId, "Anna Kowalska", mileage, keys, documents)

    @Test
    fun `poprawia sam przebieg i zostawia slad w audycie z wartoscia przed i po`() = runBlocking {
        val entity = visit(mileage = 12_400)

        handler.handle(command(mileage = 124_000))

        io.mockk.verify { entity.mileageAtArrival = 124_000 }
        io.mockk.verify(exactly = 0) { entity.keysHandedOver = any() }
        val audit = slot<LogAuditCommand>()
        coVerify { auditService.log(capture(audit)) }
        val change = audit.captured.changes.single()
        assertEquals("mileageAtArrival", change.field)
        assertEquals("12400", change.oldValue)
        assertEquals("124000", change.newValue)
    }

    @Test
    fun `pominiete pola zostaja bez zmian - null znaczy nie ruszaj`() = runBlocking {
        val entity = visit(keys = true, documents = false)

        handler.handle(command(documents = true))

        io.mockk.verify(exactly = 0) { entity.mileageAtArrival = any() }
        io.mockk.verify(exactly = 0) { entity.keysHandedOver = any() }
        io.mockk.verify { entity.documentsHandedOver = true }
    }

    @Test
    fun `ta sama wartosc nie generuje zapisu ani wpisu w audycie`() = runBlocking {
        val entity = visit(mileage = 12_400)

        handler.handle(command(mileage = 12_400))

        io.mockk.verify(exactly = 0) { visitRepository.save(any()) }
        coVerify(exactly = 0) { auditService.log(any()) }
        io.mockk.verify(exactly = 0) { entity.mileageAtArrival = any() }
    }

    @Test
    fun `zadanie bez zadnego pola jest odrzucane`() {
        assertThrows(ValidationException::class.java) { runBlocking { handler.handle(command()) } }
    }

    @Test
    fun `przebieg ujemny albo absurdalnie duzy jest odrzucany przed dotknieciem wizyty`() {
        assertThrows(ValidationException::class.java) { runBlocking { handler.handle(command(mileage = -1)) } }
        assertThrows(ValidationException::class.java) {
            runBlocking { handler.handle(command(mileage = UpdateArrivalStateHandler.MAX_MILEAGE_KM + 1)) }
        }
        io.mockk.verify(exactly = 0) { visitRepository.findByIdAndStudioId(any(), any()) }
    }
}
