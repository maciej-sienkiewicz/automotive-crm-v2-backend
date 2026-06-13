package pl.detailing.crm.leads.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

// ── Config ────────────────────────────────────────────────────────────────────

@Configuration
class TimeAnalyticsAiConfig {
    @Bean("timeAnalyticsChatClient")
    fun timeAnalyticsChatClient(builder: ChatClient.Builder): ChatClient = builder.build()
}

// ── Command / Result ──────────────────────────────────────────────────────────

enum class TimeAnalyticsBucketType { BY_HOUR, BY_DAY_OF_MONTH }
enum class TimeAnalyticsActionType { INCOMING, ACCEPTED, REJECTED }

data class InterpretTimeAnalyticsCommand(
    val bucketType: TimeAnalyticsBucketType,
    val actionTypes: Set<TimeAnalyticsActionType>,
    val buckets: List<TimeBucketResult>
)

data class TimeAnalysisInsight(
    val bucketLabel: String,
    val observation: String,
    val causalExplanation: String
)

data class TimeAnalyticsInterpretation(
    val summary: String,
    val insights: List<TimeAnalysisInsight>,
    val recommendations: TimeAnalyticsRecommendations
)

data class TimeAnalyticsRecommendations(
    val bestTimeToCall: String,
    val bestTimeToRemind: String,
    val adCampaignTiming: String,
    val socialMediaTiming: String
)

// ── Service ───────────────────────────────────────────────────────────────────

