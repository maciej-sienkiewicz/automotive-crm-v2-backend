package pl.detailing.crm.phototags

import org.springframework.stereotype.Service

/**
 * Returns a curated list of tag suggestions for the photo tagging UI.
 *
 * Suggestions cover common detailing use-cases: car zones, damage types,
 * treatment types (PPF, ceramic, paint correction), and workflow stages (before/after).
 */
@Service
class GetTagSuggestionsHandler {

    fun handle(): GetTagSuggestionsResult = GetTagSuggestionsResult(suggestions = SUGGESTIONS)

    companion object {
        private val SUGGESTIONS = listOf(
            // Car zones
            "przód", "tył", "lewy bok", "prawy bok",
            "dach", "maska", "zderzak", "szyba",
            "słupek", "progowa", "lusterko",

            // Damage types
            "uszkodzenie", "zarysowanie", "wgniecenie",

            // Treatments
            "PPF", "folia", "ceramika", "lakier", "korekta lakieru", "detailing",

            // Wheel / tyre
            "felga", "opona",

            // Interior / cargo
            "wnętrze", "bagażnik", "koło zapasowe",

            // Workflow stages
            "przed", "po"
        )
    }
}

data class GetTagSuggestionsResult(
    val suggestions: List<String>
)
