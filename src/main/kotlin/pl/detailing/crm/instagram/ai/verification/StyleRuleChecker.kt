package pl.detailing.crm.instagram.ai.verification

import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.model.RuleVerdict

/**
 * Deterministyczne sprawdzanie reguł, które da się POLICZYĆ.
 *
 * Model językowy nie umie liczyć i nie ma powodu, żeby go o to prosić: przy trzech
 * myślnikach w tekście potrafił zwrócić „4 bullet pointy", a przy poście bez ani
 * jednego emoji — „brak emoji w poście" jako uzasadnienie NARUSZENIA reguły „bez emoji".
 * Oba werdykty uruchamiały korektę poprawnego tekstu i kończyły się oznaczeniem posta
 * jako niezgodnego ze stylem.
 *
 * Reguły policzalne (emoji, liczba punktów listy, hashtagów, wykrzykników, znaków)
 * rozstrzyga więc kod — dokładnie i powtarzalnie. Do modelu trafiają wyłącznie reguły
 * jakościowe („pisz ciepłym tonem", „nie obiecuj efektów, których nie ma"), których
 * policzyć się nie da.
 *
 * Zasada ostrożności: wzorzec musi być JEDNOZNACZNY. Reguły, których nie rozumiemy na
 * pewno, zostawiamy modelowi — zgadywanie intencji regułą-wyrażeniem regularnym
 * kończyłoby się tym samym, co zgadywanie przez model, tylko bez jego elastyczności.
 */
@Service
class StyleRuleChecker {

    companion object {
        /** Piktogramy Unicode: emoji, symbole pogodowe, dingbaty. */
        private val EMOJI = Regex("[\\p{So}\\p{Cs}]|[\\x{1F000}-\\x{1FAFF}]|[\\x{2600}-\\x{27BF}]|\\x{FE0F}")

        /** Wiersz listy: „- ", „• ", „* ", „1. ", „1) " oraz warianty z myślnikiem długim. */
        private val BULLET_LINE = Regex("^\\s*(?:[-–—*•‣▪▫◦]|\\d+[.)])\\s+\\S", RegexOption.MULTILINE)

        private val HASHTAG = Regex("(?<!\\w)#\\p{L}[\\p{L}\\p{N}_]*")

        /** Liczba w treści reguły — bez niej reguła „ilościowa" nie ma progu. */
        private val NUMBER = Regex("\\d+")

        private val NEGATIONS = listOf("bez ", "nie ", "zero ", "brak ", "zakaz", "unikaj", "żadn")
        private val AT_MOST = listOf("max", "maksymalnie", "maks", "nie więcej", "najwyżej", "do ")
        private val AT_LEAST = listOf("min", "minimum", "co najmniej", "przynajmniej", "nie mniej")
    }

    /** Co dokładnie liczymy w tekście posta. */
    private enum class Countable(val label: String, val plural: String) {
        BULLETS("punkt listy", "punktów listy"),
        HASHTAGS("hashtag", "hashtagów"),
        EXCLAMATIONS("wykrzyknik", "wykrzykników"),
        CHARACTERS("znak", "znaków"),
        WORDS("słowo", "słów")
    }

    private enum class Comparison { AT_MOST, AT_LEAST, EXACTLY }

    /**
     * Werdykt dla reguły albo null, gdy reguła nie jest policzalna i musi ją ocenić model.
     */
    fun check(rule: String, text: String, ruleIndex: Int): RuleVerdict? {
        val normalized = rule.lowercase()

        emojiVerdict(rule, normalized, text, ruleIndex)?.let { return it }
        return countVerdict(rule, normalized, text, ruleIndex)
    }

    // ── Emoji ─────────────────────────────────────────────────────────────────

