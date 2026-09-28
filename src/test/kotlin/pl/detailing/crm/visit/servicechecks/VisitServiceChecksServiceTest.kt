package pl.detailing.crm.visit.servicechecks

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitServiceItemRepository
import java.util.Optional
import java.util.UUID

/**
 * Lista kontrolna usług na widoku wizyty: tylko znak dla ludzi na hali, z jednym
 * obowiązkiem - kto i kiedy odhaczył albo odznaczył, zostaje w historii wizyty.
 */
class VisitServiceChecksServiceTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val visit = VisitFixtures.visit(
        studioId = studioId,
        items = listOf(
            VisitFixtures.serviceItem().copy(serviceName = "Powłoka ceramiczna"),
            VisitFixtures.serviceItem().copy(serviceName = "Pranie tapicerki")
        )
    )
    private val visitEntity = VisitEntity.fromDomain(visit)
    private val ceramicId = visit.serviceItems[0].id.value
    private val upholsteryId = visit.serviceItems[1].id.value

    private val checks = mutableMapOf<UUID, VisitServiceCheckEntity>()
    private val audits = mutableListOf<LogAuditCommand>()
    private var enabled = true

    private val checkRepository: VisitServiceCheckRepository = mockk {
        every { findById(any()) } answers { Optional.ofNullable(checks[firstArg()]) }
        every { save(any()) } answers { firstArg<VisitServiceCheckEntity>().also { checks[it.serviceItemId] = it } }
        every { delete(any()) } answers { checks.remove(firstArg<VisitServiceCheckEntity>().serviceItemId) }
        every { findByStudioIdAndVisitId(any(), any()) } answers { checks.values.toList() }
    }
    private val visitRepository: VisitRepository = mockk {
        every { findByIdAndStudioId(visit.id.value, studioId.value) } returns visitEntity
        every { findByIdAndStudioId(neq(visit.id.value), any()) } returns null
    }
    private val itemRepository: VisitServiceItemRepository = mockk {
        every { findByVisitId(visit.id.value) } answers { visitEntity.serviceItems.toList() }
        every { findByIdAndVisitId(any(), visit.id.value) } answers {
            visitEntity.serviceItems.firstOrNull { it.id == firstArg<UUID>() }
        }
    }
    private val settingsRepository: StudioSettingsRepository = mockk {
        every { findById(any()) } answers {
            Optional.of(StudioSettingsEntity(studioId = studioId.value).also { it.serviceChecklistEnabled = enabled })
        }
    }
    private val auditService: AuditService = mockk {
        val command = slot<LogAuditCommand>()
        every { logSync(capture(command)) } answers { audits += command.captured }
    }
    private val service = VisitServiceChecksService(checkRepository, visitRepository, itemRepository, settingsRepository, auditService)

    private fun set(itemId: UUID, done: Boolean) = service.set(studioId, visit.id, itemId, done, userId, "Marek z hali")

    @Test
    fun `odhaczenie zapisuje kto i kiedy, a historia wizyty mowi ktora usluga`() {
        val check = set(ceramicId, true)

        assertNotNull(check)
        assertEquals("Marek z hali", check!!.checkedByName)
        val audit = audits.single()
        assertEquals(AuditAction.SERVICE_CHECKED, audit.action)
        assertEquals(visit.id, audit.context.visitId)
        assertEquals("Powłoka ceramiczna", audit.metadata["serviceName"])
    }

    @Test
    fun `odznaczenie usuwa znak i tez trafia do historii`() {
        set(ceramicId, true)

        assertNull(set(ceramicId, false))

        assertEquals(emptyList<ServiceCheck>(), service.list(studioId, visit.id))
        assertEquals(listOf(AuditAction.SERVICE_CHECKED, AuditAction.SERVICE_UNCHECKED), audits.map { it.action })
    }

    @Test
    fun `drugie klikniecie w to samo nie dopisuje drugiego wpisu do historii`() {
        set(ceramicId, true)
        set(ceramicId, true)
        set(upholsteryId, false)

        assertEquals(1, audits.size)
    }

    @Test
    fun `usluga spoza tej wizyty jest odrzucana`() {
        assertThrows<EntityNotFoundException> { set(UUID.randomUUID(), true) }
        verify(exactly = 0) { checkRepository.save(any()) }
    }

    @Test
    fun `przy wylaczonym ustawieniu odhaczac nie mozna`() {
        enabled = false

        assertThrows<ConflictException> { set(ceramicId, true) }
        assertEquals(0, audits.size)
    }

    @Test
    fun `lista pomija odhaczenia uslug usunietych z wizyty`() {
        set(ceramicId, true)
        val removedItem = UUID.randomUUID()
        checks[removedItem] = VisitServiceCheckEntity(removedItem, studioId.value, visit.id.value, java.time.Instant.now(), userId.value, "x")

        assertEquals(listOf(ceramicId), service.list(studioId, visit.id).map { it.serviceItemId })
    }
}
