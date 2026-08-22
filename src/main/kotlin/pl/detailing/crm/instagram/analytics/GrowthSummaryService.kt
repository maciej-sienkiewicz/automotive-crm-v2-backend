package pl.detailing.crm.instagram.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── DTO / wyjątki ─────────────────────────────────────────────────────────────

data class GrowthSummaryDto(
    val summary: String,
    val generatedAt: String,
    val weeks: Int,
    val fromCache: Boolean
)

/** Limit: jedno pełne (zakończone sukcesem) wygenerowanie podsumowania na cooldown. */
class GrowthSummaryRateLimitException(val retryAfterSeconds: Long) :
    RuntimeException("Podsumowanie AI można generować raz na 15 minut")

// ── Konfiguracja AI ───────────────────────────────────────────────────────────

/**
 * Dedykowany klient dla podsumowań trendów: mocniejszy model niż domyślny
 * gpt-4o-mini modułu Instagram, bo zadanie wymaga wnioskowania o wzorcach
 * w szeregach czasowych (cykliczność, trendy, porównania między profilami),
 * a wywołanie jest rzadkie (twardy limit 1/15 min na studio) i cache'owane.
 */
@Configuration
class InstagramTrendsAiConfig {

    @Bean("instagramTrendsChatClient")
    fun instagramTrendsChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.instagram-trends.model:gpt-4o}") model: String
    ): ChatClient =
        builder
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.3)
                    .build()
            )
            .build()
}

// ── Serwis ────────────────────────────────────────────────────────────────────

/**
 * Podsumowanie AI przyrostów obserwujących dla zakładki "Porównanie".
 *
 * Backend zbiera liczby (dzienne delty ze snapshotów + tygodniowe agregaty),
 * LLM pisze wyłącznie interpretację — nie może zhalucynować danych, bo dostaje
 * gotowy payload i ma zakaz wymyślania liczb.
 *
 * Ochrona kosztów: odpowiedź jest cache'owana w Redis (klucz zawiera datę
 * ostatniego snapshotu, więc nowe dane naturalnie unieważniają cache), a nowe
 * wygenerowanie podlega limitowi 1 zakończony request / cooldown na studio.
 * Trafienie w cache NIE konsumuje limitu.
 */