    private fun emojiVerdict(rule: String, normalized: String, text: String, ruleIndex: Int): RuleVerdict? {
        if (!normalized.contains("emoji") && !normalized.contains("emotikon")) return null

        val found = EMOJI.findAll(text).map { it.value }.filter { it.isNotBlank() }.toList()
        val number = NUMBER.find(normalized)?.value?.toIntOrNull()

        // „bez emoji", „nie używaj emoji", „zero emoji" — dowolne emoji łamie regułę.
        if (number == null && isNegated(normalized)) {
            return verdict(
                rule, ruleIndex, passed = found.isEmpty(),
                violation = "emoji w treści: ${found.take(5).joinToString(" ")}"
            )
        }
        // „maksymalnie 2 emoji", „co najmniej 1 emoji".
        if (number != null) {
            return compare(rule, ruleIndex, found.size, number, comparisonOf(normalized), "emoji", "emoji")
        }
        return null
    }

    // ── Liczby ────────────────────────────────────────────────────────────────

    private fun countVerdict(rule: String, normalized: String, text: String, ruleIndex: Int): RuleVerdict? {
        val countable = when {
            normalized.contains("bullet") || normalized.contains("punkt") ||
                normalized.contains("wypunktow") || normalized.contains("myślnik") -> Countable.BULLETS
            normalized.contains("hashtag") -> Countable.HASHTAGS
            normalized.contains("wykrzyknik") -> Countable.EXCLAMATIONS
            normalized.contains("znak") -> Countable.CHARACTERS
            normalized.contains("słow") || normalized.contains("wyraz") -> Countable.WORDS
            else -> return null
        }

        val number = NUMBER.find(normalized)?.value?.toIntOrNull()

        // Reguła bez liczby, ale z zakazem: „bez hashtagów", „nie używaj wykrzykników".
        if (number == null) {
            if (!isNegated(normalized)) return null
            val actual = count(countable, text)
            return compare(rule, ruleIndex, actual, 0, Comparison.AT_MOST, countable.label, countable.plural)
        }

        val actual = count(countable, text)
        return compare(rule, ruleIndex, actual, number, comparisonOf(normalized), countable.label, countable.plural)
    }

    private fun count(countable: Countable, text: String): Int = when (countable) {
        Countable.BULLETS -> BULLET_LINE.findAll(text).count()
        Countable.HASHTAGS -> HASHTAG.findAll(text).count()
        Countable.EXCLAMATIONS -> text.count { it == '!' }
        Countable.CHARACTERS -> text.length
        Countable.WORDS -> text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    /**
     * Bez wyraźnego „maksymalnie" albo „co najmniej" liczbę czytamy jako DOKŁADNIE tyle:
     * „3 bullet pointy" to prośba o trzy punkty, a nie o cokolwiek do trzech.
     */
    private fun comparisonOf(normalized: String): Comparison = when {
        AT_MOST.any { normalized.contains(it) } -> Comparison.AT_MOST
        AT_LEAST.any { normalized.contains(it) } -> Comparison.AT_LEAST
        else -> Comparison.EXACTLY
    }

    private fun isNegated(normalized: String): Boolean = NEGATIONS.any { normalized.contains(it) }

    private fun compare(
        rule: String,
        ruleIndex: Int,
        actual: Int,
        expected: Int,
        comparison: Comparison,
        label: String,
        plural: String
    ): RuleVerdict {
        val passed = when (comparison) {
            Comparison.AT_MOST -> actual <= expected
            Comparison.AT_LEAST -> actual >= expected
            Comparison.EXACTLY -> actual == expected
        }
        val expectation = when (comparison) {
            Comparison.AT_MOST -> "najwyżej $expected"
            Comparison.AT_LEAST -> "co najmniej $expected"
            Comparison.EXACTLY -> "dokładnie $expected"
        }
        return verdict(
            rule, ruleIndex, passed,
            violation = "w tekście: $actual ${if (actual == 1) label else plural} (oczekiwano $expectation)"
        )
    }

    private fun verdict(rule: String, ruleIndex: Int, passed: Boolean, violation: String) = RuleVerdict(
        ruleIndex = ruleIndex,
        ruleText = rule,
        passed = passed,
        violation = if (passed) null else violation
    )
}
