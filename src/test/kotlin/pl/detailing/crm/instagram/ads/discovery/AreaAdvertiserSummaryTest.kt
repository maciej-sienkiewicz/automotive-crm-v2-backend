package pl.detailing.crm.instagram.ads.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ads.RawAdLocation

/**
 * Złożenie tabeli: reklamy odfiltrowane po rejonie → jeden wiersz na firmę.
 * Sedno to grupowanie po stronie, zliczanie aktywnych reklam i sumowanie zasięgu.
 */
class AreaAdvertiserSummaryTest {

    private fun ad(
        id: String,
        pageId: String,
        pageName: String? = "Firma",
        active: Boolean = true,
        reach: Int? = 1000,
        city: String = "Poznań",
        snapshotUrl: String? = "https://snap/$id",
        linkCaption: String? = null
    ) = DiscoveredAd(
        adArchiveId = id,
        pageId = pageId,
        pageName = pageName,
        active = active,
        reach = reach,
        snapshotUrl = snapshotUrl,
        locations = listOf(RawAdLocation(name = city, type = "city", excluded = false)),
        linkCaption = linkCaption
    )

    private val cities = listOf("Poznań")

    @Test
    fun `reklamy tej samej strony sklejaja sie w jeden wiersz z suma zasiegu`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "100", reach = 1000),
                ad("2", pageId = "100", reach = 500)
            ),
            cities,
            AreaMatchMode.CITIES_ONLY
        )

        assertEquals(1, rows.size)
        assertEquals(2, rows.first().activeAds)
        assertEquals(1500, rows.first().reach)
    }

    @Test
    fun `reklama poza rejonem wypada z tabeli`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", city = "Warszawa")),
            cities,
            AreaMatchMode.CITIES_ONLY
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `reklama nieaktywna nie liczy sie do tabeli`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", active = false)),
            cities,
            AreaMatchMode.CITIES_ONLY
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `wiecej firm - sortowanie po liczbie aktywnych reklam malejaco`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "A"),
                ad("2", pageId = "B"),
                ad("3", pageId = "B"),
                ad("4", pageId = "B")
            ),
            cities,
            AreaMatchMode.CITIES_ONLY
        )
        assertEquals(listOf("B", "A"), rows.map { it.pageId })
        assertEquals(3, rows.first().activeAds)
    }

    @Test
    fun `pusta nazwa strony spada do numeru strony`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", pageName = "  ")),
            cities,
            AreaMatchMode.CITIES_ONLY
        )
        assertEquals("100", rows.first().companyName)
    }

    @Test
    fun `brak danych o zasiegu daje null, a nie zero`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", reach = null)),
            cities,
            AreaMatchMode.CITIES_ONLY
        )
        assertNull(rows.first().reach)
    }

    @Test
    fun `link do biblioteki reklam celuje w strone firmy, aktywne, PL`() {
        val rows = AreaAdvertiserSummary.summarize(listOf(ad("1", pageId = "12345")), cities, AreaMatchMode.CITIES_ONLY)
        val url = rows.first().adLibraryUrl
        assertTrue(url.contains("view_all_page_id=12345"))
        assertTrue(url.contains("active_status=active"))
        assertTrue(url.contains("country=PL"))
    }

    @Test
    fun `domena firmy skladana z adresow wszystkich jej reklam`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "p1", linkCaption = "fb.me"),
                ad("2", pageId = "p1", linkCaption = "folia-samochodowa.pl"),
                ad("3", pageId = "p1", linkCaption = "https://folia-samochodowa.pl/pl/c/Folie")
            ),
            cities,
            AreaMatchMode.CITIES_ONLY
        )

        assertEquals(1, rows.size)
        // fb.me to formularz kontaktowy Meta, nie strona firmy - nie może wygrać.
        assertEquals("folia-samochodowa.pl", rows.single().domain)
        // Nazwę IG dokleja dopiero warstwa odczytu; samo podsumowanie jej nie zna.
        assertNull(rows.single().instagram)
    }

    @Test
    fun `reklamodawca kierujacy tylko na posrednikow nie ma domeny`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "p1", linkCaption = "fb.me"), ad("2", pageId = "p1", linkCaption = "booksy.com")),
            cities,
            AreaMatchMode.CITIES_ONLY
        )

        assertNull(rows.single().domain)
    }

    @Test
    fun `wykluczony reklamodawca nie trafia do tabeli`() {
        val ads = listOf(
            ad("1", pageId = "bot-1"),
            ad("2", pageId = "konkurent"),
            ad("3", pageId = "konkurent")
        )

        val rows = AreaAdvertiserSummary.summarize(
            ads, cities, AreaMatchMode.CITIES_ONLY, blockedPageIds = setOf("bot-1")
        )

        assertEquals(1, rows.size)
        assertEquals("konkurent", rows.single().pageId)
        assertEquals(2, rows.single().activeAds)
    }

    @Test
    fun `bez wykluczen tabela jest pelna`() {
        val ads = listOf(ad("1", pageId = "bot-1"), ad("2", pageId = "konkurent"))
        assertEquals(2, AreaAdvertiserSummary.summarize(ads, cities, AreaMatchMode.CITIES_ONLY).size)
    }
}
