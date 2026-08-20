package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.tags.LeadTagDefinitionEntity
import pl.detailing.crm.leads.tags.LeadTagDefinitionRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Słownik tagów jest edytowalny, więc musi znieść to, co ludzie z nim zrobią:
 * polskie znaki w nazwie, tę samą nazwę dwa razy i nazwę, która wraca po usunięciu.
 */
class LeadTagCatalogServiceTest {

    private val repository = mockk<LeadTagDefinitionRepository>(relaxed = true)
    private val service = LeadTagCatalogService(repository)
    private val studioId = StudioId(UUID.randomUUID())

    private fun definition(code: String, label: String, archived: Instant? = null) =
        LeadTagDefinitionEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            code = code,
            label = label,
            position = 0,
            archivedAt = archived
        )

    private fun existing(vararg entries: LeadTagDefinitionEntity) {
        every { repository.countByStudioId(studioId.value) } returns entries.size.toLong()
        every { repository.findByStudioIdOrderByPositionAscLabelAsc(studioId.value) } returns entries.toList()
    }

    @Test
    fun `kod powstaje z nazwy bez polskich znakow`() {
        existing()
        val saved = slot<LeadTagDefinitionEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.create(studioId, "Mycie i pielęgnacja łodzi")

        assertEquals("MYCIE_I_PIELEGNACJA_LODZI", saved.captured.code)
        assertEquals("Mycie i pielęgnacja łodzi", saved.captured.label)
    }

    @Test
    fun `kolizja kodu dostaje sufiks zamiast nadpisac istniejacy tag`() {
        existing(definition("FOLIA", "Folia"))
        val saved = slot<LeadTagDefinitionEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.create(studioId, "Folia!")

        assertEquals("FOLIA_2", saved.captured.code)
    }

    @Test
    fun `ta sama nazwa drugi raz to blad, a nie drugi tag`() {
        existing(definition("PPF", "Folia PPF"))

        assertThrows(ConflictException::class.java) { service.create(studioId, "folia ppf") }
    }

    @Test
    fun `nazwa wracajaca po usunieciu przywraca ten sam tag`() {
        val archived = definition("PPF", "Folia PPF", archived = Instant.now())
        existing(archived)
        every { repository.save(archived) } returns archived

        val restored = service.create(studioId, "Folia PPF")

        assertNull(restored.archivedAt)
        assertEquals("PPF", restored.code)
    }

    @Test
    fun `walidacja odrzuca kod spoza slownika`() {
        existing(definition("PPF", "Folia PPF"))

        assertThrows(ValidationException::class.java) { service.validate(studioId, listOf("NIE_MA")) }
    }

    @Test
    fun `walidacja odrzuca tag usuniety ze slownika`() {
        existing(definition("PPF", "Folia PPF", archived = Instant.now()))

        assertThrows(ValidationException::class.java) { service.validate(studioId, listOf("PPF")) }
    }

    @Test
    fun `puste studio dostaje zestaw startowy przy pierwszym odczycie`() {
        every { repository.countByStudioId(studioId.value) } returns 0
        val seeded = slot<List<LeadTagDefinitionEntity>>()
        every { repository.saveAll(capture(seeded)) } answers { seeded.captured }
        every { repository.findByStudioIdOrderByPositionAscLabelAsc(studioId.value) } returns emptyList()

        service.listActive(studioId)

        assertTrue(seeded.captured.any { it.code == "CERAMIC_COATING" })
    }
}
