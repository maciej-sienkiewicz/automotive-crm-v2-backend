package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.analytics.AwaitingWorkService
import pl.detailing.crm.leads.conversation.LeadConversationState
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.conversation.LeadReplyState
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AwaitingWorkServiceTest {

    private val leadRepository = mockk<LeadRepository>()
    private val conversationStates = mockk<LeadConversationStateService>()
    private val service = AwaitingWorkService(leadRepository, conversationStates)

    private val studioId = pl.detailing.crm.shared.StudioId(UUID.randomUUID())

    private fun lead(
        value: Long,
        name: String? = null,
        contact: String = "klient@example.com",
        brand: String? = null,
        model: String? = null
    ): LeadEntity = mockk {
        every { id } returns UUID.randomUUID()
        every { estimatedValue } returns value
        every { customerName } returns name
        every { contactIdentifier } returns contact
        every { vehicleBrand } returns brand
        every { vehicleModel } returns model
    }

    private fun awaitingOurReply(daysAgo: Long) =
        LeadConversationState(
            replyState = LeadReplyState.AWAITING_OUR_REPLY,
            lastInboundAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS),
            lastOutboundAt = null
        )

    private fun awaitingClientReply() =
        LeadConversationState(
            replyState = LeadReplyState.AWAITING_CLIENT_REPLY,
            lastInboundAt = null,
            lastOutboundAt = Instant.now()
        )

    @Test
    fun `nothing awaits when there are no open leads`() {
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns emptyList()

        val result = service.awaitingWork(studioId)

        assertEquals(0, result.count)
        assertEquals(0L, result.value)
        assertNull(result.oldest)
    }

    @Test
    fun `only leads where the ball is on our side are counted`() {
        val ours = lead(value = 100_00, name = "Maciej Sienkiewicz", brand = "Porsche", model = "911")
        val theirs = lead(value = 999_00, name = "Ktoś, kto czeka na naszą decyzję")
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns listOf(ours, theirs)
        every { conversationStates.statesOf(studioId.value, any()) } returns mapOf(
            ours.id to awaitingOurReply(daysAgo = 8),
            theirs.id to awaitingClientReply()
        )

        val result = service.awaitingWork(studioId)

        // Wartość i licznik biorą wyłącznie „naszą" rozmowę — kwota klienta nie liczy się do długu.
        assertEquals(1, result.count)
        assertEquals(100_00L, result.value)
        assertEquals("Maciej Sienkiewicz", result.oldest?.name)
        assertEquals("Porsche 911", result.oldest?.vehicle)
        assertEquals(8, result.oldest?.waitingDays)
    }

    @Test
    fun `oldest is the one waiting the longest and value sums all of ours`() {
        val fresh = lead(value = 300_00, name = "Świeży")
        val stale = lead(value = 1_200_00, name = "Najdłużej czekający")
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns listOf(fresh, stale)
        every { conversationStates.statesOf(studioId.value, any()) } returns mapOf(
            fresh.id to awaitingOurReply(daysAgo = 2),
            stale.id to awaitingOurReply(daysAgo = 11)
        )

        val result = service.awaitingWork(studioId)

        assertEquals(2, result.count)
        assertEquals(1_500_00L, result.value)
        assertEquals("Najdłużej czekający", result.oldest?.name)
        assertEquals(11, result.oldest?.waitingDays)
    }

    @Test
    fun `name falls back to the contact identifier when the customer has no name`() {
        val anon = lead(value = 50_00, name = null, contact = "600100200")
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns listOf(anon)
        every { conversationStates.statesOf(studioId.value, any()) } returns mapOf(
            anon.id to awaitingOurReply(daysAgo = 1)
        )

        val result = service.awaitingWork(studioId)

        assertEquals("600100200", result.oldest?.name)
        assertNull(result.oldest?.vehicle)
    }
}
