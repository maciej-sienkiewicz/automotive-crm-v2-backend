package pl.detailing.crm.instagram.ads

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate

/**
 * Odczyt jednej reklamy z odpowiedzi `ads_archive`.
 *
 * Osobno od klienta HTTP, bo to jedyna część integracji, którą da się sprawdzić
 * bez sieci — a odpowiedzi Meta bywają nieregularne w sposób, którego nie da się
 * przewidzieć z dokumentacji. To, co wiemy z prawdziwych odpowiedzi:
 *
 *  - `type` lokalizacji przychodzi raz jako `"countries"` (mnoga, małymi), raz jako
 *    `"CITY"` (pojedyncza, wielkimi). Bez normalizacji ekran pokazywałby „countries".
 *  - w rozbiciu wieku i płci klucze o wartości zero są POMIJANE: `{"age_range":
 *    "18-24", "female": 1}` nie ma w ogóle pola `male`.
 *  - `target_ages` to para liczb `["22","53"]`, a nie przedziały Meta — reklamodawca
 *    ustawia dowolny zakres, nie jeden z siedmiu koszyków raportowania.
 *  - `ad_delivery_start_time` bywa samą datą, bywa pełnym znacznikiem czasu.
 */
internal object MetaAdParser {

    /**
     * Nazwy typów lokalizacji tak, jak zwraca je Meta → nasza jedna postać.
     * Nieznany typ zostaje sobą: lepiej pokazać surową nazwę niż zgadywać.
     */
    private val LOCATION_TYPES = mapOf(
        "countries" to "country",
        "country" to "country",
        "regions" to "region",
        "region" to "region",
        "cities" to "city",
        "city" to "city",
        "zips" to "zip",
        "zip" to "zip",
        "neighborhoods" to "neighborhood",
        "neighborhood" to "neighborhood",
        "places" to "place",
        "place" to "place"
    )

    fun parseAd(node: JsonNode): RawMetaAd? {
        val id = node.path("id").textOrNull() ?: return null
        val pageId = node.path("page_id").textOrNull() ?: return null
        val start = parseDate(node.path("ad_delivery_start_time").textOrNull()) ?: return null

        val payers = node.path("beneficiary_payers").firstOrNull()

        return RawMetaAd(
            adArchiveId = id,
            pageId = pageId,
            pageName = node.path("page_name").textOrNull(),
            title = parseTitle(node),
            deliveryStart = start,
            deliveryStop = parseDate(node.path("ad_delivery_stop_time").textOrNull()),
            reachEu = node.path("eu_total_reach").asIntOrNull(),
            platforms = node.path("publisher_platforms").mapNotNull { it.textOrNull()?.uppercase() },
            targetAges = parseAges(node.path("target_ages")),
            targetGender = node.path("target_gender").textOrNull(),
            targetLocations = parseLocations(node.path("target_locations")),
            payer = payers?.path("payer")?.textOrNull(),
            beneficiary = payers?.path("beneficiary")?.textOrNull(),
            polandBreakdown = parsePolandBreakdown(node.path("age_country_gender_reach_breakdown")),
            snapshotUrl = node.path("ad_snapshot_url").textOrNull()
        )
    }

    /**
     * Nazwa do odróżnienia reklam. Najpierw tytuł kreacji, a gdy go nie ma —
     * pierwsza linia treści: reklamy z Biblioteki bywają bez tytułu, a wiersz
     * kalendarza podpisany „Kampania" nie mówi nic.
     */
    private fun parseTitle(node: JsonNode): String? =
        node.path("ad_creative_link_titles").firstOrNull()?.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?.take(200)
            ?: node.path("ad_creative_bodies").firstOrNull()?.textOrNull()
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotBlank() }
                ?.take(120)

    /** Meta bywa niekonsekwentna: raz `"2026-07-12"`, raz pełny znacznik czasu. */
    fun parseDate(raw: String?): LocalDate? =
        raw?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

    /** `["25","54"]` → `25-54`; pojedyncza wartość zostaje jak jest. */
    fun parseAges(node: JsonNode): String? = when {
        node.isArray && node.size() >= 2 -> "${node[0].textOrNull()}-${node[node.size() - 1].textOrNull()}"
        node.isArray && node.size() == 1 -> node[0].textOrNull()
        else -> node.textOrNull()
    }?.takeIf { it.isNotBlank() && !it.contains("null") }

    fun parseLocations(node: JsonNode): List<RawAdLocation> =
        node.mapNotNull { item ->
            val name = item.path("name").textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawType = item.path("type").textOrNull()?.trim()?.lowercase() ?: "location"
            RawAdLocation(
                name = name,
                type = LOCATION_TYPES[rawType] ?: rawType,
                excluded = item.path("excluded").asBoolean(false)
            )
        }

    /**
     * Z całego rozbicia bierzemy wyłącznie Polskę: właściciel studia w Krakowie
     * nie ma pożytku z tego, ilu Niemców zobaczyło reklamę konkurenta, a wybór
     * kraju na ekranie byłby wyborem, którego nikt nigdy nie zmieni.
     *
     * Brakujące klucze to zera — Meta pomija płeć, której nikt z danego przedziału
     * nie reprezentował.
     */
    fun parsePolandBreakdown(node: JsonNode): List<RawAgeGenderReach> =
        node.filter { it.path("country").textOrNull()?.uppercase() == "PL" }
            .flatMap { country -> country.path("age_gender_breakdowns") }
            .mapNotNull { bucket ->
                val age = bucket.path("age_range").textOrNull() ?: return@mapNotNull null
                RawAgeGenderReach(
                    ageRange = age,
                    male = bucket.path("male").asIntOrNull() ?: 0,
                    female = bucket.path("female").asIntOrNull() ?: 0,
                    unknown = bucket.path("unknown").asIntOrNull() ?: 0
                )
            }

    private fun JsonNode.textOrNull(): String? = if (isTextual || isNumber) asText() else null
    private fun JsonNode.asIntOrNull(): Int? =
        if (isNumber || (isTextual && asText().toIntOrNull() != null)) asInt() else null
    private fun JsonNode.firstOrNull(): JsonNode? = if (isArray && size() > 0) get(0) else null
}
