package pl.detailing.crm.instagram.ads.discovery

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ads.AdvertiserInstagramResolver
import pl.detailing.crm.instagram.ads.discovery.ig.MetaIgLookupService
import pl.detailing.crm.instagram.ads.MetaAdLibraryClient
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate
import java.util.UUID

/**
 * „Obserwuj" przy reklamodawcy: nazwę profilu ustala SERWER.
 *
 * Przeglądarka wskazuje wiersz (identyfikator strony), a nie nazwę konta —
 * bo po drugiej stronie stoi dopisanie cudzego profilu do listy, którą studio
 * będzie regularnie pobierać. Te testy pilnują trzech rzeczy: że nazwa bierze
 * się z tych samych danych co tabela, że reklamodawcy spoza rejonu i wykluczonego
 * nie da się obserwować, i że brak nazwy jest normalnym wynikiem, a nie zgadywaniem.
 */
class AdvertiserFollowHandleTest {

    private val fetchService = mockk<AdDiscoveryFetchService>(relaxed = true)
    private val phraseRepository = mockk<AdDiscoveryPhraseRepository>(relaxed = true)
    private val adRepository = mockk<AdDiscoveryAdRepository>()
    private val client = mockk<MetaAdLibraryClient>(relaxed = true)
    private val instagramResolver = mockk<AdvertiserInstagramResolver>()
    private val blockService = mockk<AdvertiserBlockService>()
    private val settingsService = mockk<AdAreaSettingsService>()
    private val advertiserRepository = mockk<AdDiscoveryAdvertiserRepository>()
    private val igLookupService = mockk<MetaIgLookupService>()

    private val service = AdDiscoveryReadService(
        fetchService, phraseRepository, adRepository, client, instagramResolver,
        blockService, settingsService, advertiserRepository, igLookupService, 10
    )

    private val studioId = StudioId(UUID.randomUUID())

    private fun ad(pageId: String, caption: String?, city: String = "Poznań") = AdDiscoveryAdEntity(
        id = UUID.randomUUID(),
        phrase = AdDiscoveryPhrase.normalizeValid(AdDiscoveryCatalog.ALL.first().text)!!,
        adArchiveId = UUID.randomUUID().toString(),
        pageId = pageId,
        pageName = "Studio $pageId",
        deliveryStart = LocalDate.now().minusDays(30),
        targetLocations = "$city;city;0",
        linkCaption = caption
    )

    private fun settings(locations: List<String> = listOf("Poznań")) = AreaSettingsDto(
        locations = locations,
        matchMode = AreaMatchMode.CITIES_ONLY,
        excludedPhraseIds = emptyList(),
        trackedPhraseCount = AdDiscoveryCatalog.ALL.size,
        noveltyAckedThrough = null,
        updatedAt = null
    )

    private fun quietSurroundings() {
        every { advertiserRepository.findByPageIdIn(any()) } returns emptyList()
        every { igLookupService.known(any()) } returns emptyMap()
        every { instagramResolver.resolve(any<Collection<String?>>()) } returns emptyMap()
        every { blockService.blockedPageIds(studioId) } returns emptySet()
    }

    @Test
    fun `nazwa bierze sie z podpisu reklamy tego samego wiersza`() {
        quietSurroundings()
        every { settingsService.get(studioId) } returns settings()
        every { adRepository.findByPhraseIn(any()) } returns listOf(
            ad("100", "instagram.com/premium.detail"),
            ad("100", "instagram.com/premium.detail")
        )

        assertEquals("premium.detail", service.instagramHandleFor(studioId, "100"))
    }

    /**
     * Reklamodawca wykluczony przez studio nie jest w jego tabeli, więc nie ma też
     * przycisku „Obserwuj" — a skoro nie ma, to żądanie z takim identyfikatorem nie
     * przyszło z ekranu i nie ma prawa nic dodać.
     */
    @Test
    fun `wykluczonego reklamodawcy nie da sie obserwowac`() {
        quietSurroundings()
        every { blockService.blockedPageIds(studioId) } returns setOf("100")
        every { settingsService.get(studioId) } returns settings()
        every { adRepository.findByPhraseIn(any()) } returns listOf(ad("100", "instagram.com/premium.detail"))

        assertNull(service.instagramHandleFor(studioId, "100"))
    }

    @Test
    fun `reklamodawca spoza rejonu studia nie istnieje dla tego wywolania`() {
        quietSurroundings()
        every { settingsService.get(studioId) } returns settings(locations = listOf("Kraków"))
        every { adRepository.findByPhraseIn(any()) } returns listOf(
            ad("100", "instagram.com/premium.detail", city = "Poznań")
        )

        assertNull(service.instagramHandleFor(studioId, "100"))
    }

    @Test
    fun `identyfikator strony, ktorej nie ma w wynikach, nie daje nazwy`() {
        quietSurroundings()
        every { settingsService.get(studioId) } returns settings()
        every { adRepository.findByPhraseIn(any()) } returns listOf(ad("100", "instagram.com/premium.detail"))

        assertNull(service.instagramHandleFor(studioId, "999"))
    }

    /**
     * Reklama kierująca na `instagram.com/p/…` niesie identyfikator posta, nie konta.
     * Brak nazwy jest tu poprawnym wynikiem — lepiej nie dodać nic niż profil „p".
     */
    @Test
    fun `adres posta nie jest nazwa profilu`() {
        quietSurroundings()
        every { settingsService.get(studioId) } returns settings()
        every { adRepository.findByPhraseIn(any()) } returns listOf(ad("100", "instagram.com/p/C3xYz"))

        assertNull(service.instagramHandleFor(studioId, "100"))
    }

    @Test
    fun `studio bez wskazanego rejonu nie ma czego obserwowac`() {
        quietSurroundings()
        every { settingsService.get(studioId) } returns settings(locations = emptyList())

        assertNull(service.instagramHandleFor(studioId, "100"))
    }
}
