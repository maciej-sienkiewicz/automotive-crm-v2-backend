package pl.detailing.crm.instagram.ai.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.model.RuleVerdict
import pl.detailing.crm.instagram.ai.model.VerificationReport

/**
 * Weryfikator reguł stylistycznych — OSOBNE wywołanie LLM, niezależne od generatora.
 *
 * Dostaje WYŁĄCZNIE treść draftu i ponumerowaną listę reguł. Celowo nie widzi promptu
 * generatora: model, który zna własne założenia, ocenia raczej intencję niż tekst
 * i potrafi uznać za spełnioną regułę, której draft nie spełnia.
 *
 * Temperatura 0.0 ustawiana PER ŻĄDANIE (globalna konfiguracja `spring.ai.*` zostaje
 * nietknięta — generator dalej pracuje na swoich 0.7): ocena „spełnia / nie spełnia"
 * ma być powtarzalna, nie kreatywna.
 */
@Service
class InstagramPostVerifierService(
    @Qualifier("instagramChatClient") private val chatClient: ChatClient
) {
    private val logger = LoggerFactory.getLogger(InstagramPostVerifierService::class.java)

    companion object {
        /** Ocena binarna musi być powtarzalna — stąd 0.0, a nie temperatura generatora. */
        private const val VERIFIER_TEMPERATURE = 0.0
    }

    /**
     * Sprawdza draft względem [rules] i zwraca werdykt dla KAŻDEJ reguły.
     *
     * Wynik modelu jest przypisywany do reguł po TREŚCI reguły, a nie po numerze —
     * patrz [attribute]. Numer bywa przez model liczony od zera albo pomijany, a wtedy
     * naruszenie jednej reguły lądowało na innej: post bez ani jednego emoji wracał
     * z werdyktem „łamie regułę «bez emoji»", bo dostał ocenę sąsiedniej pozycji listy.
     */
    suspend fun verify(draft: String, rules: List<String>): VerificationReport {
        if (rules.isEmpty()) return VerificationReport(emptyList())

        val numberedRules = rules.mapIndexed { i, rule -> "${i + 1}. $rule" }.joinToString("\n")

        val systemMessage = """
            |Jesteś surowym audytorem tekstu. Twoim JEDYNYM zadaniem jest sprawdzenie,
            |czy podany post na Instagram spełnia każdą z reguł stylistycznych.
            |
            |ZASADY OCENY:
            |- Oceniaj WYŁĄCZNIE to, co widzisz w tekście posta. Nie domyślaj się intencji autora.
            |- Dla KAŻDEJ reguły zwróć osobny werdykt. W polu ruleText PRZEPISZ treść reguły
            |  dokładnie tak, jak ją dostałeś, a w ruleIndex podaj jej numer z listy
            |  (numeracja od 1, tak jak poniżej).
            |- passed = true, gdy tekst regułę spełnia; passed = false, gdy ją łamie.
            |- passed = false wymaga DOWODU: w polu violation zacytuj konkretny fragment
            |  posta, który łamie regułę (np. "emoji 🔥 w pierwszej linii", "3 wykrzykniki
            |  w akapicie o cenie"). Nie potrafisz wskazać fragmentu — reguła jest spełniona.
            |- Nie zgaduj i nie oceniaj „na wszelki wypadek": brak dowodu to passed = true.
            |- Przy passed = true pole violation zostaw puste.
            |- Nie proponuj poprawek i nie przepisuj tekstu — tylko oceniaj.
        """.trimMargin()

        val userMessage = """
            |=== REGUŁY ===
            |$numberedRules
            |
            |=== POST DO OCENY ===
            |$draft
        """.trimMargin()

        val report = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .options(OpenAiChatOptions.builder().temperature(VERIFIER_TEMPERATURE).build())
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(VerificationReport::class.java)
        } ?: VerificationReport(emptyList())

        val normalized = normalize(report, rules)
        val violations = normalized.verdicts.filter { !it.passed }
        if (violations.isEmpty()) {
            logger.info("Verification done: {} rules, no violations", rules.size)
        } else {
            // Same liczby nie dało się zdiagnozować: po tym logu widać, KTÓRA reguła
            // i CZYM według modelu została złamana.
            logger.info(
                "Verification done: {} rules, {} violation(s): {}",
                rules.size, violations.size,
                violations.joinToString(" | ") { "«${it.ruleText}» → ${it.violation}" }
            )
        }
        return normalized
    }

    /**
     * Dopasowuje raport LLM do listy reguł i przywraca oryginalną treść reguły.
     *
     * Zasada nadrzędna: NIE WYMYŚLAMY naruszeń. Reguła jest niespełniona tylko wtedy,
     * gdy da się jednoznacznie wskazać werdykt modelu, ten werdykt mówi `passed = false`
     * ORAZ niesie uzasadnienie. Wątpliwość działa na korzyść tekstu — fałszywe naruszenie
     * uruchamia korektę, która psuje poprawny post, a po trzech rundach oznacza go jako
     * niezgodny ze stylem, choć nic mu nie brakuje.
     */
    private fun normalize(report: VerificationReport, rules: List<String>): VerificationReport {
        val attributed = attribute(report.verdicts, rules)

        val verdicts = rules.mapIndexed { i, ruleText ->
            val verdict = attributed[i]
            val violation = verdict?.violation?.trim()?.takeIf { it.isNotEmpty() }
            // Naruszenie bez wskazania fragmentu traktujemy jak brak naruszenia: model,
            // który nie potrafi pokazać, co łamie regułę, zwykle jej nie znalazł.
            val failed = verdict?.passed == false && violation != null
            RuleVerdict(
                ruleIndex = i + 1,
                ruleText = ruleText,
                passed = !failed,
                violation = if (failed) violation else null
            )
        }
        return VerificationReport(verdicts)
    }

    /**
     * Zwraca werdykt modelu dla każdej reguły (po pozycji na liście reguł) albo null,
     * gdy żadnego nie da się przypisać.
     *
     * Kolejność prób:
     *  1. po TREŚCI reguły — model przepisuje ją do `ruleText` i to jedyny nośnik,
     *     który nie zależy od tego, jak model numeruje pozycje;
     *  2. po numerze, z wykryciem bazy numeracji (0 albo 1) — model bywa konsekwentny,
     *     tylko liczy od zera;
     *  3. po kolejności — ale TYLKO gdy model nie dał ani użytecznych treści, ani
     *     użytecznych numerów, a werdyktów jest dokładnie tyle co reguł. Gdy któraś
     *     z dwóch pierwszych dróg zadziałała, pozycja na liście niczego nie dowodzi:
     *     dwa sprzeczne werdykty dla tej samej reguły odrzuciliśmy właśnie po to,
     *     żeby nie wskrzeszać ich kolejnością.
     *
     * Werdyktów, których nie da się przypisać żadną z tych dróg, nie używamy w ogóle.
     */
    private fun attribute(verdicts: List<RuleVerdict>, rules: List<String>): List<RuleVerdict?> {
        if (verdicts.isEmpty()) return rules.map { null }

        val byText = verdicts
            .filter { it.ruleText.isNotBlank() }
            .groupBy { normalizeText(it.ruleText) }
            // Ta sama reguła oceniona dwa razy jest niejednoznaczna — pomijamy.
            .filterValues { it.size == 1 }
            .mapValues { (_, matches) -> matches.first() }

        val indices = verdicts.map { it.ruleIndex }
        val zeroBased = indices.isNotEmpty() &&
            indices.min() == 0 &&
            indices.max() == rules.size - 1 &&
            indices.toSet().size == indices.size
        val byIndex = verdicts
            .groupBy { if (zeroBased) it.ruleIndex else it.ruleIndex - 1 }
            .filterValues { it.size == 1 }
            .mapValues { (_, matches) -> matches.first() }

        val positional = verdicts.size == rules.size && byText.isEmpty() && byIndex.isEmpty()

        var textMatches = 0
        val result = rules.mapIndexed { i, ruleText ->
            val match = byText[normalizeText(ruleText)]
            if (match != null) textMatches++
            match ?: byIndex[i] ?: if (positional) verdicts[i] else null
        }

        if (textMatches < rules.size) {
            logger.debug(
                "Verifier echoed {} of {} rules verbatim; the rest matched by index/position",
                textMatches, rules.size
            )
        }
        return result
    }

    private fun normalizeText(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', ',', ';', ':')
}
