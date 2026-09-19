package pl.detailing.crm.instagram.ads.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ads.RawAdLocation
import java.time.LocalDate

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
        linkCaption: String? = null,
        start: LocalDate = LocalDate.of(2026, 3, 1)
    ) = DiscoveredAd(
        adArchiveId = id,
        pageId = pageId,
        pageName = pageName,
        active = active,
        reach = reach,
        locations = listOf(RawAdLocation(name = city, type = "city", excluded = false)),
        linkCaption = linkCaption,
        deliveryStart = start
    )

    private val cities = listOf("Poznań")

    /** Dzień odniesienia dla okna nowości — testy nie mogą zależeć od zegara. */
    private val today = LocalDate.of(2026, 9, 19)

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

    /**
     * Link do przykładowej reklamy składamy z jej identyfikatora, a nie z
     * `ad_snapshot_url` od Meta — tamten niesie token dostępowy instalacji
     * w adresie i trafiłby prosto do przeglądarki klienta.
     */
    @Test
    fun `link do reklamy jest publiczny i nie niesie tokena`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("998877", pageId = "konkurent")), cities, AreaMatchMode.CITIES_ONLY
        )

        assertEquals(
            "https://www.facebook.com/ads/library/?id=998877",
            rows.single().sampleSnapshotUrl
        )
    }

    // ── Nowości: nowa kampania vs nowa firma ─────────────────────────────────

    @Test
    fun `kampania z ostatnich 14 dni liczy sie jako nowa, starsza nie`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "100", start = today.minusDays(3)),
                ad("2", pageId = "100", start = today.minusDays(40))
            ),
            cities, AreaMatchMode.CITIES_ONLY,
            knownSince = mapOf("100" to today.minusDays(200)),
            today = today
        )

        val row = rows.single()
        assertEquals(1, row.newCampaigns)
        assertEquals(today.minusDays(3), row.latestCampaignStart)
        assertFalse(row.newAdvertiser)
    }

    @Test
    fun `granica okna - 13 dni temu nowe, rowno 14 dni temu juz nie`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "100", start = today.minusDays(13)),
                ad("2", pageId = "200", start = today.minusDays(14))
            ),
            cities, AreaMatchMode.CITIES_ONLY, today = today
        )

        assertEquals(1, rows.first { it.pageId == "100" }.newCampaigns)
        assertEquals(0, rows.first { it.pageId == "200" }.newCampaigns)
        assertNull(rows.first { it.pageId == "200" }.latestCampaignStart)
    }

    @Test
    fun `firma znana z rejestru od dawna to nowa kampania, nie nowa firma`() {
        // W cache widać tylko świeżą kampanię — stara się skończyła i zniknęła
        // z cache. Bez rejestru wyglądałoby to jak debiut; rejestr pamięta marzec.
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", start = today.minusDays(2))),
            cities, AreaMatchMode.CITIES_ONLY,
            knownSince = mapOf("100" to LocalDate.of(2026, 3, 1)),
            today = today
        )

        val row = rows.single()
        assertEquals(1, row.newCampaigns)
        assertFalse(row.newAdvertiser)
    }

    @Test
    fun `firma, ktorej najwczesniejszy znany start jest w oknie, to debiutant`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", start = today.minusDays(5))),
            cities, AreaMatchMode.CITIES_ONLY,
            knownSince = mapOf("100" to today.minusDays(5)),
            today = today
        )

        assertTrue(rows.single().newAdvertiser)
    }

    @Test
    fun `bez wpisu w rejestrze debiut ocenia sie po tym, co widac w cache`() {
        val fresh = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", start = today.minusDays(1))),
            cities, AreaMatchMode.CITIES_ONLY, today = today
        ).single()
        val veteran = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "100", start = today.minusDays(1)),
                ad("2", pageId = "100", start = today.minusDays(90))
            ),
            cities, AreaMatchMode.CITIES_ONLY, today = today
        ).single()

        assertTrue(fresh.newAdvertiser)
        assertFalse(veteran.newAdvertiser)
        assertEquals(1, veteran.newCampaigns)
    }

    @Test
    fun `rejestr moze tylko postarzyc firme, nigdy odmlodzic`() {
        // Cache widzi start sprzed 90 dni, rejestr twierdzi „sprzed 2 dni" (np.
        // pobrany tylko pod inną frazą). Bierzemy wcześniejszy — firma nie jest nowa.
        val row = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", start = today.minusDays(90))),
            cities, AreaMatchMode.CITIES_ONLY,
            knownSince = mapOf("100" to today.minusDays(2)),
            today = today
        ).single()

        assertFalse(row.newAdvertiser)
    }

    @Test
    fun `nowosci ida na gore - debiutant przed nowa kampania przed reszta, a w obrebie grupy najwieksi`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(
                ad("1", pageId = "big", pageName = "Big", reach = 9000),
                ad("2", pageId = "big", pageName = "Big", reach = 9000),
                ad("3", pageId = "big", pageName = "Big", reach = 9000),
                ad("4", pageId = "known", pageName = "Known", start = today.minusDays(1)),
                ad("5", pageId = "known", pageName = "Known"),
                ad("6", pageId = "debut", pageName = "Debut", reach = 10, start = today.minusDays(4)),
                ad("7", pageId = "debut2", pageName = "Debut2", reach = 500, start = today.minusDays(6))
            ),
            cities, AreaMatchMode.CITIES_ONLY,
            knownSince = mapOf(
                "big" to LocalDate.of(2025, 1, 1),
                "known" to LocalDate.of(2025, 1, 1),
                "debut" to today.minusDays(4),
                "debut2" to today.minusDays(6)
            ),
            today = today
        )

        // Dwaj debiutanci (większy zasięg pierwszy), potem znana firma z nową
        // kampanią, na końcu największa, ale bez żadnej nowości.
        assertEquals(listOf("debut2", "debut", "known", "big"), rows.map { it.pageId })
    }

    @Test
    fun `wykluczona strona nie liczy sie do nowosci`() {
        val rows = AreaAdvertiserSummary.summarize(
            listOf(ad("1", pageId = "100", start = today.minusDays(1))),
            cities, AreaMatchMode.CITIES_ONLY,
            blockedPageIds = setOf("100"),
            today = today
        )
        assertTrue(rows.isEmpty())
    }
}
