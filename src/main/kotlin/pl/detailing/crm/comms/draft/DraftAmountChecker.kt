package pl.detailing.crm.comms.draft

/**
 * Kontrola szkicu po stronie kodu — prompt prosi, kod sprawdza.
 *
 * Kwota w mailu do klienta to zobowiązanie. Model poproszony o przepisanie ceny z wyceny
 * zwykle to robi, ale „zwykle" nie wystarcza: jedna zaokrąglona albo dosumowana kwota
 * to cena, której nikt w studiu nie ustalił. Nie poprawiamy jej po cichu (nie wiemy,
 * co autor miał na myśli) — oddajemy ją ekranowi jako ostrzeżenie do sprawdzenia.
 */
object DraftAmountChecker {

    /**
     * „1 900,00 zł", „1900 zł", „1.900 zł", „1 900 PLN", „1900,5 zł". Grupy tysięcy
     * mają dokładnie trzy cyfry, więc „1.90 zł" nie jest brane za tysiąc dziewięćset.
     */
    private val AMOUNT = Regex(
        """(?<![\d,.])(\d{1,3}(?:[  .]\d{3})+|\d+)(?:,(\d{1,2}))?\s*(?:zł|zl|pln)(?![\p{L}])""",
        RegexOption.IGNORE_CASE
    )

    private val PLACEHOLDER = Regex("""\[[^\[\]\n]{2,80}]""")

    /** Kwoty ze szkicu, których nie ma w wycenie — w zapisie, w jakim stoją w tekście. */
    fun unverifiedAmounts(text: String, allowed: Set<Long>): List<String> =
        AMOUNT.findAll(text)
            .filter { match -> toGrosze(match) !in allowed }
            .map { it.value.trim() }
            .distinct()
            .toList()

    /** Znaczniki do uzupełnienia („[proponowany termin]") — szkic z nimi nie jest gotowy do wysłania. */
    fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.distinct().toList()

    private fun toGrosze(match: MatchResult): Long {
        val zl = match.groupValues[1].filter(Char::isDigit).toLong()
        val fraction = match.groupValues[2].takeIf { it.isNotEmpty() }?.padEnd(2, '0')?.toLong() ?: 0L
        return zl * 100 + fraction
    }
}
