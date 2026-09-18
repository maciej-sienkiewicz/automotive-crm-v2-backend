package pl.detailing.crm.careinstruction

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionRepository
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Słownik instrukcji jest wielonajemcowy i steruje treścią PODPISANEGO dokumentu.
 * Te dwie rzeczy razem znaczą, że cudzy wpis nie ma prawa się tu przecisnąć ani przez
 * przypisanie do usługi, ani przez wykaz do wydruku.
 */
class CareInstructionServiceTest {

    private val repository = mockk<CareInstructionRepository>(relaxed = true)
    private val linkRepository = mockk<ServiceCareInstructionRepository>(relaxed = true)
    private val provisioner = mockk<DefaultCareInstructionProvisioner>(relaxed = true)
    private val service = CareInstructionService(repository, linkRepository, provisioner)

    private val studio = StudioId(UUID.randomUUID())

    private fun instruction(title: String, content: String, order: Int) = CareInstructionEntity(
        id = UUID.randomUUID(),
        studioId = studio.value,
        title = title,
        content = content,
        sortOrder = order
    )

    @Test
    fun `wydruk bierze treści w kolejności słownika, nie w kolejności zaznaczania`() {
        val first = instruction("Mycie", "Dwa wiadra.", 0)
        val second = instruction("Osuszanie", "Mikrofibra.", 1)
        val third = instruction("Chemia", "Neutralne pH.", 2)
        every { repository.findAllByStudio(studio.value) } returns listOf(first, second, third)

        val contents = service.contentsFor(studio, listOf(third.id.toString(), first.id.toString()))

        assertEquals(listOf("Dwa wiadra.", "Neutralne pH."), contents)
    }

    @Test
    fun `cudza instrukcja nie wchodzi na dokument`() {
        val mine = instruction("Mycie", "Dwa wiadra.", 0)
        every { repository.findAllByStudio(studio.value) } returns listOf(mine)

        val contents = service.contentsFor(
            studio,
            listOf(mine.id.toString(), UUID.randomUUID().toString(), "to-nie-jest-uuid")
        )

        assertEquals(listOf("Dwa wiadra."), contents)
    }

    @Test
    fun `przypisanie do usługi odrzuca instrukcje spoza studia`() {
        val mine = instruction("Powłoka", "Nie myj przez 7 dni.", 0)
        val foreign = UUID.randomUUID()
        every { repository.findByIdAndStudioId(mine.id, studio.value) } returns mine
        every { repository.findByIdAndStudioId(foreign, studio.value) } returns null

        val serviceId = UUID.randomUUID()
        val saved = mutableListOf<ServiceCareInstructionEntity>()
        every { linkRepository.save(capture(slot<ServiceCareInstructionEntity>())) } answers {
            firstArg<ServiceCareInstructionEntity>().also { saved += it }
        }

        service.setForService(studio, serviceId, listOf(mine.id.toString(), foreign.toString()))

        assertEquals(1, saved.size)
        assertEquals(mine.id, saved.single().careInstructionId)
        // Podmiana kompletu, nie dokładanie: stare przypisania muszą zniknąć.
        verify { linkRepository.deleteByService(studio.value, serviceId) }
    }

    @Test
    fun `usunięcie instrukcji zabiera jej przypisania`() {
        val mine = instruction("Mycie", "Dwa wiadra.", 0)
        every { repository.findByIdAndStudioId(mine.id, studio.value) } returns mine

        service.delete(studio, mine.id)

        verify { linkRepository.deleteByInstruction(studio.value, mine.id) }
        verify { repository.delete(mine) }
    }

    @Test
    fun `pusta treść nie przechodzi`() {
        val error = assertThrows(ValidationException::class.java) {
            service.create(studio, SaveCareInstructionRequest(title = "Mycie", content = "   "))
        }
        assertTrue(error.message!!.contains("Treść"), "komunikat ma wskazywać pole: ${error.message}")
    }

    @Test
    fun `odczyt słownika zasiewa domyślne wpisy`() {
        every { repository.findAllByStudio(studio.value) } returns emptyList()
        every { linkRepository.findByStudioId(studio.value) } returns emptyList()

        service.list(studio)

        verify { provisioner.ensureDefaults(studio) }
    }
}
