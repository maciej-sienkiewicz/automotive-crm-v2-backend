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
     * Gdy LLM zwróci niekompletny raport (mniej werdyktów niż reguł), brakujące pozycje
     * są uzupełniane jako spełnione — weryfikator, który się nie wypowiedział, nie może
     * blokować posta w nieskończoność.
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
            |- Dla KAŻDEJ reguły zwróć osobny werdykt z jej numerem i treścią.
            |- passed = true, gdy tekst regułę spełnia; passed = false, gdy ją łamie.
            |- Przy passed = false podaj w polu violation krótkie, konkretne wskazanie,
            |  CO w tekście łamie regułę (np. "3 wykrzykniki w pierwszym akapicie").
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
        logger.info(
            "Verification done: {} rules, {} violations",
            rules.size, normalized.verdicts.count { !it.passed }
        )
        return normalized
    }

    /**
     * Dopasowuje raport LLM do listy reguł: uzupełnia braki, przycina nadmiar
     * i przywraca oryginalną treść reguły (model bywa kreatywny przy przepisywaniu).
     */
    private fun normalize(report: VerificationReport, rules: List<String>): VerificationReport {
        val byIndex = report.verdicts.associateBy { it.ruleIndex }
        val verdicts = rules.mapIndexed { i, ruleText ->
            val verdict = byIndex[i + 1]
            RuleVerdict(
                ruleIndex = i + 1,
                ruleText = ruleText,
                passed = verdict?.passed ?: true,
                violation = if (verdict?.passed == false) verdict.violation else null
            )
        }
        return VerificationReport(verdicts)
    }
}
