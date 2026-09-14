package pl.detailing.crm.leads.attachment

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.infrastructure.CommAttachmentMeta
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import java.time.Instant
import java.util.UUID

/**
 * Klient dołącza zdjęcia do zapytania o wycenę — po założeniu leada mają być przy
 * leadzie, a nie tylko w skrzynce.
 */
class LeadAttachmentLinkerTest {

    private val attachmentRepository = mockk<CommAttachmentRepository>()
    private val leadAttachmentRepository = mockk<LeadAttachmentRepository>(relaxed = true)
    private val linker = LeadAttachmentLinker(attachmentRepository, leadAttachmentRepository)

    private val studioId = UUID.randomUUID()
    private val leadId = UUID.randomUUID()
    private val messageId = UUID.randomUUID()
    private val receivedAt: Instant = Instant.parse("2026-09-04T13:23:23Z")

    private val message = mockk<CommMessageEntity>(relaxed = true).also {
        every { it.id } returns messageId
        every { it.studioId } returns studioId
        every { it.sentAt } returns receivedAt
    }

    private fun meta(
        fileName: String,
        isInline: Boolean = false,
        id: UUID = UUID.randomUUID()
    ) = CommAttachmentMeta(
        id = id,
        messageId = messageId,
        fileName = fileName,
        contentType = "image/jpeg",
        contentId = if (isInline) "logo@studio" else null,
        isInline = isInline,
        sizeBytes = 1024
    )

    @Test
    fun `podpina zalaczniki wiadomosci do leada`() {
        every { attachmentRepository.findMetaByMessageIdIn(listOf(messageId)) } returns
            listOf(meta("lakier-przod.jpg"), meta("lakier-tyl.jpg"))
        every { leadAttachmentRepository.existsByLeadIdAndAttachmentId(any(), any()) } returns false

        val saved = mutableListOf<LeadAttachmentEntity>()
        every { leadAttachmentRepository.save(capture(saved)) } answers { firstArg() }

        assertEquals(2, linker.link(leadId, message))
        assertEquals(listOf("lakier-przod.jpg", "lakier-tyl.jpg"), saved.map { it.fileName })
        assertEquals(setOf(leadId), saved.map { it.leadId }.toSet())
        assertEquals(setOf(studioId), saved.map { it.studioId }.toSet())
        // Czas nadejścia wiadomości, nie czas podpięcia — inaczej pliki wyskoczyłyby
        // na końcu osi czasu, poniżej odpowiedzi handlowca.
        assertEquals(setOf(receivedAt), saved.map { it.receivedAt }.toSet())
    }

    @Test
    fun `pomija obrazki osadzone w tresci`() {
        every { attachmentRepository.findMetaByMessageIdIn(listOf(messageId)) } returns
            listOf(meta("logo.png", isInline = true), meta("zdjecie.jpg"))
        every { leadAttachmentRepository.existsByLeadIdAndAttachmentId(any(), any()) } returns false

        val saved = slot<LeadAttachmentEntity>()
        every { leadAttachmentRepository.save(capture(saved)) } answers { firstArg() }

        assertEquals(1, linker.link(leadId, message))
        assertEquals("zdjecie.jpg", saved.captured.fileName)
    }

    @Test
    fun `nie duplikuje juz podpietego zalacznika`() {
        val existing = meta("faktura.pdf")
        every { attachmentRepository.findMetaByMessageIdIn(listOf(messageId)) } returns listOf(existing)
        every { leadAttachmentRepository.existsByLeadIdAndAttachmentId(leadId, existing.id) } returns true

        assertEquals(0, linker.link(leadId, message))
        verify(exactly = 0) { leadAttachmentRepository.save(any()) }
    }

    @Test
    fun `awaria zapisu nie wywraca tworzenia leada`() {
        every { attachmentRepository.findMetaByMessageIdIn(any()) } throws IllegalStateException("baza padła")

        assertEquals(0, linker.link(leadId, message))
    }

    @Test
    fun `wiadomosc bez zalacznikow nic nie zapisuje`() {
        every { attachmentRepository.findMetaByMessageIdIn(listOf(messageId)) } returns emptyList()

        assertEquals(0, linker.link(leadId, message))
        verify(exactly = 0) { leadAttachmentRepository.save(any()) }
    }
}
