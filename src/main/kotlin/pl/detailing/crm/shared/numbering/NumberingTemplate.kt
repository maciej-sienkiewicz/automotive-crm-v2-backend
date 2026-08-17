package pl.detailing.crm.shared.numbering

import java.security.SecureRandom
import java.time.LocalDate

/**
 * Parses and renders a document-numbering template made of literal text and a small
 * set of placeholders: `{YYYY}`, `{YY}`, `{MM}`, `{DD}`, and exactly one of `{SEQ}` or
 * `{RAND}` — the two are mutually exclusive, since combining them would still leak the
 * running count through the visible `{SEQ}` part.
 *
 * - `{SEQ}` — zero-padded running sequence, e.g. `"VIS-{YYYY}-{SEQ}"` → `VIS-2026-00072`.
 *   Reset behavior falls naturally out of which date tokens are used, instead of a
 *   separate "reset period" setting: a template containing `{MM}` resets every month
 *   because the rendered prefix/suffix changes with the month; one with only `{YYYY}`
 *   resets yearly; one with no date token never resets (a single studio-wide running
 *   count, same idea as an invoice number).
 * - `{RAND}` — a fresh cryptographically-random digit string per number, for studios
 *   that don't want a visit number to reveal how many visits they've done. There is
 *   nothing to reset or query for: each call draws independently, and the rare
 *   collision is handled the same way the sequential path already handles a lost
 *   race — retry with a freshly generated number against the unique DB index.
 */
class NumberingTemplate(val template: String, val sequenceLength: Int, val randomLength: Int) {

    enum class Kind { SEQUENTIAL, RANDOM }

    val kind: Kind = if (template.contains(SEQ_TOKEN)) Kind.SEQUENTIAL else Kind.RANDOM

    init {
        require(sequenceLength in 1..10) { "Długość numeru porządkowego musi być między 1 a 10 cyfr" }
        require(randomLength in 1..12) { "Długość numeru losowego musi być między 1 a 12 cyfr" }
        val errors = validate(template)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    /** Full formatted number for a given date + sequence, e.g. "VIS-2026-00072". SEQUENTIAL templates only. */
    fun render(date: LocalDate, sequence: Int): String {
        require(kind == Kind.SEQUENTIAL) { "render(date, sequence) dotyczy tylko formatów z {SEQ}" }
        require(sequence >= 1) { "Numer porządkowy musi być dodatni" }
        return renderTokens(template, date).replace(SEQ_TOKEN, sequence.toString().padStart(sequenceLength, '0'))
    }

    /**
     * Full formatted number with a freshly drawn random digit string, e.g.
     * "VIS-2026-704812". RANDOM templates only — every call returns a different value,
     * by design, so it is not idempotent and must not be cached.
     */
    fun renderRandom(date: LocalDate): String {
        require(kind == Kind.RANDOM) { "renderRandom(date) dotyczy tylko formatów z {RAND}" }
        val digits = buildString { repeat(randomLength) { append(SECURE_RANDOM.nextInt(10)) } }
        return renderTokens(template, date).replace(RAND_TOKEN, digits)
    }

    /**
     * SQL `LIKE ... ESCAPE '\'` pattern matching every existing number from the same
     * period (i.e. sharing this date's rendered prefix/suffix), with literal `%`, `_`
     * and `\` in the template escaped so studio-authored text can't break the query.
     * SEQUENTIAL templates only — a RANDOM template has no "same period" concept to
     * query for, since it never derives a next value from what already exists.
     */
    fun likePattern(date: LocalDate): String {
        require(kind == Kind.SEQUENTIAL) { "likePattern(date) dotyczy tylko formatów z {SEQ}" }
        val (prefixTpl, suffixTpl) = splitOn(SEQ_TOKEN)
        return escapeLike(renderTokens(prefixTpl, date)) + "%" + escapeLike(renderTokens(suffixTpl, date))
    }

    /**
     * Extracts the sequence value from an existing number for this same period, or
     * null when the number's shape doesn't match (different period, foreign format
     * left over from a prior template change, etc.) — callers should ignore nulls
     * rather than treat them as errors. SEQUENTIAL templates only.
     */
    fun extractSequence(existingNumber: String, date: LocalDate): Int? {
        require(kind == Kind.SEQUENTIAL) { "extractSequence(...) dotyczy tylko formatów z {SEQ}" }
        val (prefixTpl, suffixTpl) = splitOn(SEQ_TOKEN)
        val prefix = renderTokens(prefixTpl, date)
        val suffix = renderTokens(suffixTpl, date)
        if (!existingNumber.startsWith(prefix) || !existingNumber.endsWith(suffix)) return null
        val middleEnd = existingNumber.length - suffix.length
        if (middleEnd < prefix.length) return null
        val middle = existingNumber.substring(prefix.length, middleEnd)
        if (middle.isEmpty()) return null
        return middle.toIntOrNull()
    }

    private fun splitOn(token: String): Pair<String, String> {
        val idx = template.indexOf(token)
        return template.substring(0, idx) to template.substring(idx + token.length)
    }

    companion object {
        private const val SEQ_TOKEN = "{SEQ}"
        private const val RAND_TOKEN = "{RAND}"
        private val KNOWN_TOKENS = setOf("YYYY", "YY", "MM", "DD", "SEQ", "RAND")
        private val TOKEN_REGEX = Regex("\\{([A-Za-z]*)}")
        private const val MAX_LENGTH = 100
        private val SECURE_RANDOM = SecureRandom()

        /** Validates a template WITHOUT constructing an instance — used for form-level input checks. */
        fun validate(template: String): List<String> {
            val errors = mutableListOf<String>()
            if (template.isBlank()) {
                errors += "Format numeru nie może być pusty"
                return errors
            }
            if (template.length > MAX_LENGTH) errors += "Format numeru jest za długi (maks. $MAX_LENGTH znaków)"

            val seqCount = Regex(Regex.escape(SEQ_TOKEN)).findAll(template).count()
            val randCount = Regex(Regex.escape(RAND_TOKEN)).findAll(template).count()
            if (seqCount + randCount != 1) {
                errors += "Format musi zawierać dokładnie jeden znacznik {SEQ} albo {RAND} (nie oba naraz)"
            }

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
