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
        reachPl: Int? = 1000,
        city: String = "Poznań",
        snapshotUrl: String? = "https://snap/$id"
    ) = DiscoveredAd(
        adArchiveId = id,
        pageId = pageId,
        pageName = pageName,
        active = active,
        reachPl = reachPl,
        snapshotUrl = snapshotUrl,
        locations = listOf(RawAdLocation(name = city, type = "city", excluded = false))
    )

    private val cities = listOf("Poznań")

    @Test
    fun `reklamy tej samej strony sklejaja sie w jeden wiersz z suma zasiegu`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "100", reachPl = 1000),
                ad("2", pageId = "100", reachPl = 500)
            ),
            cities,
            AreaMatchMode.CITIES_ONLY
        )

        assertEquals(1, rows.size)
        assertEquals(2, rows.first().activeAds)
        assertEquals(1500, rows.first().reachPl)
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
            listOf(ad("1", pageId = "100", reachPl = null)),
            cities,
            AreaMatchMode.CITIES_ONLY
        )
        assertNull(rows.first().reachPl)
    }

    @Test
    fun `link do biblioteki reklam celuje w strone firmy, aktywne, PL`() {
        val rows = AreaAdvertiserSummary.summarize(listOf(ad("1", pageId = "12345")), cities, AreaMatchMode.CITIES_ONLY)
        val url = rows.first().adLibraryUrl
        assertTrue(url.contains("view_all_page_id=12345"))
        assertTrue(url.contains("active_status=active"))
        assertTrue(url.contains("country=PL"))
    }
}
