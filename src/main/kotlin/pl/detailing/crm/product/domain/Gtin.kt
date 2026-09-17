package pl.detailing.crm.product.domain

import pl.detailing.crm.shared.ValidationException

/**
 * GTIN sprowadzony do 14 cyfr z poprawną sumą kontrolną.
 *
 * Po co osobny typ, a nie String: kod kreskowy jest KLUCZEM TOŻSAMOŚCI produktu
 * w katalogu globalnym (jeden GTIN = jeden wiersz dla wszystkich tenantów). Ten sam
 * produkt bywa wydrukowany raz jako EAN-13, raz jako UPC-A (12) czy EAN-8 — bez
 * normalizacji do 14 cyfr z wiodącymi zerami te warianty rozjechałyby się na trzy
 * wiersze i cała deduplikacja katalogu przestałaby działać.
 *
 * Walidacja sumy kontrolnej jest BRAMĄ przed płatnym zapytaniem: kod z przestawioną
 * cyfrą odrzucamy tu, zanim poleci cokolwiek do modelu językowego czy GS1.
 */
@JvmInline
value class Gtin private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val DIGITS = Regex("^[0-9]+$")

        /** Zwraca znormalizowany GTIN-14 albo null (nie rzuca — do miękkiego sprawdzania). */
        fun parseOrNull(raw: String?): Gtin? {
            val digits = raw?.trim()?.replace(" ", "")?.replace("-", "") ?: return null
            if (!DIGITS.matches(digits)) return null
            if (digits.length !in intArrayOf(8, 12, 13, 14)) return null
            val padded = digits.padStart(14, '0')
            if (!checksumValid(padded)) return null
            return Gtin(padded)
        }

        /** Jak [parseOrNull], ale rzuca [ValidationException] — do wejścia z API. */
        fun parse(raw: String?): Gtin = parseOrNull(raw)
            ?: throw ValidationException("Niepoprawny kod kreskowy — suma kontrolna się nie zgadza.")

        /**
         * Suma kontrolna GS1 (mod-10): cyfry od prawej ważone naprzemiennie 3 i 1,
         * ostatnia cyfra dopełnia sumę do wielokrotności 10. Reguła jest ta sama dla
         * GTIN-8/12/13/14, gdy policzyć ją na 14 cyfrach z wiodącymi zerami.
         */
        private fun checksumValid(gtin14: String): Boolean {
            if (gtin14.length != 14) return false
            val body = gtin14.dropLast(1)
            val check = gtin14.last().digitToInt()
            var sum = 0
            // Waga zależy od pozycji liczonej OD PRAWEJ strony ciała (bez cyfry kontrolnej):
            // pozycja najbliższa cyfrze kontrolnej ma wagę 3.
            for ((i, c) in body.reversed().withIndex()) {
                val weight = if (i % 2 == 0) 3 else 1
                sum += c.digitToInt() * weight
            }
            val expected = (10 - sum % 10) % 10
            return expected == check
        }
    }
}
