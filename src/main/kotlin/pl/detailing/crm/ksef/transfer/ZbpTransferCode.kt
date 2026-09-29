package pl.detailing.crm.ksef.transfer

/**
 * Treść kodu QR do przelewu wg „Rekomendacji Związku Banków Polskich dotyczącej kodu
 * dwuwymiarowego (2D)" - ten kod czytają aplikacje mBanku, ING, PKO i reszty rynku.
 *
 * Dziewięć pól rozdzielonych `|`, w tej kolejności i z tymi limitami znaków:
 *
 * | # | Pole                    | Max | Tu                                        |
 * |---|-------------------------|-----|-------------------------------------------|
 * | 1 | NIP odbiorcy            | 10  | tylko z poprawną sumą kontrolną, inaczej pusto |
 * | 2 | Kod kraju               | 2   | zawsze `PL`                               |
 * | 3 | Numer rachunku (NRB)    | 26  | same cyfry, z poprawną sumą kontrolną     |
 * | 4 | Kwota w groszach        | 6   | uzupełniona zerami z lewej: 12,30 zł = `001230` |
 * | 5 | Nazwa odbiorcy          | 20  |                                           |
 * | 6 | Tytuł płatności         | 32  |                                           |
 * | 7-9 | Rezerwa (Invoobill, …) | 20, 12, 24 | puste                          |
 *
 * Całość mieści się w 160 znakach. Kodowanie UTF-8, korekcja błędów L, obraz co
 * najmniej 250 px (parametry obrazka: [BankQrCodeGenerator]).
 *
 * Pole kwoty ma 6 cyfr, więc kod mieści najwyżej 9 999,99 zł. Większej kwoty nie
 * zapisujemy wbrew standardowi: nie wiemy, jak każda aplikacja banku przeczyta pole
 * dłuższe niż 6 cyfr, a kod z błędną kwotą jest gorszy niż brak kodu.
 *
 * Czysta logika bez Springa, żeby dało się ją sprawdzić co do znaku.
 */
object ZbpTransferCode {

    const val MAX_AMOUNT_GROSZE = 999_999L
    const val MAX_LENGTH = 160
    const val RECIPIENT_NAME_MAX = 20
    const val TITLE_MAX = 32

    private const val SEPARATOR = "|"
    private const val COUNTRY = "PL"

