package pl.detailing.crm.shared

/**
 * Zamienia jedno pole „szukaj" z listy dokumentów na parametry, które natywne
 * zapytanie podstawia do warunków LIKE.
 *
 * ## Dlaczego w jednym miejscu
 *
 * Wyszukiwarka faktur ma odpowiadać na wszystko, czym człowiek identyfikuje dokument:
 * NIP, nazwę kontrahenta, nazwę pozycji, numer dokumentu, numer KSeF albo kwotę.
 * Każde z tych pól jest w bazie zapisane inaczej niż wpisuje je użytkownik, więc samo
 * `LIKE '%fraza%'` po kolumnach tekstowych odpowiada „brak wyników" na zapytania,
 * które oczywiście powinny trafić:
 *
 * - **NIP** przychodzi z KSeF jako `PL1234563218`, a z ręcznego wpisu jako
 *   `123-456-32-18`; użytkownik wkleja `1234563218`. Stąd osobny parametr
 *   [digitsLike], porównywany z kolumną zredukowaną do samych cyfr.
 * - **Kwota** jest w bazie liczbą groszy (`123050`), a na ekranie i na papierze
 *   `1230,50`. Szukanie po `123050` byłoby szukaniem po wewnętrznej reprezentacji,
 *   więc [amountLike] normalizuje wpis do formatu złotówkowego z kropką, a zapytanie
 *   porównuje go z kwotą sformatowaną tak samo. Wpisanie samego `1230` dalej trafia
 *   w `1230,50`, bo dopasowanie jest fragmentem.
 * - **Znaki LIKE** (`%`, `_`) we frazie muszą być literałami — inaczej `%` wpisany
 *   w pole szukania pokazuje wszystko, a `_` psuje dopasowanie po cichu.
 *
 * Wszystkie metody zwracają `null` dla pustego albo nieprzystającego wejścia — zapytania
 * traktują `null` jako „nie filtruj po tym", więc pusta wyszukiwarka nie zmienia listy.
 */
object SearchTerm {

    /** Górny limit długości frazy — dłuższe wejście to nie wyszukiwanie, tylko wklejka. */
    private const val MAX_LENGTH = 120

    /**
     * Ile cyfr musi zostać z frazy, żeby próbować dopasowania po NIP-ie. Fraza „auto 5"
     * niesie jedną cyfrę, a `%5%` pasuje do niemal każdego NIP-u — dokładanie takich
     * trafień do wyników nazwy jest szumem, nie pomocą. Fragment NIP-u, którego ktoś
     * faktycznie szuka, ma co najmniej trzy cyfry.
     */
    private const val MIN_NIP_DIGITS = 3

    /** Kwota tak, jak pisze ją człowiek: `1230`, `1230,50`, `1 230.5`. */
    private val AMOUNT = Regex("""^-?\d{1,12}([.]\d{1,2})?$""")

    /**
     * Fraza jako wzorzec `%…%` do porównań tekstowych. Wynik jest małymi literami,
     * więc kolumny trzeba porównywać przez `LOWER(...)`.
     */
    fun like(raw: String?): String? {
        val term = raw?.trim()?.take(MAX_LENGTH)?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return "%${escapeLike(term)}%"
    }

    /**
     * Fraza zredukowana do cyfr, jako wzorzec `%…%`. Służy do dopasowania NIP-u
     * niezależnie od prefiksu kraju i myślników po obu stronach porównania.
     * `null`, gdy fraza nie zawiera cyfr.
     */
    fun digitsLike(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() }?.take(MAX_LENGTH) ?: return null
        if (digits.length < MIN_NIP_DIGITS) return null
        return "%$digits%"
    }

    /**
     * Fraza rozumiana jako kwota w złotych: spacje (także twarde) znikają, przecinek
     * staje się kropką. Wynik to wzorzec `%…%` porównywany z kwotą sformatowaną
     * w zapytaniu jako `TO_CHAR(grosze / 100.0, 'FM9999999990.00')`.
     * `null`, gdy fraza nie wygląda na kwotę — wtedy zapytanie nie tyka kolumn kwotowych.
     */
    fun amountLike(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.take(MAX_LENGTH)
            // Spacja rozdzielająca tysiące bywa twarda (NBSP) — wklejona kwota musi działać tak samo.
            ?.replace('\u00A0', ' ')
            ?.replace('\u202F', ' ')
            ?.replace(" ", "")
            ?.replace(',', '.')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (!AMOUNT.matches(normalized)) return null
        return "%$normalized%"
    }

    /** `%`, `_` i sam znak ucieczki wpisane we frazę są literałami, nie wzorcem. */
    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
