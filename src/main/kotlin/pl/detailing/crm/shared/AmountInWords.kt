package pl.detailing.crm.shared

/**
 * Kwota słownie po polsku, w formacie używanym na fakturach:
 * „dwa tysiące dwieście czternaście złotych 00/100".
 *
 * Nie jest to element wymagany przez art. 106e ustawy o VAT, ale jest na polskich
 * fakturach na tyle powszechny, że jego brak czyta się jak niedokończony dokument —
 * i jest jedynym zabezpieczeniem przed dopisaniem cyfry do kwoty na wydruku.
 *
 * Grosze celowo zostają cyframi (00/100), tak jak w praktyce bankowej: słowna forma
 * groszy nic nie wnosi, a wydłuża wiersz o pół linijki.
 */
object AmountInWords {

    private val UNITS = listOf(
        "", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem", "dziewięć",
        "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście", "piętnaście",
        "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście"
    )
    private val TENS = listOf(
        "", "", "dwadzieścia", "trzydzieści", "czterdzieści", "pięćdziesiąt",
        "sześćdziesiąt", "siedemdziesiąt", "osiemdziesiąt", "dziewięćdziesiąt"
    )
    private val HUNDREDS = listOf(
        "", "sto", "dwieście", "trzysta", "czterysta", "pięćset",
        "sześćset", "siedemset", "osiemset", "dziewięćset"
    )

    /** Formy: 1, 2-4, 5+ — polska odmiana rzeczownika po liczebniku. */
    private data class Forms(val one: String, val few: String, val many: String)

    private val THOUSAND = Forms("tysiąc", "tysiące", "tysięcy")
    private val MILLION = Forms("milion", "miliony", "milionów")
    private val BILLION = Forms("miliard", "miliardy", "miliardów")
    private val ZLOTY = Forms("złoty", "złote", "złotych")

    /** @param grosze kwota w groszach; ujemna zwraca formę z „minus". */
    fun format(grosze: Long): String {
        val sign = if (grosze < 0) "minus " else ""
        val absolute = Math.abs(grosze)
        val zlote = absolute / 100
        val reszta = absolute % 100
        return "$sign${spell(zlote)} ${form(zlote, ZLOTY)} ${"%02d".format(reszta)}/100"
    }

    private fun spell(value: Long): String {
        if (value == 0L) return "zero"
        val groups = mutableListOf<String>()
        var rest = value
        val scales = listOf<Forms?>(null, THOUSAND, MILLION, BILLION)
        var scaleIndex = 0
        val parts = mutableListOf<Pair<Int, Forms?>>()
        while (rest > 0) {
            parts += (rest % 1000).toInt() to scales.getOrNull(scaleIndex)
            rest /= 1000
            scaleIndex++
        }
        parts.asReversed().forEach { (group, scale) ->
            if (group == 0) return@forEach
            val words = spellGroup(group)
            groups += when {
                scale == null -> words
                // „tysiąc", nie „jeden tysiąc" — polszczyzna nie liczy pierwszej setki
                // ani pierwszego tysiąca liczebnikiem.
                group == 1 -> scale.one
                else -> "$words ${form(group.toLong(), scale)}"
            }
        }
        return groups.joinToString(" ").trim()
    }

    private fun spellGroup(group: Int): String {
        val hundreds = group / 100
        val rest = group % 100
        val words = mutableListOf<String>()
        if (hundreds > 0) words += HUNDREDS[hundreds]
        when {
            rest in 1..19 -> words += UNITS[rest]
            rest >= 20 -> {
                words += TENS[rest / 10]
                if (rest % 10 > 0) words += UNITS[rest % 10]
            }
        }
        return words.joinToString(" ")
    }

    /**
     * Polska odmiana: 1 złoty, 2-4 złote, 5-21 złotych, 22 złote…
     * Wyjątek na 12-14 („dwanaście złotych", nie „złote") jest tu celowo — bez niego
     * kwota 1 213 zł wychodziłaby jako „tysiąc dwieście trzynaście złote".
     */
    private fun form(value: Long, forms: Forms): String {
        if (value == 1L) return forms.one
        val lastTwo = value % 100
        val last = value % 10
        return when {
            lastTwo in 12..14 -> forms.many
            last in 2..4 -> forms.few
            else -> forms.many
        }
    }
}
