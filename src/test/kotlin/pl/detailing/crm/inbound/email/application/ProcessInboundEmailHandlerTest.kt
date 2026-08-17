package pl.detailing.crm.inbound.email.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.RawIncomingEmail
import pl.detailing.crm.mailbox.ingest.IngestMailHandler
import pl.detailing.crm.mailbox.ingest.MailIngestResult
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.util.UUID

/**
 * The handler no longer decides anything about leads — it resolves the studio from the
 * forwarding alias and hands a normalized message to the shared mailbox pipeline. Threading
 * and classification are covered by IngestMailHandlerTest.
 */
class ProcessInboundEmailHandlerTest {

    private val studioRepository = mockk<StudioRepository>()
    private val ingestMailHandler = mockk<IngestMailHandler>()

    private val handler = ProcessInboundEmailHandler(studioRepository, ingestMailHandler)

    private val studioId = UUID.randomUUID()

    private val command = ProcessInboundEmailCommand(
        alias = "info@studio.pl",
        from = "klient@example.com",
        subject = "Zapytanie",
        body = "Dzień dobry, interesuje mnie powłoka ceramiczna."
    )

    @BeforeEach
    fun setUp() {
        every { studioRepository.findByEmailAlias(command.alias) } returns
            mockk<StudioEntity> { every { id } returns studioId }
    }

    @Test
    fun `unknown alias is ignored without reaching the pipeline`() = runBlocking {
        every { studioRepository.findByEmailAlias(any()) } returns null

        val result = handler.handle(command)

        assertTrue(result is ProcessInboundEmailResult.Ignored)
        coVerify(exactly = 0) { ingestMailHandler.handle(any(), any(), any()) }
    }

    @Test
    fun `a created lead is reported back to the webhook`() = runBlocking {
        val leadId = UUID.randomUUID()
        coEvery { ingestMailHandler.handle(any(), any(), any()) } returns MailIngestResult.LeadCreated(leadId)

        val result = handler.handle(command)

        assertEquals(ProcessInboundEmailResult.LeadCreated(leadId.toString()), result)
    }

    @Test
    fun `a reply on an existing conversation is reported as appended`() = runBlocking {
        val leadId = UUID.randomUUID()
        coEvery { ingestMailHandler.handle(any(), any(), any()) } returns MailIngestResult.ReplyAppended(leadId)

        val result = handler.handle(command)

        assertEquals(ProcessInboundEmailResult.ReplyAppended(leadId.toString()), result)
    }

    @Test
    fun `threading headers are forwarded to the pipeline when the worker sends them`() = runBlocking {
        coEvery { ingestMailHandler.handle(any(), any(), any()) } returns MailIngestResult.Stored
        val captured = slot<RawIncomingEmail>()

        handler.handle(
            command.copy(
                messageId = "abc@mail.com",
                inReplyTo = "parent@mail.com",
                references = listOf("root@mail.com", "parent@mail.com")
            )
        )

        coVerify { ingestMailHandler.handle(studioId, null, capture(captured)) }
        assertEquals("abc@mail.com", captured.captured.messageId)
        assertEquals("parent@mail.com", captured.captured.inReplyTo)
        assertEquals(listOf("root@mail.com", "parent@mail.com"), captured.captured.references)
        assertEquals(MailDirection.INBOUND, captured.captured.direction)
    }

    @Test
    fun `sender address and display name are split out of a From header`() = runBlocking {
        coEvery { ingestMailHandler.handle(any(), any(), any()) } returns MailIngestResult.Stored
        val captured = slot<RawIncomingEmail>()

        handler.handle(command.copy(from = "\"Jan Kowalski\" <Jan.Kowalski@Example.COM>"))

        coVerify { ingestMailHandler.handle(any(), any(), capture(captured)) }
        assertEquals("jan.kowalski@example.com", captured.captured.fromEmail)
        assertEquals("Jan Kowalski", captured.captured.fromName)
    }
}
