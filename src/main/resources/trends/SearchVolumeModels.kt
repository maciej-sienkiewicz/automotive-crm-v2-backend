package com.example.demo.trends.searchvolume.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ═══════════════════════════════════════════════════════════════════════
// SHARED API RESPONSE WRAPPERS
// ═══════════════════════════════════════════════════════════════════════

/** Top-level DataForSEO API response envelope (shared by all endpoints). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DataForSeoResponse<T>(
    val version: String? = null,
    @JsonProperty("status_code") val statusCode: Int = 0,
    @JsonProperty("status_message") val statusMessage: String? = null,
    @JsonProperty("tasks_count") val tasksCount: Int = 0,
    val tasks: List<DataForSeoTask<T>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataForSeoTask<T>(
    val id: String? = null,
    @JsonProperty("status_code") val statusCode: Int = 0,
    @JsonProperty("status_message") val statusMessage: String? = null,
    val result: List<T>? = null
)

// ═══════════════════════════════════════════════════════════════════════
// VOIVODESHIP LOCATION MAPPING
// ═══════════════════════════════════════════════════════════════════════
//
// DataForSEO Google Ads Locations endpoint:
//   GET https://api.dataforseo.com/v3/keywords_data/google_ads/locations
//   Docs: https://docs.dataforseo.com/v3/keywords_data/google_ads/locations
//
// Poland country: location_code = 2616
// Voivodeships are returned as location_type = "Region" with country_iso_code = "PL"
//
// The location_code values below were resolved from the DataForSEO
// locations list filtered by country_iso_code=PL, location_type=Region.
// ═══════════════════════════════════════════════════════════════════════

/**
 * A single Google Ads geotargeting location as returned by
 * GET /v3/keywords_data/google_ads/locations.
 *
 * @see <a href="https://docs.dataforseo.com/v3/keywords_data/google_ads/locations">Locations endpoint</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleAdsLocation(
    @JsonProperty("location_code")    val locationCode: Int,
    @JsonProperty("location_name")    val locationName: String? = null,
    @JsonProperty("location_code_parent") val locationCodeParent: Int? = null,
    @JsonProperty("country_iso_code") val countryIsoCode: String? = null,
    @JsonProperty("location_type")    val locationType: String? = null
)

/**
 * Hardcoded mapping of all 16 Polish voivodeships with their Google Ads location_code.
 *
 * Resolution method:
 *   1. GET /v3/keywords_data/google_ads/locations
 *   2. Filter: country_iso_code = "PL" AND location_type = "Region"
 *   3. Match location_name to canonical voivodeship name
 *
 * Each entry stores:
 *   - locationCode: Google Ads geotargeting code (primary key for requests)
 *   - canonicalName: English canonical name
 *   - polishName: Polish canonical name
 *   - aliases: alternative names that may appear in responses
 *   - locationNameApi: exact location_name string from the DataForSEO API
 *   - parentCountryCode: always 2616 (Poland)
 */
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
        const val POLAND_ISO = "PL"
    }
}

/**
 * Static registry of all 16 Polish voivodeships with their Google Ads location codes.
 *
 * Source: GET /v3/keywords_data/google_ads/locations, filtered by:
 *   - country_iso_code = "PL"
 *   - location_type = "Region"
 */
object PolandLocations {

    /** Poland country-level location. */
    val COUNTRY = VoivodeshipLocation(
        locationCode = 2616,
        canonicalName = "Poland",
        polishName = "Polska",
        aliases = listOf("Poland", "Polska", "PL"),
        locationNameApi = "Poland"
    )

