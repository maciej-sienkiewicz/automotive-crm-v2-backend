package pl.detailing.crm.communication.rehearsal

import pl.detailing.crm.campaigns.application.SmsSegmentCalculator

enum class Severity { ERROR, WARNING }

data class Finding(val severity: Severity, val rule: String, val detail: String)

enum class RehearsalChannel { SMS, EMAIL }

/**
 * Checks a *rendered* message — the string that would leave for the provider — for
 * everything a customer must never see.
 *
 * The renderer already refuses unknown `{{tokens}}`; this looks for what its regex cannot
 * see: a token with a Polish letter (`{{imię}}`), a single or unbalanced brace, HTML in a
 * text/plain e-mail, and — the most valuable rule — a sample value that should be in the
 * text and is not (a reminder that never mentions the hour).
 */
object RenderedMessageValidator {

    /**
     * Any brace at all. A customer never needs to see one: a lone `}` after `{{dokument}}}`
     * is as much a template accident as `{{imie`. Longer alternatives come first so the
     * reported snippet shows the whole token rather than its first character.
     */
    private val ORPHAN_BRACES = Regex("""\{\{[^}]*}}|\{[^{}\n]{1,40}}|[{}]""")
    private val DIACRITIC_PLACEHOLDER = Regex("""\{\{\s*[^}]*[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ][^}]*}}""")
    private val TEMPLATE_LEFTOVERS = Regex("""\$\{|%s|%d|\[\[|]]|\bnull\b|\bundefined\b|\bNaN\b""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""</?[a-z][a-z0-9]*(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val DATE_WITH_YEAR = Regex("""\b\d{2}\.\d{2}\.\d{4}\b""")

    private val REQUIRED_NON_EMPTY = setOf("imie", "data", "godzina", "link")

    fun validate(
        channel: RehearsalChannel,
        subject: String?,
        body: String,
        expectedValues: Map<String, String>
    ): List<Finding> = buildList {
        val text = listOfNotNull(subject, body).joinToString("\n")

        fun error(rule: String, detail: String) = add(Finding(Severity.ERROR, rule, detail))
        fun warn(rule: String, detail: String) = add(Finding(Severity.WARNING, rule, detail))

        // A. Orphaned or malformed placeholders — a hard stop.
        DIACRITIC_PLACEHOLDER.findAll(text).forEach { error("placeholder-with-diacritics", it.value) }
        ORPHAN_BRACES.findAll(text).forEach { error("orphan-braces", it.value) }
        TEMPLATE_LEFTOVERS.findAll(text).forEach { error("template-leftover", it.value) }

        // B. Every substituted value must be visible in the result.
        expectedValues.filterValues { it.isNotBlank() }.forEach { (key, value) ->
            if (value !in text) error("value-missing", "{{$key}} = \"$value\" nie występuje w treści")
        }

        // C. Values that may never be blank.
        REQUIRED_NON_EMPTY.intersect(expectedValues.keys)
            .filter { expectedValues[it].isNullOrBlank() }
            .forEach { error("required-empty", "{{$it}}") }

        // D. Link shape (liveness is checked by the person opening it on the phone).
        expectedValues["link"]?.let { url ->
            if (!url.startsWith("https://") || ' ' in url) error("link-format", url)
        }

        // E. A date without a year is a date the customer will misread in January.
        if ("data" in expectedValues && !DATE_WITH_YEAR.containsMatchIn(text)) error("date-without-year", "")

        // F. Channel-specific.
        when (channel) {
            RehearsalChannel.SMS -> {
                val segments = SmsSegmentCalculator.segments(body)
                when {
                    segments > 3 -> error("sms-too-long", "$segments segmentów")
                    segments > 2 -> warn("sms-long", "$segments segmentów")
                }
                if (body != body.trim() || "  " in body) warn("whitespace", "podwójne spacje lub białe znaki na brzegach")
                if (HTML_TAG.containsMatchIn(body)) error("html-in-sms", HTML_TAG.find(body)!!.value)
                if (body.isBlank()) error("body-empty", "")
            }
            RehearsalChannel.EMAIL -> {
                val s = subject.orEmpty()
                if (s.isBlank()) error("subject-empty", "")
                if ('\n' in s || '\r' in s) error("subject-multiline", s)
                if (s.length > 78) warn("subject-long", "${s.length} znaków")
                if (HTML_TAG.containsMatchIn(body)) error("html-in-plaintext-email", HTML_TAG.find(body)!!.value)
                if (body.isBlank()) error("body-empty", "")
                else if (body.length < 40) warn("body-suspiciously-short", "${body.length} znaków")
            }
        }
    }
}
