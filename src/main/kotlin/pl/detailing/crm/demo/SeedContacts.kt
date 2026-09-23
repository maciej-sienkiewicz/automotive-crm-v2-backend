package pl.detailing.crm.demo

/**
 * Dane kontaktowe w danych przykładowych.
 *
 * Konto DEMO pokazuje realistyczne numery i adresy. Piaskownica podglądu roli dostaje
 * w ich miejsce adresy, pod które NIC nie da się dostarczyć: domena `example.com` jest
 * zarezerwowana (RFC 2606), a numer z zerem po kierunkowym nie istnieje w polskiej
 * numeracji. To druga linia obrony - pierwszą jest bezpiecznik wysyłek piaskownicy -
 * na wypadek, gdyby jakaś wysyłka jednak się prześlizgnęła: nie ma prawa trafić do
 * prawdziwej osoby, której numer przypadkiem zgadza się z przykładowym.
 */
enum class SeedContacts {
    REALISTIC {
        override fun email(value: String) = value
        override fun phone(value: String) = value
    },
    UNDELIVERABLE {
        override fun email(value: String) = value.substringBefore('@') + "@example.com"

        // +48 512 345 678 -> +48 000 345 678: ostatnie cyfry zostają, żeby numery klientów
        // dalej się od siebie różniły, a zero na początku numeru krajowego gwarantuje,
        // że operator takiego numeru nie przyjmie.
        override fun phone(value: String): String {
            val digits = value.filter(Char::isDigit)
            return "+48000" + digits.takeLast(6).padStart(6, '0')
        }
    };

    abstract fun email(value: String): String
    abstract fun phone(value: String): String

    /** Adres odbiorcy z dziennika wysyłek - e-mail albo numer telefonu. */
    fun address(value: String): String = if ('@' in value) email(value) else phone(value)
}
