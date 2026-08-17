package pl.detailing.crm.shared.numbering

import java.time.LocalDate

/**
 * Parses and renders a document-numbering template made of literal text and a small
 * set of placeholders: `{YYYY}`, `{YY}`, `{MM}`, `{DD}`, `{SEQ}`. Exactly one `{SEQ}`
 * is required — it marks where the zero-padded running sequence goes.
 *
 * Example: template `"VIS-{YYYY}-{SEQ}"` with sequenceLength 5 renders `VIS-2026-00072`.
 *
 * Sequence reset behavior falls naturally out of which date tokens are used, instead
 * of a separate "reset period" setting: a template containing `{MM}` resets every
 * month because the rendered prefix/suffix changes with the month; one with only
 * `{YYYY}` resets yearly; one with no date token never resets (a single studio-wide
 * running count, same idea as an invoice number).
 */
class NumberingTemplate(val template: String, val sequenceLength: Int) {

    init {
        require(sequenceLength in 1..10) { "Długość numeru porządkowego musi być między 1 a 10 cyfr" }
        val errors = validate(template)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    /** Full formatted number for a given date + sequence, e.g. "VIS-2026-00072". */
    fun render(date: LocalDate, sequence: Int): String {
        require(sequence >= 1) { "Numer porządkowy musi być dodatni" }
        return renderTokens(template, date).replace(SEQ_TOKEN, sequence.toString().padStart(sequenceLength, '0'))
    }

    /**
     * SQL `LIKE ... ESCAPE '\'` pattern matching every existing number from the same
     * period (i.e. sharing this date's rendered prefix/suffix), with literal `%`, `_`
     * and `\` in the template escaped so studio-authored text can't break the query.
     */
    fun likePattern(date: LocalDate): String {
        val (prefixTpl, suffixTpl) = splitOnSeq()
        return escapeLike(renderTokens(prefixTpl, date)) + "%" + escapeLike(renderTokens(suffixTpl, date))
    }

    /**
     * Extracts the sequence value from an existing number for this same period, or
     * null when the number's shape doesn't match (different period, foreign format
     * left over from a prior template change, etc.) — callers should ignore nulls
     * rather than treat them as errors.
     */
    fun extractSequence(existingNumber: String, date: LocalDate): Int? {
        val (prefixTpl, suffixTpl) = splitOnSeq()
        val prefix = renderTokens(prefixTpl, date)
        val suffix = renderTokens(suffixTpl, date)
        if (!existingNumber.startsWith(prefix) || !existingNumber.endsWith(suffix)) return null
        val middleEnd = existingNumber.length - suffix.length
        if (middleEnd < prefix.length) return null
        val middle = existingNumber.substring(prefix.length, middleEnd)
        if (middle.isEmpty()) return null
        return middle.toIntOrNull()
    }

    private fun splitOnSeq(): Pair<String, String> {
        val idx = template.indexOf(SEQ_TOKEN)
        return template.substring(0, idx) to template.substring(idx + SEQ_TOKEN.length)
    }

    companion object {
        private const val SEQ_TOKEN = "{SEQ}"
        private val KNOWN_TOKENS = setOf("YYYY", "YY", "MM", "DD", "SEQ")
        private val TOKEN_REGEX = Regex("\\{([A-Za-z]*)}")
        private const val MAX_LENGTH = 100

        /** Validates a template WITHOUT constructing an instance — used for form-level input checks. */
        fun validate(template: String): List<String> {
            val errors = mutableListOf<String>()
            if (template.isBlank()) {
                errors += "Format numeru nie może być pusty"
                return errors
            }
            if (template.length > MAX_LENGTH) errors += "Format numeru jest za długi (maks. $MAX_LENGTH znaków)"

            val seqCount = Regex(Regex.escape(SEQ_TOKEN)).findAll(template).count()
            if (seqCount != 1) errors += "Format musi zawierać dokładnie jeden znacznik {SEQ}"

            TOKEN_REGEX.findAll(template).forEach { match ->
                val name = match.groupValues[1]
                if (name !in KNOWN_TOKENS) errors += "Nieznany znacznik: {$name}"
            }
            return errors
        }

        private fun renderTokens(text: String, date: LocalDate): String = text
            .replace("{YYYY}", date.year.toString())
            .replace("{YY}", (date.year % 100).toString().padStart(2, '0'))
            .replace("{MM}", date.monthValue.toString().padStart(2, '0'))
            .replace("{DD}", date.dayOfMonth.toString().padStart(2, '0'))

        private fun escapeLike(s: String): String =
            s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
