package pl.detailing.crm.communication.template

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Thrown when a template references a placeholder the caller has no value for.
 *
 * Sending the raw `{{...}}` token to a customer is never acceptable, so the
 * caller must abort the delivery rather than degrade to something else.
 */
class UnresolvedPlaceholderException(val placeholders: Set<String>) : RuntimeException(
    "Szablon zawiera nieznane zmienne: ${placeholders.joinToString(", ") { "{{$it}}" }}"
)

/**
 * The single placeholder engine for every outbound message.
 *
 * Two rules define its behaviour:
 *  - a placeholder that [MessageTemplateKind] does not declare is a hard error,
 *    both when the studio saves the template and when we render it;
 *  - nothing is ever substituted for a missing template — an empty or disabled
 *    template means the message is not sent at all. There is no built-in body.
 */
@Component
class MessageTemplateRenderer {

    fun render(template: String, values: Map<String, String>): String {
        // Checked against the template, not the output: a substituted value that happens
        // to contain braces is customer data, not a placeholder to resolve.
        val unresolved = placeholdersIn(template) - values.keys
        if (unresolved.isNotEmpty()) throw UnresolvedPlaceholderException(unresolved)

        val prepared = if (values[TIME_KEY].isNullOrBlank()) TIME_PHRASE.replace(template, "") else template
        val rendered = PLACEHOLDER.replace(prepared) { match -> values.getValue(match.groupValues[1]) }

        // Empty substitutions (no license plate, no last service) would otherwise
        // leave double spaces and trailing blanks in the delivered text.
        return rendered
            .replace(HORIZONTAL_RUN, " ")
            .lines().joinToString("\n") { it.trimEnd() }
            .trim()
    }

    fun placeholdersIn(template: String): Set<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    companion object {
        private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("pl"))
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("pl"))

        private const val TIME_KEY = "godzina"

        /**
         * {{data}} / {{godzina}} — shared by every message that references a moment in time.
         *
         * Rezerwacja całodniowa nie ma godziny: jej `startDateTime` to północ, a „o godz. 00:00"
         * w SMS-ie do klienta to absurd. Dla [allDay] {{godzina}} jest pusta, a [render] wycina
         * razem z nią zwrot, który ją zapowiadał (patrz [TIME_PHRASE]) — studio nie musi
         * utrzymywać dwóch wersji szablonu.
         */
        fun scheduleValues(moment: Instant, allDay: Boolean = false): Map<String, String> {
            val zoned = moment.atZone(WARSAW)
            return mapOf(
                "data" to DATE.format(zoned),
                TIME_KEY to if (allDay) "" else TIME.format(zoned)
            )
        }

        /**
         * Zwrot zapowiadający godzinę, usuwany razem z pustym {{godzina}}:
         * „o godz. {{godzina}}", „o godzinie {{godzina}}", „godz. {{godzina}}", „o {{godzina}}",
         * także z przecinkiem przed („{{data}}, godz. {{godzina}}"). Przy pustej godzinie
         * „dnia 09.09.2026 o godz. {{godzina}}." staje się „dnia 09.09.2026.", a nie
         * „dnia 09.09.2026 o godz. ." Sam placeholder bez zwrotu też znika (jak każda pusta
         * wartość); spacje po nim sprząta [HORIZONTAL_RUN].
         */
        private val TIME_PHRASE = Regex(
            """[ \t]*(?:,[ \t]*)?(?:\bo[ \t]+)?(?:godz(?:\.|inie|inę|ina)?[ \t]*)?\{\{\s*godzina\s*}}""",
            RegexOption.IGNORE_CASE
        )

        private val PLACEHOLDER = Regex("""\{\{\s*([a-zA-Z0-9_]+)\s*}}""")
        private val HORIZONTAL_RUN = Regex("""[ \t]{2,}""")
    }
}
