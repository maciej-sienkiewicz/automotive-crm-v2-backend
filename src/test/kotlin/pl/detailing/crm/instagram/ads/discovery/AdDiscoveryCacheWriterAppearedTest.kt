package pl.detailing.crm.instagram.ads.discovery

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.instagram.ads.RawMetaAd
import java.time.LocalDate
import java.util.UUID

/**
 * Podmiana cache frazy ogłasza reklamy, których poprzednio nie było - ale tylko wtedy,
 * gdy jest z czym porównać. Pierwsze pobranie frazy zwróciłoby jako „nowe" setki
 * kampanii trwających od miesięcy.
 */
class AdDiscoveryCacheWriterAppearedTest {

    private val phrase = "powłoka ceramiczna"
    private val phraseRepository = mockk<AdDiscoveryPhraseRepository>(relaxed = true)
    private val adRepository = mockk<AdDiscoveryAdRepository>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val writer = AdDiscoveryCacheWriter(phraseRepository, adRepository, mockk(relaxed = true), publisher)

    init {
        every { phraseRepository.save(any<AdDiscoveryPhraseEntity>()) } answers { firstArg() }
        every { adRepository.saveAll(any<List<AdDiscoveryAdEntity>>()) } answers { firstArg() }
    }

    private fun raw(id: String) = RawMetaAd(
        adArchiveId = id, pageId = "p", pageName = "Firma", title = null, body = null, linkDescription = null,
        linkCaption = null, deliveryStart = LocalDate.of(2026, 9, 20), deliveryStop = null, reachEu = null,
        platforms = emptyList(), targetAges = null, targetGender = null, targetLocations = emptyList(),
        payer = null, beneficiary = null, polandBreakdown = emptyList()
    )

    private fun phraseEntity(status: PhraseFetchStatus) =
        AdDiscoveryPhraseEntity(id = UUID.randomUUID(), phrase = phrase, lastStatus = status)

    @Test
    fun `pierwsze pobranie frazy niczego nie oglasza`() {
        every { phraseRepository.findByPhrase(phrase) } returns null
        every { adRepository.findAdArchiveIdsByPhrase(phrase) } returns emptyList()

        writer.replacePhrase(phrase, listOf(raw("a"), raw("b")), truncated = false)

        verify(exactly = 0) { publisher.publishEvent(any<AreaAdsAppearedEvent>()) }
    }

    @Test
    fun `kolejne pobranie oglasza tylko reklamy, ktorych nie bylo`() {
        every { phraseRepository.findByPhrase(phrase) } returns phraseEntity(PhraseFetchStatus.OK)
        every { adRepository.findAdArchiveIdsByPhrase(phrase) } returns listOf("a")
        val event = slot<AreaAdsAppearedEvent>()
        every { publisher.publishEvent(capture(event)) } returns Unit

        writer.replacePhrase(phrase, listOf(raw("a"), raw("b"), raw("b")), truncated = false)

        assertEquals(AreaAdsAppearedEvent(phrase, setOf("b")), event.captured)
    }

    @Test
    fun `pusty poprzedni stan z udanego pobrania to prawdziwe zero - nowe reklamy sa nowe`() {
        every { phraseRepository.findByPhrase(phrase) } returns phraseEntity(PhraseFetchStatus.OK)
        every { adRepository.findAdArchiveIdsByPhrase(phrase) } returns emptyList()
        val event = slot<AreaAdsAppearedEvent>()
        every { publisher.publishEvent(capture(event)) } returns Unit

        writer.replacePhrase(phrase, listOf(raw("a")), truncated = false)

        assertEquals(setOf("a"), event.captured.adArchiveIds)
    }

    @Test
    fun `pusty stan po samych bledach to brak punktu odniesienia`() {
        every { phraseRepository.findByPhrase(phrase) } returns phraseEntity(PhraseFetchStatus.RATE_LIMITED)
        every { adRepository.findAdArchiveIdsByPhrase(phrase) } returns emptyList()

        writer.replacePhrase(phrase, listOf(raw("a")), truncated = false)

        verify(exactly = 0) { publisher.publishEvent(any<AreaAdsAppearedEvent>()) }
    }
}
