package pl.detailing.crm.instagram.ads

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Parser na PRAWDZIWYCH odpowiedziach `ads_archive`.
 *
 * Ładunki poniżej to wycinki z realnego wywołania na koncie studia (`page_id`
 * dopisany, bo produkcyjne zapytanie o to pole prosi). Dokumentacja Meta nie
 * opisuje tych nieregularności — wyszły dopiero z odpowiedzi:
 *
 *   • `type` lokalizacji: raz `"countries"`, raz `"CITY"`,
 *   • w rozbiciu wieku i płci brak klucza znaczy zero, a nie brak danych,
 *   • `target_ages` to dowolna para liczb, nie koszyk raportowania Meta,
 *   • wykluczanie miast przy włączonym kraju zdarza się naprawdę.
 */
class MetaAdParserTest {

    private val mapper = ObjectMapper()

    private fun parse(json: String) = MetaAdParser.parseAd(mapper.readTree(json))

    /** Polskie studio detailingowe — reklama lokalna, całe rozbicie w PL. */
    private val localStudioAd = """
    {
      "id": "2539939343114257",
      "page_id": "100064123456789",
      "page_name": "Easy PPF & Detailing",
      "ad_delivery_start_time": "2026-09-07",
      "eu_total_reach": 138,
      "age_country_gender_reach_breakdown": [
        {
          "country": "PL",
          "age_gender_breakdowns": [
            { "age_range": "18-24", "male": 6, "female": 1 },
            { "age_range": "25-34", "male": 33, "female": 6 },
            { "age_range": "35-44", "male": 45, "female": 9 },
            { "age_range": "45-54", "male": 34, "female": 3, "unknown": 1 }
          ]
        }
      ],
      "target_ages": ["22", "53"],
      "target_gender": "All",
      "target_locations": [
        { "name": "Kościan, Polska", "num_obfuscated": 0, "type": "CITY", "excluded": false }
      ],
      "ad_creative_bodies": ["🚗 Jesteś z Kościana lub okolic Poznania? \nChcesz zabezpieczyć lakier?"]
    }
    """.trimIndent()

    @Test
    fun `zasieg w Polsce zgadza sie z suma rozbicia`() {
        val ad = parse(localStudioAd)!!

        assertEquals(138, ad.reachEu)
        // 6+1 + 33+6 + 45+9 + 34+3+1 — dokładnie tyle, ile Meta podała jako eu_total_reach,
        // bo cała emisja poszła w Polsce.
        assertEquals(138, ad.reachPoland)
    }

    @Test
    fun `typ lokalizacji CITY normalizuje sie do naszej postaci`() {
        val location = parse(localStudioAd)!!.targetLocations.single()

        assertEquals("Kościan, Polska", location.name)
        assertEquals("city", location.type)
        assertEquals(false, location.excluded)
    }

    @Test
    fun `zakres wieku to para liczb reklamodawcy, nie koszyk raportowania`() {
        assertEquals("22-53", parse(localStudioAd)!!.targetAges)
    }

    /**
     * Ten sam zakres skonfrontowany z koszykami Meta: 22-53 obejmuje wszystko od
     * 18-24 (bo sięga 22) po 45-54, ale już nie 55-64.
     */
    @Test
    fun `zakres 22-53 obejmuje koszyki od 18-24 do 45-54`() {
        listOf("18-24", "25-34", "35-44", "45-54").forEach { bucket ->
            assertTrue(AdCalendarMath.inTargetAge(bucket, "22-53"), bucket)
        }
        listOf("13-17", "55-64", "65+").forEach { bucket ->
            assertTrue(!AdCalendarMath.inTargetAge(bucket, "22-53"), bucket)
        }
    }

    @Test
    fun `pierwsza linia tresci sluzy za nazwe, gdy reklama nie ma tytulu`() {
        val title = parse(localStudioAd)!!.title

        assertEquals("🚗 Jesteś z Kościana lub okolic Poznania?", title)
    }

    /** Kraj włączony, miasta wykluczone — realne ustawienie polskiego studia. */
    @Test
    fun `wykluczone miasta przy wlaczonym kraju wracaja jako wykluczenia`() {
        val ad = parse(
            """
            {
              "id": "2712906559111035",
              "page_id": "100064000000001",
              "page_name": "ZEN Detailingu",
              "ad_delivery_start_time": "2026-09-07",
              "eu_total_reach": 3,
              "target_ages": ["25", "50"],
              "target_gender": "Men",
              "target_locations": [
                { "name": "Bielsko-Biała, Polska", "num_obfuscated": 1, "type": "CITY", "excluded": true },
                { "name": "Polska", "num_obfuscated": 0, "type": "countries", "excluded": false },
                { "name": "Kraków, Polska", "num_obfuscated": 0, "type": "CITY", "excluded": true }
              ]
            }
            """.trimIndent()
        )!!

        val included = ad.targetLocations.filterNot { it.excluded }
        val excluded = ad.targetLocations.filter { it.excluded }

        assertEquals(listOf("Polska"), included.map { it.name })
        // Kraj przychodzi od Meta jako „countries" — bez normalizacji ekran pokazywałby to słowo.
        assertEquals("country", included.single().type)
        assertEquals(listOf("Bielsko-Biała, Polska", "Kraków, Polska"), excluded.map { it.name })
    }

    /**
     * Reklama ogólnoeuropejska: z całego rozbicia bierzemy sam wiersz PL, a brak
     * klucza `male` w koszyku znaczy zero, nie brak danych.
     */
    @Test
    fun `z rozbicia po krajach zostaje sama Polska, a brak klucza to zero`() {
        val ad = parse(
            """
            {
              "id": "2068168163825860",
              "page_id": "100064000000002",
              "page_name": "RileyRiver Clothing",
              "ad_delivery_start_time": "2026-08-23",
              "eu_total_reach": 177,
              "age_country_gender_reach_breakdown": [
                { "country": "IT", "age_gender_breakdowns": [ { "age_range": "55-64", "male": 4, "female": 10 } ] },
                { "country": "PL", "age_gender_breakdowns": [ { "age_range": "55-64", "female": 1, "unknown": 1 } ] },
                { "country": "DE", "age_gender_breakdowns": [ { "age_range": "65+", "male": 4, "female": 5 } ] }
              ],
              "target_ages": ["18", "65"],
              "target_gender": "Women",
              "target_locations": [
                { "name": "Polska", "num_obfuscated": 0, "type": "countries", "excluded": false }
              ]
            }
            """.trimIndent()
        )!!

        val bucket = ad.polandBreakdown.single()
        assertEquals("55-64", bucket.ageRange)
        assertEquals(0, bucket.male)
        assertEquals(1, bucket.female)
        assertEquals(1, bucket.unknown)
        // 177 kont w całej UE, ale w Polsce tylko dwoje — i to jest liczba, którą pokazujemy.
        assertEquals(177, ad.reachEu)
        assertEquals(2, ad.reachPoland)
    }

    /** Reklama trwająca nie ma daty końca — i to jest jedyny sygnał, że trwa. */
    @Test
    fun `brak daty konca znaczy, ze emisja trwa`() {
        val ad = parse(localStudioAd)!!

        assertEquals(LocalDate.of(2026, 9, 7), ad.deliveryStart)
        assertNull(ad.deliveryStop)
    }

    /** Bez identyfikatora strony nie ma czego przypiąć do obserwowanego profilu. */
    @Test
    fun `reklama bez page_id jest pomijana`() {
        assertNull(parse("""{ "id": "1", "ad_delivery_start_time": "2026-09-07" }"""))
    }
}
