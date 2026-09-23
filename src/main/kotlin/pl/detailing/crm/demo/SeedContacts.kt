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
sealed interface SeedContacts {
    fun email(value: String): String
    fun phone(value: String): String

    /** Adres odbiorcy z dziennika wysyłek - e-mail albo numer telefonu. */
    fun address(value: String): String = if ('@' in value) email(value) else phone(value)

    companion object {
        /** Konto DEMO: dane bez zmian. */
        val REALISTIC: SeedContacts = Realistic

        /**
         * Piaskownica: nowy komplet zastępników na każde zasiewanie. Jeden obiekt obsługuje
         * jedno wywołanie seeda - to on pamięta, które zastępniki są już zajęte.
         */
        fun undeliverable(): SeedContacts = Undeliverable()
    }
}

private data object Realistic : SeedContacts {
    override fun email(value: String) = value
    override fun phone(value: String) = value
}

/**
 * Zastępniki muszą być różnowartościowe: telefon i e-mail klienta są unikalne w studiu
 * (`idx_customers_studio_phone`, `idx_customers_studio_email`), więc dwa numery sklejone
 * w jeden zastępnik wywracają całe zakładanie piaskownicy. Samo „ostatnie sześć cyfr"
 * tego nie gwarantuje: +48 601 234 567 i +48 501 234 567 kończą się tak samo.
 *
 * Dlatego zastępnik jest przydzielany przy pierwszym użyciu i zapamiętany. Ten sam numer
 * (klient, jego lead, SMS w dzienniku wysyłek) zawsze dostaje ten sam zastępnik, a numer,
 * którego naturalny zastępnik jest już zajęty, dostaje kolejny wolny.
 */
private class Undeliverable : SeedContacts {
    private val phoneByNumber = HashMap<String, String>()
    private val takenPhones = HashSet<String>()
    private val emailByAddress = HashMap<String, String>()
    private val takenEmails = HashSet<String>()

    // +48 512 345 678 -> +48 000 345 678: ostatnie cyfry zostają, żeby numery klientów
    // dalej się od siebie różniły, a zero na początku numeru krajowego gwarantuje,
    // że operator takiego numeru nie przyjmie.
    override fun phone(value: String): String {
        if (value.isBlank()) return value
        val digits = value.filter(Char::isDigit)
        return phoneByNumber.getOrPut(digits) {
            val start = digits.takeLast(SUBSCRIBER_DIGITS).padStart(SUBSCRIBER_DIGITS, '0').toInt()
            val substitute = (0 until SUBSCRIBER_NUMBERS).asSequence()
                .map { offset -> NO_SUCH_NUMBER_PREFIX + ((start + offset) % SUBSCRIBER_NUMBERS).toString().padStart(SUBSCRIBER_DIGITS, '0') }
                .first { it !in takenPhones }
            takenPhones += substitute
            substitute
        }
    }

    override fun email(value: String): String {
        if (value.isBlank()) return value
        return emailByAddress.getOrPut(value) {
            val localPart = value.substringBefore('@')
            val substitute = generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) "$localPart@$RESERVED_DOMAIN" else "$localPart-$n@$RESERVED_DOMAIN" }
                .first { it !in takenEmails }
            takenEmails += substitute
            substitute
        }
    }

    private companion object {
        const val NO_SUCH_NUMBER_PREFIX = "+48000"
        const val SUBSCRIBER_DIGITS = 6
        const val SUBSCRIBER_NUMBERS = 1_000_000
        const val RESERVED_DOMAIN = "example.com"
    }
}