@Service
class GrowthSummaryService(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val statsRepository: InstagramProfileStatsWeeklyRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Qualifier("instagramTrendsChatClient") private val chatClient: ObjectProvider<ChatClient>,
    @Value("\${instagram.growth-summary.cooldown-minutes:15}") private val cooldownMinutes: Long
) {
    private val log = LoggerFactory.getLogger(GrowthSummaryService::class.java)

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        private val GENERATED_AT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(WARSAW)
        private val CACHE_TTL: Duration = Duration.ofHours(24)

        private val SYSTEM_PROMPT = """
            Jesteś doświadczonym analitykiem social media, specjalizujesz się w analizie wzrostu
            profili na Instagramie dla studiów detailingu samochodowego w Polsce.

            Otrzymasz dane JSON z przyrostami liczby obserwujących dla grupy profili:
            profil użytkownika (isSelf=true) oraz obserwowani konkurenci. Dla każdego profilu
            dostaniesz dzienne delty (dailyDeltas) i/lub tygodniowe delty (weeklyDeltas)
            z całego analizowanego okresu, plus aktualną liczbę obserwujących.

            Twoje zadanie — przeanalizuj i opisz:
            1. Kto i kiedy notuje wzrosty: wskaż konkretne dni/tygodnie z największymi przyrostami.
            2. Regularność i cykliczność: czy któryś profil rośnie w powtarzalnym rytmie
               (np. stałe dni tygodnia, równe tempo tydzień po tygodniu)? Nazwij ten wzorzec.
            3. Trendy: kto ma trend wzrostowy, kto wyhamował, kto traci. Porównaj tempo między
               profilami — także względem profilu użytkownika.
            4. Jeżeli dane są nieregularne i nie widać żadnej reguły — napisz to wprost,
               nie dopatruj się wzorców na siłę.

            Twarde zasady:
            - Używaj WYŁĄCZNIE liczb i dat z przekazanych danych. Niczego nie wymyślaj i nie szacuj.
            - Przy małej liczbie punktów danych zaznacz, że wnioski są wstępne.
            - Pisz po polsku, zwięźle i profesjonalnie, jak analityk do właściciela studia.
            - Format: zwykły tekst. Zacznij 1-2 zdaniami najważniejszego wniosku, potem krótki
              akapit lub punkty (myślniki) per profil, na końcu 1-2 zdania rekomendacji.
            - Bez nagłówków markdown, bez tabel, maksymalnie ok. 250 słów.
        """.trimIndent()
    }

    fun summarize(studioId: StudioId, weeks: Int): GrowthSummaryDto {
        val links = studioProfileRepository.findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
        if (links.isEmpty()) {
            throw ValidationException("Dodaj przynajmniej jeden profil, aby wygenerować podsumowanie")
        }
        val profiles = profileRepository.findAllById(links.map { it.profileId }).associateBy { it.id }

        val currentMonday = MetricsCalculator.currentWeekStart()
        val windowFrom = currentMonday.minusWeeks(weeks.toLong() - 1)

        val snapshotsByProfile = metricsRepository
            .findByProfileIdInAndSnapshotDateAfterOrderBySnapshotDateAsc(links.map { it.profileId }, windowFrom)
            .groupBy { it.profileId }
        val weeklyByProfile = statsRepository
            .findByProfileIdInAndWeekStartGreaterThanEqual(links.map { it.profileId }, windowFrom)
            .groupBy { it.profileId }

        val latestSnapshotDate = snapshotsByProfile.values.flatten().maxOfOrNull { it.snapshotDate }
            ?: throw ValidationException(
                "Za mało danych do podsumowania — historia obserwujących buduje się od dnia dodania profilu"
            )

        // Cache: klucz zawiera datę najnowszego snapshotu, więc świeże dane = nowy klucz.
        val cacheKey = "instagram:growth-summary:${studioId.value}:$weeks:$latestSnapshotDate"
        redisTemplate.opsForValue().get(cacheKey)?.let { cached ->
            return objectMapper.readValue(cached, GrowthSummaryDto::class.java).copy(fromCache = true)
        }

        // Rate limit: liczy się dopiero ZAKOŃCZONY request (klucz ustawiany po sukcesie),
        // więc błąd LLM nie blokuje ponownej próby.
        val cooldownKey = "instagram:growth-summary:cooldown:${studioId.value}"
        if (redisTemplate.hasKey(cooldownKey)) {
            val retryAfter = redisTemplate.getExpire(cooldownKey).coerceAtLeast(1)
            throw GrowthSummaryRateLimitException(retryAfter)
        }

        val client = chatClient.ifAvailable
            ?: throw ValidationException("Funkcja podsumowań AI jest w tej chwili niedostępna")

        val payload = buildPayload(links, profiles, snapshotsByProfile, weeklyByProfile, weeks)

        val summary = client.prompt()
            .system(SYSTEM_PROMPT)
            .user(objectMapper.writeValueAsString(payload))
            .call()
            .content()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("LLM zwrócił pustą odpowiedź")

        val dto = GrowthSummaryDto(
            summary = summary,
            generatedAt = GENERATED_AT_FMT.format(Instant.now()),
            weeks = weeks,
            fromCache = false
        )

        redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofMinutes(cooldownMinutes))
        redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dto), CACHE_TTL)
        log.info("Instagram growth summary generated [studioId={}, weeks={}, profiles={}]", studioId, weeks, links.size)

        return dto
    }

    private fun buildPayload(
        links: List<pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileEntity>,
        profiles: Map<java.util.UUID, pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity>,
        snapshotsByProfile: Map<java.util.UUID, List<pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotEntity>>,
        weeklyByProfile: Map<java.util.UUID, List<pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyEntity>>,
        weeks: Int
    ): Map<String, Any> {
        val profilesPayload = links.mapNotNull { link ->
            val profile = profiles[link.profileId] ?: return@mapNotNull null

            // Dzienne delty z kolejnych par snapshotów (pomijamy dni bez pomiaru).
            val dailyDeltas = mutableListOf<Map<String, Any>>()
            var prev: Int? = null
            (snapshotsByProfile[link.profileId] ?: emptyList()).forEach { snapshot ->
                val count = snapshot.followerCount ?: return@forEach
                prev?.let { previous ->
                    dailyDeltas += mapOf(
                        "date" to snapshot.snapshotDate.toString(),
                        "delta" to (count - previous),
                        "total" to count
                    )
                }
                prev = count
            }

            val weeklyDeltas = (weeklyByProfile[link.profileId] ?: emptyList())
                .sortedBy { it.weekStart }
                .mapNotNull { row ->
                    row.followerDelta?.let {
                        mapOf("weekStart" to row.weekStart.toString(), "delta" to it, "posts" to row.postCount)
                    }
                }

            mapOf(
                "username" to profile.username,
                "isSelf" to link.isSelf,
                "currentFollowers" to (profile.followerCount ?: 0),
                "dailyDeltas" to dailyDeltas,
                "weeklyDeltas" to weeklyDeltas
            )
        }

        return mapOf(
            "periodWeeks" to weeks,
            "note" to "dailyDeltas obejmują tylko dni z pomiarem; historia buduje się od dnia dodania profilu",
            "profiles" to profilesPayload
        )
    }
}
