package pl.detailing.crm.visit.services.sms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

@Configuration
class ServiceChangeSmsAiConfig {

    @Bean("serviceChangeSmsChatClient")
    fun serviceChangeSmsChatClient(builder: ChatClient.Builder): ChatClient = builder.build()
}

/** Jedna pozycja zmiany przekazywana modelowi. Kwoty w groszach. */
data class SmsServiceChangeLine(
    val serviceName: String,
    val grossCents: Long,
    val previousGrossCents: Long? = null
)

data class ServiceChangeSmsContext(
    val added: List<SmsServiceChangeLine>,
    val removed: List<SmsServiceChangeLine>,
    val priceChanged: List<SmsServiceChangeLine>,
    val totalGrossBeforeCents: Long,
    val totalGrossAfterCents: Long
)

/**
 * Prosi LLM (OpenAI przez Spring AI) o krótkie podsumowanie zmian w zakresie usług,
 * które trafi do klienta SMS-em.
 *
 * Wywołanie zawsze zwraca sam TEKST ZMIAN — bez wezwania do odpowiedzi "TAK".
 * Ta fraza jest doklejana przez [pl.detailing.crm.smscampaigns.consent.SmsConsentService]
 * przy wysyłce, żeby nie dało się jej usunąć ani z draftu, ani z edycji w CRM-ie.
 */
@Service
class ServiceChangeSmsGenerator(
    @Qualifier("serviceChangeSmsChatClient") private val chatClient: ChatClient
) {
    companion object {
        /** Docelowa długość treści od modelu; dopisek o odpowiedzi dochodzi już przy wysyłce. */
        const val TARGET_BODY_LENGTH = 160

        /** Twardy limit — dłuższą odpowiedź ucinamy na granicy słowa. */
        const val MAX_BODY_LENGTH = 200
    }

    suspend fun generate(context: ServiceChangeSmsContext): String {
        val raw = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemPrompt)
                .user(buildUserPrompt(context))
                .call()
                .content()
        }

        val cleaned = raw?.trim()?.trim('"')?.replace(Regex("\\s+"), " ").orEmpty()
        if (cleaned.isBlank()) throw ServiceChangeSmsGenerationException("LLM zwrócił pustą odpowiedź")

        return truncateOnWordBoundary(cleaned)
    }

    private fun truncateOnWordBoundary(text: String): String {
        if (text.length <= MAX_BODY_LENGTH) return text
        val cut = text.take(MAX_BODY_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > MAX_BODY_LENGTH / 2) cut.take(lastSpace) else cut).trim()
    }

    private val systemPrompt = """
        ROLA: Redagujesz krótkie SMS-y wysyłane przez studio detailingu do klienta,
        którego auto jest właśnie w serwisie, o zmianie zakresu usług.

        ZASADY:
        1. Piszesz po polsku, z polskimi znakami, zwięźle i rzeczowo.
        2. Maksymalnie $TARGET_BODY_LENGTH znaków. Krócej = lepiej — to SMS.
        3. Wymieniasz konkretnie co zostało DODANE, USUNIĘTE i czemu ZMIENIONO CENĘ,
           używając nazw usług z kontekstu. Pomijasz sekcje, które są puste.
        4. Zawsze podajesz nową cenę końcową brutto w złotych (np. "Razem 1 230,00 zł").
        5. Bez powitań, podpisów, emoji, linków, numerów telefonu i cen jednostkowych,
           jeśli nie są potrzebne do zrozumienia zmiany.
        6. NIE dopisujesz prośby o odpowiedź "TAK" ani żadnego wezwania do działania —
           system dokleja je sam. Nie kończ zdaniem o potwierdzeniu.
        7. Zwracasz wyłącznie treść SMS-a, bez cudzysłowów i komentarzy.
    """.trimIndent()

    private fun buildUserPrompt(ctx: ServiceChangeSmsContext): String {
        val sections = buildList {
            if (ctx.added.isNotEmpty()) {
                add("DODANE:\n" + ctx.added.joinToString("\n") { "- ${it.serviceName} (${money(it.grossCents)})" })
            }
            if (ctx.removed.isNotEmpty()) {
                add("USUNIĘTE:\n" + ctx.removed.joinToString("\n") { "- ${it.serviceName}" })
            }
            if (ctx.priceChanged.isNotEmpty()) {
                add("ZMIANA CENY:\n" + ctx.priceChanged.joinToString("\n") {
                    val from = it.previousGrossCents?.let { prev -> "${money(prev)} -> " } ?: ""
                    "- ${it.serviceName} ($from${money(it.grossCents)})"
                })
            }
        }

        return buildString {
            appendLine(sections.joinToString("\n\n").ifBlank { "BRAK ZMIAN W POZYCJACH" })
            appendLine()
            appendLine("CENA KOŃCOWA BRUTTO PRZED ZMIANĄ: ${money(ctx.totalGrossBeforeCents)}")
            appendLine("CENA KOŃCOWA BRUTTO PO ZMIANIE: ${money(ctx.totalGrossAfterCents)}")
            appendLine()
            append("ZADANIE: Napisz treść SMS-a podsumowującą powyższe zmiany dla klienta.")
        }
    }

    private fun money(cents: Long): String =
        String.format(java.util.Locale.US, "%,.2f", cents / 100.0)
            .replace(",", " ")  // separator tysięcy
            .replace(".", ",") + " zł"
}

class ServiceChangeSmsGenerationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