    /** All 16 voivodeships. */
    val VOIVODESHIPS: List<VoivodeshipLocation> = listOf(
        VoivodeshipLocation(
            locationCode = 20862,
            canonicalName = "Lower Silesian Voivodeship",
            polishName = "dolnośląskie",
            aliases = listOf("Lower Silesian", "Dolnośląskie", "dolnoslaskie"),
            locationNameApi = "Lower Silesian Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20863,
            canonicalName = "Kuyavian-Pomeranian Voivodeship",
            polishName = "kujawsko-pomorskie",
            aliases = listOf("Kuyavian-Pomeranian", "Kujawsko-Pomorskie", "kujawsko-pomorskie"),
            locationNameApi = "Kuyavian-Pomeranian Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20864,
            canonicalName = "Lublin Voivodeship",
            polishName = "lubelskie",
            aliases = listOf("Lublin", "Lubelskie", "lubelskie"),
            locationNameApi = "Lublin Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20865,
            canonicalName = "Lubusz Voivodeship",
            polishName = "lubuskie",
            aliases = listOf("Lubusz", "Lubuskie", "lubuskie"),
            locationNameApi = "Lubusz Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20866,
            canonicalName = "Łódź Voivodeship",
            polishName = "łódzkie",
            aliases = listOf("Łódź", "Lodz", "Lodzkie", "łódzkie", "Lodz Voivodeship"),
            locationNameApi = "Lodz Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20867,
            canonicalName = "Lesser Poland Voivodeship",
            polishName = "małopolskie",
            aliases = listOf("Lesser Poland", "Małopolskie", "malopolskie", "Małopolska"),
            locationNameApi = "Lesser Poland Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20868,
            canonicalName = "Masovian Voivodeship",
            polishName = "mazowieckie",
            aliases = listOf("Masovian", "Mazowieckie", "mazowieckie", "Mazovia"),
            locationNameApi = "Masovian Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20869,
            canonicalName = "Opole Voivodeship",
            polishName = "opolskie",
            aliases = listOf("Opole", "Opolskie", "opolskie"),
            locationNameApi = "Opole Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20870,
            canonicalName = "Podkarpackie Voivodeship",
            polishName = "podkarpackie",
            aliases = listOf("Podkarpackie", "Subcarpathian"),
            locationNameApi = "Podkarpackie Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20871,
            canonicalName = "Podlaskie Voivodeship",
            polishName = "podlaskie",
            aliases = listOf("Podlaskie", "Podlasie"),
            locationNameApi = "Podlaskie Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20861,
            canonicalName = "Pomeranian Voivodeship",
            polishName = "pomorskie",
            aliases = listOf("Pomeranian", "Pomorskie", "pomorskie"),
            locationNameApi = "Pomeranian Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20872,
            canonicalName = "Silesian Voivodeship",
            polishName = "śląskie",
            aliases = listOf("Silesian", "Śląskie", "slaskie", "Silesia"),
            locationNameApi = "Silesian Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20873,
            canonicalName = "Świętokrzyskie Voivodeship",
            polishName = "świętokrzyskie",
            aliases = listOf("Świętokrzyskie", "Swietokrzyskie", "Holy Cross"),
            locationNameApi = "Swietokrzyskie Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20874,
            canonicalName = "Warmian-Masurian Voivodeship",
            polishName = "warmińsko-mazurskie",
            aliases = listOf("Warmian-Masurian", "Warmińsko-Mazurskie", "warminsko-mazurskie"),
            locationNameApi = "Warmian-Masurian Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20875,
            canonicalName = "Greater Poland Voivodeship",
            polishName = "wielkopolskie",
            aliases = listOf("Greater Poland", "Wielkopolskie", "wielkopolskie", "Wielkopolska"),
            locationNameApi = "Greater Poland Voivodeship"
        ),
        VoivodeshipLocation(
            locationCode = 20876,
            canonicalName = "West Pomeranian Voivodeship",
            polishName = "zachodniopomorskie",
            aliases = listOf("West Pomeranian", "Zachodniopomorskie", "zachodniopomorskie"),
            locationNameApi = "West Pomeranian Voivodeship"
        )
    )

    /** Lookup by location_code. */
    val BY_CODE: Map<Int, VoivodeshipLocation> =
        (VOIVODESHIPS + COUNTRY).associateBy { it.locationCode }

    /** All location codes including country. */
    val ALL_CODES: List<Int> = listOf(COUNTRY.locationCode) + VOIVODESHIPS.map { it.locationCode }

    /** Only voivodeship codes. */
    val VOIVODESHIP_CODES: List<Int> = VOIVODESHIPS.map { it.locationCode }
}

