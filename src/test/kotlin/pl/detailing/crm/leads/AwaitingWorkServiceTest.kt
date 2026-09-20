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
import pl.detailing.crm.leads.conversation.LeadTurnResolver
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AwaitingWorkServiceTest {

    private val leadRepository = mockk<LeadRepository>()
    private val conversationStates = mockk<LeadConversationStateService>()

    /*
     * Prawdziwy rozstrzygacz „czyjego ruchu", nie atrapa: to on jest teraz jedyną
     * definicją zaległości i test ma sprawdzać właśnie ją. Atrapa potwierdzałaby
     * wyłącznie, że usługa woła metodę — a rozjazd między tą regułą a kolejką jest
     * dokładnie tym, czemu ta zmiana zapobiega.
     */
    private val settingsRepository = mockk<StudioSettingsRepository>(relaxed = true)
    private val service = AwaitingWorkService(
        leadRepository,
        conversationStates,
        LeadTurnResolver(settingsRepository)
    )

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
        // Pola, których potrzebuje reguła „czyjego ruchu": status rozstrzyga, czy
        // sprawa w ogóle czeka, dług i pierwsza reakcja — po czyjej jest stronie.
        every { status } returns LeadStatus.IN_PROGRESS
        every { owedSince } returns null
        every { firstResponseAt } returns null
        every { createdAt } returns Instant.now().minus(30, ChronoUnit.DAYS)
    }

    /** Lead z ręcznie zgłoszonym długiem studia — „klient prosił o ofertę mailem". */
    private fun owedLead(value: Long, name: String, owedDaysAgo: Long): LeadEntity = mockk {
        every { id } returns UUID.randomUUID()
        every { estimatedValue } returns value
        every { customerName } returns name
        every { contactIdentifier } returns "600100200"
        every { vehicleBrand } returns null
        every { vehicleModel } returns null
        every { status } returns LeadStatus.IN_PROGRESS
        every { owedSince } returns Instant.now().minus(owedDaysAgo, ChronoUnit.DAYS)
        every { firstResponseAt } returns Instant.now().minus(owedDaysAgo, ChronoUnit.DAYS)
        every { createdAt } returns Instant.now().minus(owedDaysAgo + 1, ChronoUnit.DAYS)
    }

    private fun awaitingOurReply(daysAgo: Long) =
        LeadConversationState(
            replyState = LeadReplyState.AWAITING_OUR_REPLY,
            lastInboundAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS),
            lastOutboundAt = null
        )

    private fun awaitingClientReply(daysAgo: Long = 0) =
        LeadConversationState(
            replyState = LeadReplyState.AWAITING_CLIENT_REPLY,
            lastInboundAt = null,
            lastOutboundAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
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
    fun `a manually declared debt counts even when we wrote last`() {
        /*
         * Klient zadzwonił i poprosił o ofertę mailem. Odnotowany telefon stemplu-
         * je reakcję studia, więc korespondencja mówi „piłka u klienta" — a klient
         * czeka na coś, czego nie wysłaliśmy. Bez tego przypadku sprawa znikała
         * z rachunku zaległości dokładnie wtedy, gdy zaległością się stawała.
         */
        val owed = owedLead(value = 890_00, name = "Prosił o ofertę", owedDaysAgo = 3)
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns listOf(owed)
        // Ostatni mail poszedł PRZED rozmową telefoniczną, w której padła obietnica —
        // po zgłoszeniu długu nic od nas nie wyszło, więc nie ma czym go spłacić.
        every { conversationStates.statesOf(studioId.value, any()) } returns mapOf(
            owed.id to awaitingClientReply(daysAgo = 5)
        )

        val result = service.awaitingWork(studioId)

        assertEquals(1, result.count)
        assertEquals(890_00L, result.value)
        assertEquals("Prosił o ofertę", result.oldest?.name)
        assertEquals(3, result.oldest?.waitingDays)
    }

    @Test
    fun `a debt is settled by our message sent after it was declared`() {
        // Ten sam lead co wyżej, tyle że oferta już poszła. Dowód spłaty zdejmuje
        // sprawę z zaległości bez pytania użytkownika o drugie kliknięcie —
        // asynchroniczny listener dopiero kasuje pole, a rachunek musi być prawdziwy już teraz.
        val owed = owedLead(value = 890_00, name = "Oferta wysłana", owedDaysAgo = 3)
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns listOf(owed)
        every { conversationStates.statesOf(studioId.value, any()) } returns mapOf(
            owed.id to awaitingClientReply(daysAgo = 1)
        )

        val result = service.awaitingWork(studioId)

        assertEquals(0, result.count)
        assertNull(result.oldest)
    }

    @Test
    fun `a lead with no conversation at all is still ours`() {
        /*
         * Zapytanie z telefonu albo z formularza nie ma wątku, więc stan rozmowy
         * o nim milczy. Wcześniej wypadało z rachunku w całości: w kolejce stało
         * na czerwono, a Tablica mówiła, że nic nie czeka.
         */
        val phone = lead(value = 420_00, name = "Zapytanie z telefonu")
        every { leadRepository.findByStudioIdAndStatusIn(studioId.value, any()) } returns listOf(phone)
        every { conversationStates.statesOf(studioId.value, any()) } returns emptyMap()

        val result = service.awaitingWork(studioId)

        assertEquals(1, result.count)
        assertEquals(420_00L, result.value)
        assertEquals("Zapytanie z telefonu", result.oldest?.name)
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