    // Litery łacińskie (z polskimi znakami), cyfry i znaki, które bez kłopotu przechodzą
    // przez aplikacje banków. Reszta wypada, w tym `|` - separator pól, który w nazwie
    // przesunąłby wszystkie następne pola - oraz cudzysłowy z nazw w rodzaju "ABC" Sp. z o.o.
    private val DISALLOWED = Regex("[^\\p{IsLatin}0-9 ,./\\\\@#&*_-]")
    private val WHITESPACE_OR_CONTROL = Regex("[\\p{Cntrl}\\s\\u00A0\\u2007\\u202F]+")
    private val NRB_SEPARATORS = Regex("[\\s\\u00A0\\u2007\\u202F-]")
    private val NIP_WEIGHTS = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)

    /**
     * Rachunek z faktury → 26 cyfr NRB, albo null, gdy to nie jest poprawny polski rachunek.
     *
     * Przyjmuje zapis ze spacjami, myślnikami i z prefiksem `PL` (IBAN), bo tak rachunki
     * przychodzą z KSeF i z ręcznie wpisanych dokumentów. Sprawdza sumę kontrolną IBAN
     * (mod 97): literówka w numerze rachunku to przelew do nikogo albo do kogoś obcego.
     */
    fun normalizeNrb(raw: String?): String? {
        val compact = raw?.replace(NRB_SEPARATORS, "")?.uppercase() ?: return null
        val digits = if (compact.startsWith(COUNTRY)) compact.substring(COUNTRY.length) else compact
        if (!digits.matches(Regex("\\d{26}"))) return null
        return digits.takeIf { ibanChecksumValid(it) }
    }

    /** NIP → 10 cyfr, albo null, gdy suma kontrolna się nie zgadza. Pole jest opcjonalne. */
    fun normalizeNip(raw: String?): String? {
        val compact = raw?.replace(Regex("[^0-9A-Za-z]"), "")?.uppercase() ?: return null
        val digits = if (compact.startsWith(COUNTRY)) compact.substring(COUNTRY.length) else compact
        if (!digits.matches(Regex("\\d{10}"))) return null
        val sum = (0 until 9).sumOf { digits[it].digitToInt() * NIP_WEIGHTS[it] }
        val check = sum % 11
        return digits.takeIf { check != 10 && check == digits[9].digitToInt() }
    }

    /**
     * Tekst do pola kodu: separator, białe i sterujące znaki zamienione na spację, niedozwolone
     * usunięte, spacje ściśnięte, całość przycięta do limitu pola (liczonego w znakach).
     */
    fun sanitizeText(raw: String?, maxLength: Int): String =
        raw.orEmpty()
            // Separator pól staje się spacją, a nie znika: „FV|1|2" to „FV 1 2", nie „FV12".
            .replace(SEPARATOR, " ")
            .replace(WHITESPACE_OR_CONTROL, " ")
            .replace(DISALLOWED, "")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(maxLength)
            .trimEnd()

    /**
     * Tytuł przelewu z numeru faktury. Numer jest tym, po czym sprzedawca rozpozna
     * wpłatę, więc gdy „Faktura " + numer się nie mieści, zostaje sam numer - nigdy
     * numer ucięty dla ozdobnika.
     */
    fun title(invoiceNumber: String?): String {
        val number = sanitizeText(invoiceNumber, TITLE_MAX)
        if (number.isEmpty()) return "Zapłata za fakturę"
        val prefixed = "Faktura $number"
        return if (prefixed.length <= TITLE_MAX) prefixed else number
    }

    /**
     * Gotowa treść kodu. Wymaga danych już znormalizowanych ([normalizeNrb], [normalizeNip],
     * [sanitizeText]) - nie poprawia ich po cichu, tylko odrzuca.
     */
    fun payload(nip: String?, nrb: String, amountGrosze: Long, recipientName: String, title: String): String {
        require(nip == null || nip.matches(Regex("\\d{10}"))) { "NIP musi mieć 10 cyfr" }
        require(nrb.matches(Regex("\\d{26}"))) { "NRB musi mieć 26 cyfr" }
        require(amountGrosze in 1..MAX_AMOUNT_GROSZE) { "Kwota poza zakresem pola ZBP: $amountGrosze gr" }
        require(recipientName.isNotBlank() && recipientName.length <= RECIPIENT_NAME_MAX) { "Nazwa odbiorcy: 1-$RECIPIENT_NAME_MAX znaków" }
        require(title.isNotBlank() && title.length <= TITLE_MAX) { "Tytuł: 1-$TITLE_MAX znaków" }
        require(SEPARATOR !in recipientName && SEPARATOR !in title) { "Separator pól w treści pola" }

        val fields = listOf(
            nip.orEmpty(),
            COUNTRY,
            nrb,
            amountGrosze.toString().padStart(6, '0'),
            recipientName,
            title,
            "", "", "",
        )
        return fields.joinToString(SEPARATOR).also {
            check(it.length <= MAX_LENGTH) { "Treść kodu ZBP przekracza $MAX_LENGTH znaków" }
        }
    }

    /** IBAN PL: cyfry kontrolne na koniec razem z „PL" jako 2521, reszta z dzielenia przez 97 równa 1. */
    private fun ibanChecksumValid(nrb: String): Boolean {
        val rearranged = nrb.substring(2) + "2521" + nrb.substring(0, 2)
        var remainder = 0
        for (c in rearranged) remainder = (remainder * 10 + c.digitToInt()) % 97
        return remainder == 1
    }
}
