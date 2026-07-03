package pl.detailing.crm.leads.quotereply

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wyodrębnia charakter pisma (styleGuide) z zaakceptowanych przykładów ofert danego studia.
 *
 * Wynik jest cache'owany per studio i unieważniany po sygnaturze zbioru przykładów
 * (id + updatedAt każdego przykładu). Dzięki temu kosztowna analiza LLM uruchamia się
 * tylko wtedy, gdy przykłady faktycznie się zmienią — a nie przy każdym generowaniu oferty.
 */
@Component
class QuoteStyleAnalyzer(
    @Qualifier("quoteStyleChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CachedStyle(val signature: String, val styleGuide: String)

    // Jeden wpis na studio — trzymamy tylko najnowszą wersję, więc pamięć jest ograniczona.
    private val cache = ConcurrentHashMap<UUID, CachedStyle>()

    /**
     * Zwraca wytyczne stylu wyprowadzone z przykładów lub null, gdy przykładów brak
     * albo nie udało się wykryć żadnego wyraźnego wzorca.
     */
    suspend fun deriveStyleGuide(studioId: UUID, examples: List<QuoteReplyExampleEntity>): String? {
        if (examples.isEmpty()) return null

        val signature = signatureOf(examples)
        cache[studioId]?.let { if (it.signature == signature) return it.styleGuide.ifBlank { null } }

        val styleGuide = analyze(examples)
        cache[studioId] = CachedStyle(signature, styleGuide.orEmpty())
        log.info("[QUOTE_STYLE] Derived style guide for studioId={} from {} examples (chars={})",
            studioId, examples.size, styleGuide?.length ?: 0)
        return styleGuide?.ifBlank { null }
    }

    private suspend fun analyze(examples: List<QuoteReplyExampleEntity>): String? =
        withContext(Dispatchers.IO) {
            val prompt = buildString {
                appendLine("Zaakceptowane odpowiedzi ofertowe studia (${examples.size}):")
                appendLine()
                examples.forEachIndexed { idx, ex ->
                    appendLine("### Przykład ${idx + 1}")
                    appendLine("Temat: ${ex.title}")
                    appendLine("Treść:")
                    appendLine(ex.content.take(2000))
                    appendLine()
                }
            }

            try {
                chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(StyleAnalysisResponse::class.java)
                    ?.styleGuide
                    ?.trim()
            } catch (e: Exception) {
                log.warn("[QUOTE_STYLE] Style analysis failed, falling back to raw examples only: {}", e.message)
                null
            }
        }

    private fun signatureOf(examples: List<QuoteReplyExampleEntity>): String =
        examples.asSequence()
            .map { "${it.id}:${it.updatedAt.toEpochMilli()}" }
            .sorted()
            .joinToString("|")

    internal data class StyleAnalysisResponse(
        @JsonProperty("styleGuide")
        val styleGuide: String?
    )
}
