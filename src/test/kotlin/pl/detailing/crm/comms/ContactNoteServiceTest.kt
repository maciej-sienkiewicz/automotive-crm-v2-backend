package pl.detailing.crm.comms

import io.mockk.every
import io.mockk.mockk
import io.mockk.CapturingSlot
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.notes.ContactNoteEntity
import pl.detailing.crm.comms.notes.ContactNoteEventEntity
import pl.detailing.crm.comms.notes.ContactNoteEventRepository
import pl.detailing.crm.comms.notes.ContactNoteRepository
import pl.detailing.crm.comms.notes.ContactNoteService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Notatka bez śladu, kto ją zmienił, nie odpowiada na pytanie, dla którego powstała.
 * Każda operacja musi więc dopisać wpis do dziennika — i to w tej samej transakcji.
 */
class ContactNoteServiceTest {

    private val noteRepository = mockk<ContactNoteRepository>(relaxed = true)
    private val eventRepository = mockk<ContactNoteEventRepository>(relaxed = true)
    private val service = ContactNoteService(noteRepository, eventRepository)

    private val studioId = StudioId(UUID.randomUUID())
    private val actorId = UUID.randomUUID()

    private fun capturingSave(): CapturingSlot<ContactNoteEntity> = slot<ContactNoteEntity>().also { saved ->
        every { noteRepository.save(capture(saved)) } answers { saved.captured }
    }

    private fun capturingEvent(): CapturingSlot<ContactNoteEventEntity> = slot<ContactNoteEventEntity>().also { saved ->
        every { eventRepository.save(capture(saved)) } answers { saved.captured }
    }

    @Test
    fun `adres normalizujemy, zeby notatka nie rozjechala sie po wielkosci liter`() {
        val saved = capturingSave()
        capturingEvent()

        service.create(studioId, "  Klient@Example.COM ", "Dzwoni po 16", actorId, "Anna Nowak")

        assertEquals("klient@example.com", saved.captured.contactEmail)
    }

    @Test
    fun `dodanie notatki zapisuje wpis CREATED z trescia`() {
        capturingSave()
        val event = capturingEvent()

        service.create(studioId, "klient@example.com", "Jeździ dwoma autami", actorId, "Anna Nowak")

        assertEquals("CREATED", event.captured.action)
        assertEquals("Jeździ dwoma autami", event.captured.bodyAfter)
        assertEquals("Anna Nowak", event.captured.actorName)
    }

    @Test
    fun `edycja zapisuje stan przed i po`() {
        val note = ContactNoteEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            contactEmail = "klient@example.com",
            body = "Stara treść",
            createdById = actorId,
            createdByName = "Anna Nowak"
        )
        every { noteRepository.findByIdAndStudioId(note.id, studioId.value) } returns note
        every { noteRepository.save(note) } returns note
        val event = capturingEvent()

        service.update(studioId, note.id, "Nowa treść", actorId, "Piotr Kowal")

        assertEquals("UPDATED", event.captured.action)
        assertEquals("Stara treść", event.captured.bodyBefore)
        assertEquals("Nowa treść", event.captured.bodyAfter)
    }

    @Test
    fun `usuniecie jest miekkie i zostawia slad`() {
        val note = ContactNoteEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            contactEmail = "klient@example.com",
            body = "Do skasowania",
            createdById = actorId,
            createdByName = "Anna Nowak"
        )
        every { noteRepository.findByIdAndStudioId(note.id, studioId.value) } returns note
        every { noteRepository.save(note) } returns note
        val event = capturingEvent()

        service.delete(studioId, note.id, actorId, "Piotr Kowal")

        assertNotNull(note.deletedAt)
        assertEquals("DELETED", event.captured.action)
        assertEquals("Do skasowania", event.captured.bodyBefore)
    }

    @Test
    fun `pusta notatka nie przechodzi`() {
        assertThrows(ValidationException::class.java) {
            service.create(studioId, "klient@example.com", "   ", actorId, "Anna Nowak")
        }
    }
}
