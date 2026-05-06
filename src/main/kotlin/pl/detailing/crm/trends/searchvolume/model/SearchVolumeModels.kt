package pl.detailing.crm.trends.searchvolume.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ─── DataForSEO API envelope ──────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataForSeoResponse<T>(
    val version: String? = null,
    @JsonProperty("status_code")    val statusCode: Int = 0,
    @JsonProperty("status_message") val statusMessage: String? = null,
    @JsonProperty("tasks_count")    val tasksCount: Int = 0,
    val tasks: List<DataForSeoTask<T>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataForSeoTask<T>(
    val id: String? = null,
    @JsonProperty("status_code")    val statusCode: Int = 0,
    @JsonProperty("status_message") val statusMessage: String? = null,
    val result: List<T>? = null
)

// ─── Poland location registry ─────────────────────────────────────────────────
//
// Source: GET /v3/keywords_data/google_ads/locations
// Filter: country_iso_code = "PL", location_type = "Region"
//

data class VoivodeshipLocation(
    val locationCode: Int,
    val canonicalName: String,
    val polishName: String,
    val aliases: List<String>,
    val locationNameApi: String,
    val parentCountryCode: Int = POLAND_LOCATION_CODE
) {
    companion object {
        const val POLAND_LOCATION_CODE = 2616
    }
}

object PolandLocations {

    val COUNTRY = VoivodeshipLocation(
        locationCode = 2616,
        canonicalName = "Poland",
        polishName = "Polska",
        aliases = listOf("Poland", "Polska", "PL"),
        locationNameApi = "Poland"
    )

    val VOIVODESHIPS: List<VoivodeshipLocation> = listOf(
        VoivodeshipLocation(20862, "Lower Silesian Voivodeship",       "dolnośląskie",        listOf("Lower Silesian", "Dolnośląskie", "dolnoslaskie"),                          "Lower Silesian Voivodeship"),
        VoivodeshipLocation(20863, "Kuyavian-Pomeranian Voivodeship",  "kujawsko-pomorskie",  listOf("Kuyavian-Pomeranian", "Kujawsko-Pomorskie", "kujawsko-pomorskie"),         "Kuyavian-Pomeranian Voivodeship"),
        VoivodeshipLocation(20864, "Lublin Voivodeship",               "lubelskie",           listOf("Lublin", "Lubelskie", "lubelskie"),                                        "Lublin Voivodeship"),
        VoivodeshipLocation(20865, "Lubusz Voivodeship",               "lubuskie",            listOf("Lubusz", "Lubuskie", "lubuskie"),                                          "Lubusz Voivodeship"),
        VoivodeshipLocation(20866, "Łódź Voivodeship",                 "łódzkie",             listOf("Łódź", "Lodz", "Lodzkie", "łódzkie", "Lodz Voivodeship"),                 "Lodz Voivodeship"),
        VoivodeshipLocation(20867, "Lesser Poland Voivodeship",        "małopolskie",         listOf("Lesser Poland", "Małopolskie", "malopolskie", "Małopolska"),               "Lesser Poland Voivodeship"),
        VoivodeshipLocation(20868, "Masovian Voivodeship",             "mazowieckie",         listOf("Masovian", "Mazowieckie", "mazowieckie", "Mazovia"),                       "Masovian Voivodeship"),
        VoivodeshipLocation(20869, "Opole Voivodeship",                "opolskie",            listOf("Opole", "Opolskie", "opolskie"),                                           "Opole Voivodeship"),
        VoivodeshipLocation(20870, "Podkarpackie Voivodeship",         "podkarpackie",        listOf("Podkarpackie", "Subcarpathian"),                                           "Podkarpackie Voivodeship"),
        VoivodeshipLocation(20871, "Podlaskie Voivodeship",            "podlaskie",           listOf("Podlaskie", "Podlasie"),                                                   "Podlaskie Voivodeship"),
        VoivodeshipLocation(20861, "Pomeranian Voivodeship",           "pomorskie",           listOf("Pomeranian", "Pomorskie", "pomorskie"),                                   "Pomeranian Voivodeship"),
        VoivodeshipLocation(20872, "Silesian Voivodeship",             "śląskie",             listOf("Silesian", "Śląskie", "slaskie", "Silesia"),                              "Silesian Voivodeship"),
        VoivodeshipLocation(20873, "Świętokrzyskie Voivodeship",       "świętokrzyskie",      listOf("Świętokrzyskie", "Swietokrzyskie", "Holy Cross"),                         "Swietokrzyskie Voivodeship"),
        VoivodeshipLocation(20874, "Warmian-Masurian Voivodeship",     "warmińsko-mazurskie", listOf("Warmian-Masurian", "Warmińsko-Mazurskie", "warminsko-mazurskie"),          "Warmian-Masurian Voivodeship"),
        VoivodeshipLocation(20875, "Greater Poland Voivodeship",       "wielkopolskie",       listOf("Greater Poland", "Wielkopolskie", "wielkopolskie", "Wielkopolska"),        "Greater Poland Voivodeship"),
        VoivodeshipLocation(20876, "West Pomeranian Voivodeship",      "zachodniopomorskie",  listOf("West Pomeranian", "Zachodniopomorskie", "zachodniopomorskie"),             "West Pomeranian Voivodeship")
    )

    val BY_CODE: Map<Int, VoivodeshipLocation> =
        (VOIVODESHIPS + COUNTRY).associateBy { it.locationCode }

    val ALL_CODES: List<Int> = listOf(COUNTRY.locationCode) + VOIVODESHIPS.map { it.locationCode }

    val VOIVODESHIP_CODES: List<Int> = VOIVODESHIPS.map { it.locationCode }
}

// ─── Google Ads Search Volume — POST /v3/keywords_data/google_ads/search_volume/live ─────
//
// Max 1000 keywords/request. Rate limit: 12 req/min.
// Docs: https://docs.dataforseo.com/v3/keywords_data/google_ads/search_volume/live

data class SearchVolumeRequest(
    @JsonProperty("location_code") val locationCode: Int,
    @JsonProperty("language_code") val languageCode: String = "pl",
    @JsonProperty("keywords")      val keywords: List<String>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchVolumeResultItem(
    val keyword: String? = null,
    @JsonProperty("location_code")        val locationCode: Int? = null,
    @JsonProperty("language_code")        val languageCode: String? = null,
    val competition: String? = null,
    @JsonProperty("competition_index")    val competitionIndex: Int? = null,
    @JsonProperty("search_volume")        val searchVolume: Int? = null,
    @JsonProperty("low_top_of_page_bid")  val lowTopOfPageBid: Double? = null,
    @JsonProperty("high_top_of_page_bid") val highTopOfPageBid: Double? = null,
    val cpc: Double? = null,
    @JsonProperty("monthly_searches")     val monthlySearches: List<MonthlySearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MonthlySearchItem(
    val year: Int? = null,
    val month: Int? = null,
    @JsonProperty("search_volume") val searchVolume: Int? = null
)

// ─── Trends Explore — POST /v3/keywords_data/dataforseo_trends/explore/live ──────────────
//
// Max 5 keywords/request. Returns relative index 0-100 with daily granularity.
// Used to fill the gap from the last Search Volume month to today.
// Docs: https://docs.dataforseo.com/v3/keywords_data/dataforseo_trends/explore/live

data class TrendsExploreRequest(
    @JsonProperty("location_name") val locationName: String = "Poland",
    @JsonProperty("type")          val type: String = "web",
    @JsonProperty("keywords")      val keywords: List<String>,
    @JsonProperty("date_from")     val dateFrom: String,
    @JsonProperty("date_to")       val dateTo: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExploreResult(
    val keywords: List<String>? = null,
    @JsonProperty("location_code") val locationCode: Int? = null,
    val items: List<ExploreItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExploreItem(
    val keywords: List<String>? = null,
    val data: List<ExploreDataPoint>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExploreDataPoint(
    @JsonProperty("date_from") val dateFrom: String? = null,
    @JsonProperty("date_to")   val dateTo: String? = null,
    val values: List<Int?>? = null
)