@Service
class InterpretTimeAnalyticsService(
    @Qualifier("timeAnalyticsChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun interpret(command: InterpretTimeAnalyticsCommand): TimeAnalyticsInterpretation =
        withContext(Dispatchers.IO) {
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(command)

            val raw = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()
                ?: throw TimeAnalyticsInterpretationException("Model zwrócił pustą odpowiedź")

            log.debug("[LEADS] Time analytics interpretation received, length={}", raw.length)
            parseResponse(raw)
        }

    private fun buildSystemPrompt() = """
ROLA:
Jesteś analitykiem behawioralnym i strategiem marketingowym z doświadczeniem w branży automotive premium oraz e-commerce B2C w Polsce.
Specjalizujesz się w interpretacji danych czasowych (godziny, dni miesiąca) pod kątem wzorców konsumenckich i psychologii decyzji zakupowych.

MISJA:
Twoja analiza ma realne zastosowanie biznesowe — właściciel salonu detailingu podejmie na jej podstawie decyzje o:
- harmonogramie telefonów do klientów
- harmonogramie kampanii Meta Ads i Google Ads
- terminach publikacji reels/rolek na Instagram i TikTok

ZASADY KRYTYCZNE — PRZESTRZEGAJ BEZ WYJĄTKU:
1. TYLKO FAKTY POPARTE WIEDZĄ: Przytaczaj wyłącznie zjawiska, które mają udokumentowane podstawy w psychologii konsumenckiej, socjologii pracy lub polskich realiach (np. rytm wypłat, godziny szczytu komunikacyjnego, rytm tygodnia pracy). NIE SPEKULUJ.
2. ZAKAZ DOMYSŁÓW: Jeśli nie masz wystarczających danych lub wiedzy do wyciągnięcia wniosku przyczynowo-skutkowego, napisz wprost "Brak jednoznacznej interpretacji dla tego przedziału" zamiast tworzyć pseudo-naukowe tezy.
3. POLSKA SPECYFIKA: Odwołuj się do realiów polskiego rynku pracy: typowy dzień wypłaty (10., 25. dzień miesiąca), godziny szczytu komunikacyjnego w miastach (7-9, 16-18), rytm tygodnia pracy.
4. PRECYZJA ZALECEN: Rekomendacje muszą być konkretne — podaj godziny lub zakresy dni, nie ogólniki.
5. JĘZYK: Odpowiadaj wyłącznie po polsku.

FORMAT ODPOWIEDZI — ZWRÓĆ DOKŁADNIE TEN JSON (bez markdown, bez komentarzy poza JSON):
{
  "summary": "2-3 zdania opisujące główny wzorzec widoczny w danych",
  "insights": [
    {
      "bucketLabel": "np. '9:00-10:00' lub 'Dzień 10'",
      "observation": "Co widać w danych dla tego przedziału",
      "causalExplanation": "Udokumentowane wyjaśnienie lub 'Brak jednoznacznej interpretacji'"
    }
  ],
  "recommendations": {
    "bestTimeToCall": "Konkretny zakres godzin/dni z uzasadnieniem",
    "bestTimeToRemind": "Konkretny zakres z uzasadnieniem (push, SMS, email)",
    "adCampaignTiming": "Kiedy uruchamiać płatne kampanie Meta/Google Ads",
    "socialMediaTiming": "Kiedy publikować rolki/posty organiczne"
  }
}

WAŻNE: insights powinny zawierać TYLKO przedziały z wyraźnym wzorcem (szczyt lub dołek). Pomiń przedziały bez interesującego wzorca.
""".trimIndent()

    private fun buildUserPrompt(cmd: InterpretTimeAnalyticsCommand): String {
        val bucketDesc = when (cmd.bucketType) {
            TimeAnalyticsBucketType.BY_HOUR ->
                "Dane pogrupowane wg GODZINY DNIA (bucket = godzina, 0 = północ, 12 = południe)"
            TimeAnalyticsBucketType.BY_DAY_OF_MONTH ->
                "Dane pogrupowane wg DNIA MIESIĄCA (bucket = 1..31)"
        }

        val actionDesc = cmd.actionTypes.joinToString(", ") {
            when (it) {
                TimeAnalyticsActionType.INCOMING -> "przychodzące zapytania (incomingCount)"
                TimeAnalyticsActionType.ACCEPTED -> "zaakceptowane leady / potwierdzone wizyty (acceptedCount)"
                TimeAnalyticsActionType.REJECTED -> "odrzucone/utracone leady (rejectedCount)"
            }
        }

        val dataRows = cmd.buckets
            .filter { b ->
                (TimeAnalyticsActionType.INCOMING in cmd.actionTypes && b.incomingCount > 0) ||
                (TimeAnalyticsActionType.ACCEPTED in cmd.actionTypes && b.acceptedCount > 0) ||
                (TimeAnalyticsActionType.REJECTED in cmd.actionTypes && b.rejectedCount > 0)
            }
            .joinToString("\n") { b ->
                val parts = mutableListOf<String>()
                if (TimeAnalyticsActionType.INCOMING in cmd.actionTypes) parts += "przychodzące=${b.incomingCount}"
                if (TimeAnalyticsActionType.ACCEPTED in cmd.actionTypes) parts += "zaakceptowane=${b.acceptedCount}"
                if (TimeAnalyticsActionType.REJECTED in cmd.actionTypes) parts += "odrzucone=${b.rejectedCount}"
                "  bucket=${b.bucket}: ${parts.joinToString(", ")}"
            }

        return """
KONTEKST BIZNESOWY:
Salon detailingu samochodowego premium w Polsce. Usługi: powłoki ceramiczne, folie ochronne PPF, polerowanie, korekta lakieru.
Ceny usług: 500 PLN – 15 000 PLN. Klienci: właściciele aut premium i sportowych (BMW, Mercedes, Porsche, Audi).
Decyzja o zakupie jest przemyślana, często poprzedzona porównywaniem ofert.

TYP DANYCH: $bucketDesc
ANALIZOWANE METRYKI: $actionDesc

DANE:
$dataRows

ZADANIE:
1. Zidentyfikuj przedziały z wyraźnym wzrostem lub spadkiem aktywności.
2. Dla każdego takiego przedziału podaj obserwację i — jeśli istnieje udokumentowane uzasadnienie — wyjaśnienie przyczynowe.
3. Na podstawie wzorców sformułuj konkretne zalecenia operacyjne dla właściciela salonu.
""".trimIndent()
    }

    private fun parseResponse(raw: String): TimeAnalyticsInterpretation {
        val json = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.readValue(json, TimeAnalyticsInterpretation::class.java)
        } catch (e: Exception) {
            log.warn("[LEADS] Failed to parse LLM JSON response, returning raw as summary: {}", e.message)
            TimeAnalyticsInterpretation(
                summary = json,
                insights = emptyList(),
                recommendations = TimeAnalyticsRecommendations(
                    bestTimeToCall = "",
                    bestTimeToRemind = "",
                    adCampaignTiming = "",
                    socialMediaTiming = ""
                )
            )
        }
    }
}

class TimeAnalyticsInterpretationException(message: String) : RuntimeException(message)