// ═══════════════════════════════════════════════════════════════════════
// GOOGLE ADS SEARCH VOLUME — API REQUEST / RESPONSE
// ═══════════════════════════════════════════════════════════════════════
//
// POST https://api.dataforseo.com/v3/keywords_data/google_ads/search_volume/live
// Docs: https://docs.dataforseo.com/v3/keywords_data/google_ads/search_volume/live
//
// IMPORTANT constraints:
//   - Max 1000 keywords per request
//   - Exactly ONE location selector: location_code OR location_name OR location_coordinate
//   - Rate limit: max 12 requests/minute per account for Live endpoints
// ═══════════════════════════════════════════════════════════════════════

/**
 * Request body for POST /v3/keywords_data/google_ads/search_volume/live.
 *
 * Uses location_code as the single location selector (never location_name or location_coordinate).
 */
data class SearchVolumeRequest(
    @JsonProperty("location_code")  val locationCode: Int,
    @JsonProperty("language_code")  val languageCode: String = "pl",
    @JsonProperty("keywords")       val keywords: List<String>
)

/**
 * Single result item from the search_volume/live response.
 *
 * Field descriptions:
 *   - keyword:               the queried keyword (may be normalized/lowercased)
 *   - spell:                 spell-corrected keyword, null if no correction
 *   - location_code:         Google Ads geotargeting location code
 *   - language_code:         language code used in the request
 *   - search_partners:       whether Search Partners data is included
 *   - competition:           categorical competition level: "HIGH", "MEDIUM", "LOW", or null
 *   - competition_index:     0–100 integer index of advertiser competition
 *   - search_volume:         estimated average monthly search volume (rounded, last 12 months)
 *   - low_top_of_page_bid:   low range CPC estimate for top-of-page bid (USD)
 *   - high_top_of_page_bid:  high range CPC estimate for top-of-page bid (USD)
 *   - cpc:                   average cost-per-click (USD)
 *   - monthly_searches:      list of monthly historical search volumes (up to 12 months)
 *
 * Notes:
 *   - search_volume is NOT a sum of monthly_searches; it is an independent Google-computed average
 *   - monthly_searches provides granular historical data per calendar month
 *   - Google Ads may group similar keywords and return identical volumes
 *   - Some keywords may return null/0 if Google has insufficient data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchVolumeResultItem(
    val keyword: String? = null,
    val spell: String? = null,
    @JsonProperty("location_code")        val locationCode: Int? = null,
    @JsonProperty("language_code")        val languageCode: String? = null,
    @JsonProperty("search_partners")      val searchPartners: Boolean? = null,
    val competition: String? = null,
    @JsonProperty("competition_index")    val competitionIndex: Int? = null,
    @JsonProperty("search_volume")        val searchVolume: Int? = null,
    @JsonProperty("low_top_of_page_bid")  val lowTopOfPageBid: Double? = null,
    @JsonProperty("high_top_of_page_bid") val highTopOfPageBid: Double? = null,
    val cpc: Double? = null,
    @JsonProperty("monthly_searches")     val monthlySearches: List<MonthlySearch>? = null
)

/**
 * A single month entry within monthly_searches.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MonthlySearch(
    val year: Int? = null,
    val month: Int? = null,
    @JsonProperty("search_volume") val searchVolume: Int? = null
)

// ═══════════════════════════════════════════════════════════════════════
// TRENDS EXPLORE — API REQUEST / RESPONSE
// ═══════════════════════════════════════════════════════════════════════
//
// POST https://api.dataforseo.com/v3/keywords_data/dataforseo_trends/explore/live
// Docs: https://docs.dataforseo.com/v3/keywords_data/dataforseo_trends/explore/live
//
// Used as a SUPPLEMENT to Search Volume:
//   Search Volume monthly_searches has a ~5-6 week lag.
//   Trends Explore fills the gap from the last available month to today.
//
// Constraints:
//   - Max 5 keywords per request
//   - Returns relative index 0-100 (NOT absolute volume)
//   - Daily or weekly granularity depending on date range
// ═══════════════════════════════════════════════════════════════════════

/** Request body for Trends Explore endpoint. */
data class TrendsExploreRequest(
    @JsonProperty("location_name") val locationName: String = "Poland",
    @JsonProperty("type") val type: String = "web",
    @JsonProperty("keywords") val keywords: List<String>,
    @JsonProperty("date_from") val dateFrom: String,
    @JsonProperty("date_to") val dateTo: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExploreResult(
    val keywords: List<String>? = null,
    val type: String? = null,
    @JsonProperty("location_code") val locationCode: Int? = null,
    val datetime: String? = null,
    @JsonProperty("items_count") val itemsCount: Int? = null,
    val items: List<ExploreItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExploreItem(
    val type: String? = null,
    val keywords: List<String>? = null,
    val data: List<ExploreDataPoint>? = null,
    val averages: List<Int>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExploreDataPoint(
    @JsonProperty("date_from") val dateFrom: String? = null,
    @JsonProperty("date_to") val dateTo: String? = null,
    val timestamp: Long? = null,
    val values: List<Int?>? = null
)

// ═══════════════════════════════════════════════════════════════════════
// PUBLIC OUTPUT DTOs
// ═══════════════════════════════════════════════════════════════════════

/**
 * Full search volume response combining country + voivodeship data.
 */
data class SearchVolumeResponse(
    val requestId: String,
    val fetchedAt: String,
    val keywords: List<String>,
    val country: SearchVolumeLocationResult,
    val voivodeships: List<SearchVolumeLocationResult>
)

/**
 * Search volume data for a single location (country or voivodeship).
 */
data class SearchVolumeLocationResult(
    val locationCode: Int,
    val locationName: String,
    val polishName: String?,
    val geoLevel: String,  // "country" | "voivodeship"
    val items: List<SearchVolumeKeywordResult>,
    val rawResponse: String? = null
)

/**
 * Normalized keyword-level result for a single location.
 */
data class SearchVolumeKeywordResult(
    val keyword: String,
    val searchVolume: Int?,
    val competition: String?,
    val competitionIndex: Int?,
    val cpc: Double?,
    val lowTopOfPageBid: Double?,
    val highTopOfPageBid: Double?,
    val monthlySearches: List<MonthlySearch>?
)

/**
 * Compact comparison view: one keyword across all locations.
 */
data class KeywordLocationComparison(
    val keyword: String,
    val country: KeywordLocationValue,
    val voivodeships: List<KeywordLocationValue>
)

data class KeywordLocationValue(
    val locationCode: Int,
    val locationName: String,
    val polishName: String?,
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?
)

/**
 * In-memory cache entry for the last search volume fetch.
 */
data class SearchVolumeCacheEntry(
    val fetchedAt: String,
    val keywords: List<String>,
    val response: SearchVolumeResponse
)

// ═══════════════════════════════════════════════════════════════════════
// COMBINED TREND CHART — Search Volume (monthly) + Trends Explore (daily)
// ═══════════════════════════════════════════════════════════════════════

/**
 * Combined trend chart for a single keyword.
 *
 * Merges two data sources:
 *   - monthlyData: from Google Ads Search Volume (absolute volumes, 12 months history)
 *   - recentDaily: from Trends Explore (relative index 0-100, gap fill to today)
 *
 * The gap period:
 *   - starts on the 1st day after the last available month in monthly_searches
 *   - ends today
 *
 * Example: if monthly_searches latest = Feb 2026, and today = Apr 4, 2026:
 *   monthlyData: Mar'25..Feb'26 (12 points, absolute volumes)
 *   recentDaily: Mar 1..Apr 4 (35 points, relative index 0-100)
 */
data class KeywordTrendChart(
    val keyword: String,
    val locationCode: Int,
    val locationName: String,
    val monthlyData: List<MonthlyDataPoint>,
    val recentDaily: List<DailyDataPoint>,
    val coverage: TrendCoverage
)

/** A single month from Search Volume monthly_searches. */
data class MonthlyDataPoint(
    val year: Int,
    val month: Int,
    val searchVolume: Int?,
    val source: String = "google_ads"
)

/** A single day from Trends Explore (gap fill). */
data class DailyDataPoint(
    val date: String,
    val trendIndex: Int?,
    val source: String = "trends_explore"
)

/** Describes what time range each data source covers. */
data class TrendCoverage(
    val monthlyFrom: String?,
    val monthlyTo: String?,
    val dailyFrom: String?,
    val dailyTo: String?
)

/** Full response combining Search Volume + Trends Explore for all keywords. */
data class TrendChartResponse(
    val fetchedAt: String,
    val keywords: List<String>,
    val charts: List<KeywordTrendChart>
)

