package pl.detailing.crm.instagram.ads

/**
 * Co właściwie ktoś wkleił w pole „strona konkurenta".
 *
 * Facebook pokazuje tę samą stronę pod trzema postaciami naraz:
 *   facebook.com/profile.php?id=100064043405405   — numer wprost
 *   facebook.com/CarArtDetailing                  — alias, gdy strona go ma
 *   facebook.com/ads/library/?...view_all_page_id=176612669486327
 * a do tego stary format facebook.com/pages/Nazwa/123456789.
 *
 * Kazać człowiekowi rozpoznawać, którą postać ma przed sobą, to przerzucanie na
 * niego naszej pracy — zwłaszcza że numer i alias wyglądają zupełnie inaczej,
 * a oba są poprawną odpowiedzią na pytanie „gdzie jest ta firma".
 */
sealed interface PageInput {
    /** Numer strony — da się od razu odpytać bibliotekę reklam. */
    data class Id(val pageId: String) : PageInput

    /** Alias albo nazwa — trzeba dopiero ustalić numer. */
    data class Term(val term: String) : PageInput

    /** Puste albo niemożliwe do odczytania. */
    data object Empty : PageInput
}

object MetaPageInput {

    /** Segmenty ścieżki, które nigdy nie są aliasem strony. */
    private val NON_ALIAS = setOf(
        "ads", "library", "profile.php", "pages", "people", "groups", "events",
        "photo", "photos", "watch", "share", "p", "pg"
    )

    /** Krótszy ciąg cyfr to nie identyfikator strony, tylko numer z nazwy albo rok. */
    private const val MIN_ID_LENGTH = 8

    fun parse(raw: String?): PageInput {
        val input = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return PageInput.Empty

        // Sam numer — najkrótsza droga.
        if (input.all { it.isDigit() }) {
            return if (input.length >= MIN_ID_LENGTH) PageInput.Id(input) else PageInput.Term(input)
        }

        // Numer schowany w parametrze adresu: ads/library albo profile.php.
        idFromQuery(input, "view_all_page_id")?.let { return PageInput.Id(it) }
        idFromQuery(input, "id")?.let { return PageInput.Id(it) }

        if (!input.contains("facebook.com") && !input.contains("instagram.com")) {
            // Zwykły tekst: nazwa firmy albo alias wklejony bez adresu.
            return PageInput.Term(input.removePrefix("@").trim())
        }

        val segments = input
            .substringAfter("facebook.com", input.substringAfter("instagram.com", ""))
            .substringBefore('?')
            .substringBefore('#')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Stary format facebook.com/pages/Nazwa/123456789 — numer stoi na końcu.
        segments.lastOrNull { it.all(Char::isDigit) && it.length >= MIN_ID_LENGTH }
            ?.let { return PageInput.Id(it) }

        val alias = segments.firstOrNull { it.lowercase() !in NON_ALIAS }
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
            ?: return PageInput.Empty

        return PageInput.Term(alias)
    }

    /**
     * Alias na słowa: „CarArtDetailing" → „Car Art Detailing".
     *
     * Biblioteka reklam nie zna aliasów — szuka po treści reklam i po niej dopiero
     * dopasowujemy nazwy stron. Alias pisany łącznie nie trafiłby w nic, więc
     * rozbijamy go tam, gdzie człowiek widzi granicę słowa: wielka litera, kropka,
     * podkreślenie, myślnik.
     */
    fun toSearchTerm(term: String): String {
        val spaced = term
            .replace(Regex("[._-]+"), " ")
            .replace(Regex("(?<=[a-ząćęłńóśźż0-9])(?=[A-ZĄĆĘŁŃÓŚŹŻ])"), " ")
            .replace(Regex(" +"), " ")
            .trim()
        return spaced.ifBlank { term }
    }

    private fun idFromQuery(input: String, key: String): String? =
        Regex("[?&]${Regex.escape(key)}=(\\d+)").find(input)?.groupValues?.get(1)
            ?.takeIf { it.length >= MIN_ID_LENGTH }
}
