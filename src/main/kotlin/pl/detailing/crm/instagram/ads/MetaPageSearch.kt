package pl.detailing.crm.instagram.ads

import java.text.Normalizer

/**
 * Odsiew podpowiedzi stron po nazwie.
 *
 * `search_terms` w bibliotece reklam przeszukuje TREŚĆ reklam, nie nazwy stron.
 * Fraza „Car Art Detailing" trafia więc w każdego, kto ma w tekście „car"
 * i „detailing" — i tak właśnie wyglądały pierwsze podpowiedzi: obok szukanego
 * studia stały firmy niemające z nim nic wspólnego.
 *
 * Treść reklamy zostaje SITEM (tak zawężamy zapytanie do Meta), ale o tym, co
 * zobaczy człowiek, decyduje nazwa strony. Kandydat bez ani jednego słowa
 * z zapytania w nazwie nie jest podpowiedzią, tylko szumem — a szum przy wyborze
 * identyfikatora kosztuje podpięcie cudzej firmy pod nazwą konkurenta.
 */
object MetaPageSearch {

    /**
     * Kandydaci uporządkowani trafnością nazwy. Gdy żaden nie ma w nazwie nic
     * z zapytania, zwracamy pustkę: lepiej powiedzieć „nie znaleziono" i pozwolić
     * wpisać numer ręcznie, niż podsunąć listę przypadkowych firm.
     */
    fun rank(query: String, candidates: List<MetaPageCandidate>): List<MetaPageCandidate> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()

        val scored = candidates
            .map { it to score(tokens, it.pageName) }
            .filter { (_, score) -> score > 0 }

        return scored
            .sortedWith(
                compareByDescending<Pair<MetaPageCandidate, Double>> { it.second }
                    .thenByDescending { it.first.ads }
                    .thenByDescending { it.first.lastStart }
            )
            .map { it.first }
    }

    /**
     * Udział słów zapytania obecnych w nazwie strony, z premią za nazwę zawierającą
     * całą frazę. „Car Art Detailing" ma dostać wyżej stronę „Car Art Detailing"
     * niż „Detailing Kraków", choć obie mają po jednym trafionym słowie.
     */
    private fun score(tokens: List<String>, pageName: String): Double {
        val name = normalize(pageName)
        if (name.isBlank()) return 0.0

        val hits = tokens.count { name.contains(it) }
        if (hits == 0) return 0.0

        val coverage = hits.toDouble() / tokens.size
        val wholePhrase = name.contains(tokens.joinToString(" "))
        return if (wholePhrase) coverage + 1.0 else coverage
    }

    /** Słowa dłuższe niż litera — „i", „w" nie odróżniają niczego od niczego. */
    private fun tokenize(query: String): List<String> =
        normalize(query).split(' ').filter { it.length > 1 }

    /**
     * Bez ogonków i bez interpunkcji: „Car Art Detailing" ma trafiać w „CAR-ART
     * detailing", a „Żółw" w „Zolw". Porównujemy nazwy pisane przez ludzi.
     */
    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ł', 'l')
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()
}
