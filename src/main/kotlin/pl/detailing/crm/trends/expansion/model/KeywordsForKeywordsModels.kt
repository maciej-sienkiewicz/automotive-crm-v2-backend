package pl.detailing.crm.trends.expansion.model

import com.fasterxml.jackson.annotation.JsonProperty

// ─── POST /v3/keywords_data/google_ads/keywords_for_keywords/live ─────────────
//
// Given seed keywords, returns related queries that people actually search for.
// Response items share the same structure as search_volume/live (SearchVolumeResultItem).
// Docs: https://docs.dataforseo.com/v3/keywords_data/google_ads/keywords_for_keywords/live
//
// Constraints:
//   - Max 20 seed keywords per request
//   - Rate limit: 12 req/min (same pool as search_volume/live)
//   - Cost: $0.075 / request (same as search_volume)

data class KeywordsForKeywordsRequest(
    @JsonProperty("keywords")             val keywords: List<String>,
    @JsonProperty("location_code")        val locationCode: Int = 2616,
    @JsonProperty("language_code")        val languageCode: String = "pl",
    /** When true the API includes seed keywords themselves in the results. */
    @JsonProperty("include_seed_keyword") val includeSeedKeyword: Boolean = true
)
