package pl.detailing.crm.leads.intake

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** Pola leada, które umiemy rozpoznać w formularzu. */
enum class LeadFormField {
    EMAIL, PHONE, NAME, MESSAGE, VEHICLE_BRAND, VEHICLE_MODEL, VEHICLE_YEAR, SERVICE, COMPANY, CONSENT
}

/**
 * Wynik mapowania: co udało się rozpoznać i co zostało.
 *
 * `leftovers` jest równie ważne co reszta. Formularze studiów mają pola, których nie
 * przewidzimy — „skąd o nas wiesz", „preferowany termin", „stan lakieru". Wyrzucenie
 * ich znaczyłoby, że lead z formularza wie MNIEJ niż mail, który zastępuje. Trafiają
 * więc na koniec treści zapytania, dosłownie tak, jak przyszły.
 */
data class MappedForm(
    val values: Map<LeadFormField, String>,
    val leftovers: List<FormField>
) {
    operator fun get(field: LeadFormField): String? = values[field]
}

/**
 * Tłumaczy spłaszczony formularz na pola leada.
 *
 * Słownik synonimów jest tu świadomie hojny: lepiej rozpoznać o jedno pole za dużo
 * i pokazać je w leadzie, niż zmusić właściciela studia do konfigurowania mapowania,
 * zanim cokolwiek zadziała. Dopasowanie idzie od najbardziej szczegółowego do
 * najogólniejszego — „marka pojazdu" przed „marka" — bo pierwszy trafiony wygrywa.
 *
 * Nadpisania z webhooka mają pierwszeństwo przed słownikiem: gdy formularz nazywa
 * telefon „numer-do-kontaktu-zwrotnego", da się to wpisać raz i przestać o tym myśleć.
 */
@Component
class LeadFieldMapper(private val objectMapper: ObjectMapper) {

    fun map(fields: List<FormField>, overridesJson: String?): MappedForm {
        val overrides = parseOverrides(overridesJson)
        val values = LinkedHashMap<LeadFormField, String>()
        val leftovers = mutableListOf<FormField>()

        fields.forEach { field ->
            val normalized = normalizeFieldName(field.label)
            // Nazwa z prefiksem („dane.telefon") — próbujemy też samego ogona.
            val tail = normalizeFieldName(field.label.substringAfterLast('.'))
            val target = matchOverride(overrides, normalized, tail)
                ?: matchDictionary(normalized)
                ?: matchDictionary(tail)

            if (target == null) {
                if (!isNoise(normalized)) leftovers += field
                return@forEach
            }
            // Pierwsza wartość wygrywa: formularze potrafią powtórzyć to samo pole
            // (raz jako etykieta, raz jako nazwa techniczna), a druga kopia bywa gorsza.
            values.putIfAbsent(target, field.value)
        }

        return MappedForm(values, leftovers)
    }

    private fun matchOverride(
        overrides: Map<LeadFormField, List<String>>,
        normalized: String,
        tail: String
    ): LeadFormField? = overrides.entries
        .firstOrNull { (_, names) -> names.any { it == normalized || it == tail } }
        ?.key

    private fun matchDictionary(normalized: String): LeadFormField? {
        if (normalized.isBlank()) return null
        // Najpierw trafienie dokładne, potem zawieranie — inaczej „marka" złapałaby
        // „marka i model", a chcemy najpierw sprawdzić, czy nie ma osobnego pola.
        DICTIONARY.forEach { (field, names) ->
            if (names.any { it == normalized }) return field
        }
        DICTIONARY.forEach { (field, names) ->
            if (names.any { normalized.contains(it) }) return field
        }
        return null
    }

    /**
     * Pola techniczne wtyczek i śledzenia — nie są treścią zapytania i nie mają czego
     * szukać w wiadomości do przeczytania przez człowieka.
     */
    private fun isNoise(normalized: String): Boolean =
        NOISE.any { normalized == it || normalized.startsWith(it) }

    private fun parseOverrides(json: String?): Map<LeadFormField, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        val parsed = runCatching {
            objectMapper.readValue(json, Map::class.java) as Map<*, *>
        }.getOrNull() ?: return emptyMap()

        return parsed.entries.mapNotNull { (key, value) ->
            val field = runCatching { LeadFormField.valueOf(key.toString().uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            val names = when (value) {
                is List<*> -> value.mapNotNull { it?.toString() }
                is String -> listOf(value)
                else -> emptyList()
            }.map(::normalizeFieldName).filter { it.isNotBlank() }
            if (names.isEmpty()) null else field to names
        }.toMap()
    }

    private companion object {
        /**
         * Kolejność ma znaczenie: pola bardziej szczegółowe stoją wyżej, bo dopasowanie
         * „po zawieraniu" bierze pierwsze trafienie.
         */
        val DICTIONARY: List<Pair<LeadFormField, List<String>>> = listOf(
            LeadFormField.EMAIL to listOf(
                "email", "e mail", "mail", "adres email", "adres e mail", "twoj email",
                "your email", "email address", "adres mailowy", "poczta"
            ),
            LeadFormField.PHONE to listOf(
                "telefon", "nr telefonu", "numer telefonu", "tel", "phone", "your phone",
                "phone number", "komorka", "telefon kontaktowy", "numer kontaktowy", "mobile"
            ),
            LeadFormField.VEHICLE_BRAND to listOf(
                "marka pojazdu", "marka samochodu", "marka auta", "marka", "brand", "make",
                "car brand", "car make"
            ),
            LeadFormField.VEHICLE_MODEL to listOf(
                "model pojazdu", "model samochodu", "model auta", "model", "car model"
            ),
            LeadFormField.VEHICLE_YEAR to listOf(
                "rok produkcji", "rocznik", "year", "year of production", "rok"
            ),
            LeadFormField.SERVICE to listOf(
                "zakres uslug", "rodzaj uslugi", "wybierz usluge", "interesujaca usluga",
                "usluga", "uslugi", "service", "services", "zainteresowanie", "pakiet"
            ),
            LeadFormField.COMPANY to listOf("firma", "nazwa firmy", "company", "company name"),
            LeadFormField.NAME to listOf(
                "imie i nazwisko", "imie inazwisko", "nazwisko", "imie", "twoje imie",
                "your name", "full name", "name", "first name", "last name", "dane kontaktowe"
            ),
            LeadFormField.MESSAGE to listOf(
                "wiadomosc", "tresc wiadomosci", "tresc", "opis", "opis zlecenia", "komentarz",
                "uwagi", "pytanie", "message", "your message", "comment", "comments", "notes",
                "how can we help", "w czym mozemy pomoc", "szczegoly"
            ),
            LeadFormField.CONSENT to listOf(
                "zgoda", "rodo", "akceptacja", "consent", "privacy", "polityka prywatnosci"
            )
        )

        /** Pola wtyczek i marketingu: identyfikatory, UTM-y, tokeny, znaczniki czasu. */
        val NOISE = listOf(
            "utm", "gclid", "fbclid", "form id", "formid", "form name", "formname",
            "submission id", "submissionid", "response id", "responseid", "respondent",
            "event id", "eventid", "event type", "eventtype", "created at", "createdat",
            "page url", "pageurl", "referrer", "user agent", "useragent", "remote ip",
            "ip", "token", "nonce", "recaptcha", "captcha", "hidden", "post id", "postid",
            "queried id", "g recaptcha response", "wpcf7", "cf7", "timestamp"
        )
    }
}
