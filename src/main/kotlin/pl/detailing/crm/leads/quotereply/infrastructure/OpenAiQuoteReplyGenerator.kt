package pl.detailing.crm.leads.quotereply.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyGenerator
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyInput
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyResult

@Service
class OpenAiQuoteReplyGenerator(
    @Qualifier("quoteReplyChatClient") private val chatClient: ChatClient
) : QuoteReplyGenerator {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun generate(input: QuoteReplyInput): QuoteReplyResult = withContext(Dispatchers.IO) {
        val response = try {
            chatClient.prompt()
                .user(buildUserPrompt(input))
                .call()
                .entity(QuoteReplyLlmResponse::class.java)
        } catch (e: Exception) {
            log.error("[QUOTE_REPLY] LLM call failed: {}", e.message)
            throw e
        }

        if (response == null) {
            log.warn("[QUOTE_REPLY] LLM returned null response")
            return@withContext QuoteReplyResult(title = "", reply = "")
        }

        log.info("[QUOTE_REPLY] Generated reply for customer='{}'", input.customerName)

        QuoteReplyResult(title = response.title, reply = response.reply)
    }

    private fun buildUserPrompt(input: QuoteReplyInput): String = buildString {
        input.customerName?.let { appendLine("Imię klienta: $it") }

        if (input.vehicleBrand != null || input.vehicleModel != null) {
            append("Pojazd: ")
            appendLine("${input.vehicleBrand ?: ""} ${input.vehicleModel ?: ""}".trim())
        }

        if (input.initialMessage != null) {
            appendLine()
            appendLine("Treść zapytania klienta:")
            appendLine("---")
            appendLine(input.initialMessage.take(2000))
            appendLine("---")
        }

        appendLine()
        appendLine("Przygotowana wycena (${input.quoteItems.size} pozycji):")
        input.quoteItems.forEach { item ->
            val grossFormatted = "%.2f zł".format(item.priceGross / 100.0)
            appendLine("  - ${item.serviceName}: $grossFormatted brutto (VAT ${if (item.vatRate < 0) "zw." else "${item.vatRate}%"})")
        }
        val totalGross = input.quoteItems.sumOf { it.priceGross }
        appendLine("  RAZEM: ${"%.2f zł".format(totalGross / 100.0)} brutto")
    }

    private data class QuoteReplyLlmResponse(
        @JsonProperty("title")
        val title: String,

        @JsonProperty("reply")
        val reply: String
    )
}
