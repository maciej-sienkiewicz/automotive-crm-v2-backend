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
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.round

// ── DTO wyjściowe ─────────────────────────────────────────────────────────────

/**
 * Pojedyncza obserwacja. [kind] decyduje, do której sekcji trafi na froncie:
 * UNDERUSED_TOPIC | WEAK_TOPIC | OWN_PERFORMANCE | PRICE_MOVE
 */
data class EffectivenessFindingDto(
    val kind: String,
    val headline: String,
    val body: String
)

data class ContentEffectivenessDto(
    val findings: List<EffectivenessFindingDto>,
    val postsAnalyzed: Int,
    val weeks: Int,
    val generatedAt: String,
    val fromCache: Boolean
)

/** Limit: jedna zakończona analiza na studio w oknie cooldownu. */
class ContentEffectivenessRateLimitException(val retryAfterSeconds: Long) :
    RuntimeException("Analizę można generować raz na 15 minut")

// ── Struktury odpowiedzi LLM ─────────────────────────────────────────────────

data class LlmFinding(
    val kind: String = "",
    val headline: String = "",
    val body: String = ""
)

data class LlmFindingList(val findings: List<LlmFinding> = emptyList())

data class LlmVerdict(
    val index: Int = -1,
    val keep: Boolean = false,
    val reason: String = ""
)

data class LlmVerdictList(val verdicts: List<LlmVerdict> = emptyList())

// ── Konfiguracja AI ───────────────────────────────────────────────────────────

/**
 * Mocniejszy model niż domyślny gpt-4o-mini modułu: zadanie polega na wybraniu
 * istotnych zjawisk spośród kilkunastu metryk i opisaniu ich bez lania wody,
 * a wywołania są rzadkie (limit 1/15 min na studio) i cache'owane.
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
                    .temperature(0.2)
                    .build()
            )
            .build()
}

// ── Serwis ────────────────────────────────────────────────────────────────────

/**
 * "Co działa u konkurencji" — obserwacje o skuteczności tematów w obserwowanej grupie.
 *
 * Zasady, na których to stoi:
 *  1. Wszystkie liczby liczy kod. LLM wyłącznie wybiera, co jest istotne, i opisuje to zdaniami.
 *  2. Raportujemy ODCHYLENIE od wartości oczekiwanej, nie surowe zaangażowanie. Post porównujemy
 *     najpierw do mediany własnego profilu (znika wpływ wielkości konta), a potem do typowego
 *     wyniku dla danej pory publikacji (znika wpływ "wtorek rano jest słaby").
 *  3. Nic nie mówimy właścicielowi, co ma opublikować — to jego decyzja. Podajemy fakty z rynku.
 *  4. Poniżej progu liczby postów pozycja NIE pojawia się wcale (zamiast pojawiać się z zastrzeżeniem).
 *  5. Drugi przebieg LLM odrzuca obserwacje bez konkretu — ogólniki nie przechodzą do UI.
 */
@Service
class ContentEffectivenessService(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postRepository: InstagramPostSnapshotRepository,
    private val topicRepository: InstagramPostTopicRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Qualifier("instagramTrendsChatClient") private val chatClient: ObjectProvider<ChatClient>,
    @Value("\${instagram.content-effectiveness.cooldown-minutes:15}") private val cooldownMinutes: Long
) {
    private val log = LoggerFactory.getLogger(ContentEffectivenessService::class.java)

    companion object {
        private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        private val GENERATED_AT_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(WARSAW)
        private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM").withZone(WARSAW)
        private val CACHE_TTL: Duration = Duration.ofHours(24)

        /** Profil bez tylu postów nie ma wiarygodnej mediany — wypada z analizy. */
        private const val MIN_POSTS_PER_PROFILE = 4

        /** Temat poniżej tego progu nie trafia do wyników w ogóle. */
        private const val MIN_POSTS_PER_TOPIC = 3

        /** Pora publikacji poniżej tego progu nie dostaje własnego współczynnika (przyjmujemy 1.0). */
        private const val MIN_POSTS_PER_SLOT = 4

        /** Poniżej tylu postów w całej grupie nie ma o czym mówić. */
        private const val MIN_TOTAL_POSTS = 12

        /** Odchylenie mniejsze niż to uznajemy za szum, nie za sygnał. */
        private const val DEVIATION_THRESHOLD = 0.15

        private const val TOP_POSTS = 5

        /** Tematy porządkowe — nie są usługą, więc nie oceniamy ich skuteczności. */
        private val NON_SERVICE_TOPICS = setOf("KONKURS", "PROMOCJA", "INNE")

        private val DAYPART_LABELS = listOf("rano (6–11)", "w południe (11–16)", "wieczorem (16–21)", "nocą (21–6)")

        private val ALLOWED_KINDS = setOf("UNDERUSED_TOPIC", "WEAK_TOPIC", "OWN_PERFORMANCE", "PRICE_MOVE")

        private val SYSTEM_PROMPT = """
            Jesteś analitykiem treści social media współpracującym ze studiami detailingu w Polsce.
            Dostajesz POLICZONE metryki skuteczności tematów w grupie profili, które właściciel
            studia obserwuje jako konkurencję. Twoim jedynym zadaniem jest wybrać z nich to,
            co istotne, i opisać to zdaniami.

            Jak czytać dane:
            - "deviationPct" to odchylenie od wyniku OCZEKIWANEGO dla danej pory publikacji,
              po uwzględnieniu wielkości profilu. Dodatnie = temat wypadł lepiej, niż wynikałoby
              z pory dnia i typowego poziomu tych profili. Ujemne = gorzej.
            - "postsInGroup" to liczba postów, na której policzono odchylenie.
            - "yourPosts" to liczba postów właściciela na ten temat.
            - "topPosts.byTopic" to rozkład tematów wśród najlepszych postów w grupie.

            Napisz obserwacje w czterech kategoriach (pole "kind"):
            - UNDERUSED_TOPIC: temat, który w grupie działa dobrze, a właściciel publikuje go rzadko lub wcale.
            - WEAK_TOPIC: temat, który w tym okresie wypadł wyraźnie poniżej oczekiwań.
            - OWN_PERFORMANCE: skuteczność własnych postów właściciela na tle oczekiwań.
            - PRICE_MOVE: ruch cenowy u konkurencji (promocje i rabaty).

            ZASADY BEZWZGLĘDNE:
            1. Używaj wyłącznie liczb, nazw profili, nazw tematów i dat obecnych w danych.
               Nie licz niczego samodzielnie, nie zaokrąglaj inaczej, niż podano, niczego nie zgaduj.
            2. KAŻDE zdanie musi zawierać konkret: liczbę, nazwę tematu, nazwę profilu albo datę.
               Zdanie bez konkretu jest błędem.
            3. NIE doradzasz i NIE wydajesz poleceń. Zakazane są zwroty typu "warto", "powinieneś",
               "zwiększ", "publikuj", "skup się", "rozważ", "zalecamy", a także tryb rozkazujący.
               Raportujesz, co pokazują dane — decyzję podejmuje właściciel.
            4. Nie proponujesz tematów postów ani treści. Nie układasz planu publikacji.
            5. Pomijasz kategorię, dla której dane nie dają nic konkretnego. Lepiej mniej obserwacji
               niż jedna wypełniona ogólnikami.
            6. "headline" to krótka etykieta (nazwa tematu albo nazwa profilu), maksymalnie 60 znaków.
               "body" to 1–3 zdania po polsku.
            7. Maksymalnie 8 obserwacji łącznie.

            Przykład dobrej obserwacji:
            "Metamorfozy wnętrza dały 4 z 5 najlepszych postów w grupie, przy zaangażowaniu o 62%
            powyżej oczekiwanego dla ich pory publikacji. Ty nie masz w tym okresie żadnego posta
            na ten temat."

            Przykład złej obserwacji (ogólnik + porada — odrzucany):
            "Warto rozważyć publikowanie większej liczby angażujących treści, aby zwiększyć zasięgi."
        """.trimIndent()

        private val VERIFIER_PROMPT = """
            Jesteś surowym recenzentem raportów analitycznych. Dostajesz listę obserwacji
            (ponumerowaną od 0) oraz dane źródłowe, na podstawie których powstały.

            Dla każdej obserwacji orzekasz keep = true tylko wtedy, gdy spełnia WSZYSTKIE warunki:
            1. Każde zdanie zawiera konkret: liczbę, nazwę tematu, nazwę profilu albo datę.
            2. Wszystkie liczby, nazwy i daty występują w danych źródłowych. Jeśli cokolwiek
               zostało zmyślone lub przeliczone inaczej niż w danych — keep = false.
            3. Nie zawiera porad, zaleceń ani trybu rozkazującego ("warto", "powinieneś",
               "zwiększ", "publikuj", "rozważ", "skup się", "zalecamy").
            4. Nie zawiera ogólników bez pokrycia w liczbach ("dobre wyniki", "słabe zaangażowanie",
               "pozytywny trend") — chyba że w tym samym zdaniu stoi konkretna wartość.
            5. Nie proponuje tematów postów ani planu publikacji.

            W polu "reason" podaj krótkie uzasadnienie po polsku. Zwróć werdykt dla KAŻDEJ
            obserwacji, zachowując jej indeks. Bądź surowy — lepiej odrzucić obserwację
            wątpliwą niż wpuścić ogólnik.
        """.trimIndent()
    }

    // ── Wejście publiczne ─────────────────────────────────────────────────────

    fun analyze(studioId: StudioId, weeks: Int): ContentEffectivenessDto {
        val links = studioProfileRepository.findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
        if (links.isEmpty()) {
            throw ValidationException("Dodaj przynajmniej jeden profil, aby przeanalizować skuteczność tematów")
        }

        val profileIds = links.map { it.profileId }
        val profiles = profileRepository.findAllById(profileIds).associateBy { it.id }

        val windowFrom = MetricsCalculator.currentWeekStart().minusWeeks(weeks.toLong() - 1)
        val cutoff = windowFrom.atStartOfDay(ZoneOffset.UTC).toInstant()
        val posts = postRepository.findByProfileIdInAndTakenAtAfter(profileIds, cutoff)

        if (posts.size < MIN_TOTAL_POSTS) {
            throw ValidationException(
                "Za mało postów w tym okresie ($MIN_TOTAL_POSTS wymagane, jest ${posts.size}) — " +
                    "wybierz dłuższy okres albo dodaj więcej obserwowanych profili"
            )
        }

        val topics = topicRepository.findByPostIdIn(posts.map { it.id }).associateBy { it.postId }
        val latestPostAt = posts.maxOf { it.takenAt }

        // Cache: klucz zawiera datę najnowszego posta, więc nowe treści same unieważniają wynik.
        val cacheKey = "instagram:content-effectiveness:${studioId.value}:$weeks:${latestPostAt.epochSecond}"
        redisTemplate.opsForValue().get(cacheKey)?.let { cached ->
            return objectMapper.readValue(cached, ContentEffectivenessDto::class.java).copy(fromCache = true)
        }

        val selfProfileId = links.firstOrNull { it.isSelf }?.profileId
        val rows = buildRows(links.associate { it.profileId to it.isSelf }, profiles, posts, topics)
        val payload = buildPayload(rows, posts, topics, profiles, selfProfileId, weeks)
            ?: throw ValidationException(
                "Za mało danych do wiarygodnej analizy — profile muszą mieć po co najmniej " +
                    "$MIN_POSTS_PER_PROFILE postów w wybranym okresie"
            )

        // Limit liczy dopiero ZAKOŃCZONY przebieg (klucz ustawiany po sukcesie),
        // więc błąd modelu nie blokuje ponownej próby. Cache nie konsumuje limitu.
        val cooldownKey = "instagram:content-effectiveness:cooldown:${studioId.value}"
        if (redisTemplate.hasKey(cooldownKey)) {
            throw ContentEffectivenessRateLimitException(redisTemplate.getExpire(cooldownKey).coerceAtLeast(1))
        }

        val client = chatClient.ifAvailable
            ?: throw ValidationException("Analiza AI jest w tej chwili niedostępna")

        val payloadJson = objectMapper.writeValueAsString(payload)
        val drafted = draftFindings(client, payloadJson)
        val verified = verifyFindings(client, payloadJson, drafted)

        val dto = ContentEffectivenessDto(
            findings = verified,
            postsAnalyzed = rows.size,
            weeks = weeks,
            generatedAt = GENERATED_AT_FMT.format(Instant.now()),
            fromCache = false
        )

        redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofMinutes(cooldownMinutes))
        redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dto), CACHE_TTL)
        log.info(
            "Instagram content effectiveness generated [studioId={}, weeks={}, posts={}, drafted={}, kept={}]",
            studioId, weeks, rows.size, drafted.size, verified.size
        )

        return dto
    }

    // ── Etap 1: normalizacja postów ───────────────────────────────────────────

    /**
     * Jeden post sprowadzony do porównywalnej postaci. [rel] to zaangażowanie względem
     * MEDIANY WŁASNEGO PROFILU — dzięki temu post konta 20-tysięcznego i 1-tysięcznego
     * da się zestawić, a wielkość konta przestaje mieć znaczenie.
     */
    private data class PostRow(
        val profileId: UUID,
        val username: String,
        val isSelf: Boolean,
        val topic: String,
        val topicLabel: String,
        val daypart: Int,
        val rel: Double
    )

    private fun buildRows(
        isSelfByProfile: Map<UUID, Boolean>,
        profiles: Map<UUID, InstagramProfileEntity>,
        posts: List<InstagramPostSnapshotEntity>,
        topics: Map<UUID, InstagramPostTopicEntity>
    ): List<PostRow> {
        val rows = mutableListOf<PostRow>()

        posts.groupBy { it.profileId }.forEach { (profileId, profilePosts) ->
            if (profilePosts.size < MIN_POSTS_PER_PROFILE) return@forEach
            val profile = profiles[profileId] ?: return@forEach
            val median = MetricsCalculator.median(profilePosts.map { engagementOf(it).toDouble() }) ?: return@forEach
            if (median <= 0.0) return@forEach

            profilePosts.forEach { post ->
                val topicName = topics[post.id]?.topic ?: "INNE"
                rows += PostRow(
                    profileId = profileId,
                    username = profile.username,
                    isSelf = isSelfByProfile[profileId] == true,
                    topic = topicName,
                    topicLabel = InstagramPostTopic.labelFor(topicName),
                    daypart = daypartOf(post.takenAt),
                    rel = engagementOf(post).toDouble() / median
                )
            }
        }
        return rows
    }

    // ── Etap 2: metryki (wyłącznie kod) ───────────────────────────────────────

    private fun buildPayload(
        rows: List<PostRow>,
        posts: List<InstagramPostSnapshotEntity>,
        topics: Map<UUID, InstagramPostTopicEntity>,
        profiles: Map<UUID, InstagramProfileEntity>,
        selfProfileId: UUID?,
        weeks: Int
    ): Map<String, Any>? {
        if (rows.size < MIN_TOTAL_POSTS) return null

        // Współczynnik pory publikacji: ile typowo "waży" post opublikowany o tej porze.
        // Liczony na całej grupie, bo per profil próbka byłaby za mała.
        val slotFactor: Map<Int, Double> = rows.groupBy { it.daypart }
            .mapNotNull { (daypart, list) ->
                if (list.size < MIN_POSTS_PER_SLOT) return@mapNotNull null
                val factor = MetricsCalculator.median(list.map { it.rel }) ?: return@mapNotNull null
                if (factor <= 0.0) null else daypart to factor
            }
            .toMap()

        fun deviationOf(row: PostRow): Double = row.rel / (slotFactor[row.daypart] ?: 1.0) - 1.0

        val serviceRows = rows.filter { it.topic !in NON_SERVICE_TOPICS }

        // Skuteczność tematów w całej grupie.
        val topicStats: List<Map<String, Any>> = serviceRows.groupBy { it.topic }
            .mapNotNull { (_, list) ->
                if (list.size < MIN_POSTS_PER_TOPIC) return@mapNotNull null
                val deviation = MetricsCalculator.median(list.map { deviationOf(it) }) ?: return@mapNotNull null
                mapOf<String, Any>(
                    "topic" to list.first().topicLabel,
                    "postsInGroup" to list.size,
                    "yourPosts" to list.count { it.isSelf },
                    "deviationPct" to round1(deviation * 100)
                )
            }
            .sortedByDescending { it["deviationPct"] as Double }

        // Najlepsze posty w grupie i rozkład ich tematów.
        val topPosts = rows.sortedByDescending { it.rel }.take(TOP_POSTS)
        val topByTopic = topPosts.groupingBy { it.topicLabel }.eachCount()

        // Dla tematów słabych: który profil ciągnie je w dół i o jakiej porze publikował.
        val weakTopicDetails = serviceRows.groupBy { it.topic }
            .mapNotNull { (_, list) ->
                if (list.size < MIN_POSTS_PER_TOPIC) return@mapNotNull null
                val topicDeviation = MetricsCalculator.median(list.map { deviationOf(it) }) ?: return@mapNotNull null
                if (topicDeviation >= -DEVIATION_THRESHOLD) return@mapNotNull null

                val worst = list.groupBy { it.profileId }
                    .filterValues { it.size >= 2 }
                    .mapNotNull { (_, profilePosts) ->
                        val profileDeviation = MetricsCalculator.median(profilePosts.map { deviationOf(it) })
                            ?: return@mapNotNull null
                        Triple(profilePosts.first().username, profileDeviation, profilePosts)
                    }
                    .minByOrNull { it.second }
                    ?: return@mapNotNull null

                val entry = mutableMapOf<String, Any>(
                    "topic" to list.first().topicLabel,
                    "weakestProfile" to worst.first,
                    "weakestProfileDeviationPct" to round1(worst.second * 100),
                    "postsOfThatProfile" to worst.third.size,
                    "publishedAt" to worst.third.map { DAYPART_LABELS[it.daypart] }.distinct()
                )
                // Czy ta pora jest dla tego profilu zwykle dobra? Tylko przy sensownej próbce.
                bestDaypartFor(rows, worst.third.first().profileId)?.let { entry["profileBestDaypart"] = it }
                entry
            }

        // Skuteczność własnych postów właściciela.
        val ownStats: List<Map<String, Any>> = if (selfProfileId == null) emptyList() else
            serviceRows.filter { it.isSelf }
                .groupBy { it.topic }
                .mapNotNull { (_, list) ->
                    if (list.size < MIN_POSTS_PER_TOPIC) return@mapNotNull null
                    val deviation = MetricsCalculator.median(list.map { deviationOf(it) }) ?: return@mapNotNull null
                    mapOf<String, Any>(
                        "topic" to list.first().topicLabel,
                        "yourPosts" to list.size,
                        "deviationPct" to round1(deviation * 100),
                        "preliminary" to (list.size < 5)
                    )
                }

        // Ruch cenowy: promocje wykryte w treści postów.
        val priceMoves = posts.asSequence()
            .mapNotNull { post -> topics[post.id]?.takeIf { it.isPromo }?.let { post to it } }
            .sortedByDescending { it.first.takenAt }
            .take(8)
            .mapNotNull { (post, topic) ->
                val username = profiles[post.profileId]?.username ?: return@mapNotNull null
                val entry = mutableMapOf<String, Any>(
                    "profile" to username,
                    "date" to DATE_FMT.format(post.takenAt)
                )
                topic.discountPct?.let { entry["discountPct"] = it }
                post.caption?.take(220)?.let { entry["captionSnippet"] = it }
                entry
            }
            .toList()

        val hasAnything = topicStats.isNotEmpty() || ownStats.isNotEmpty() || priceMoves.isNotEmpty()
        if (!hasAnything) return null

        return mapOf(
            "periodWeeks" to weeks,
            "postsAnalyzed" to rows.size,
            "profilesAnalyzed" to rows.map { it.username }.distinct().size,
            "hasOwnProfile" to (selfProfileId != null),
            "deviationMeaning" to "odchylenie od wyniku oczekiwanego dla danej pory publikacji, " +
                "po sprowadzeniu postów do mediany własnego profilu",
            "significanceThresholdPct" to round1(DEVIATION_THRESHOLD * 100),
            "topicStats" to topicStats,
            "topPosts" to mapOf("total" to topPosts.size, "byTopic" to topByTopic),
            "weakTopicDetails" to weakTopicDetails,
            "yourTopicStats" to ownStats,
            "priceMoves" to priceMoves
        )
    }

    // ── Etap 3: LLM pisze obserwacje ──────────────────────────────────────────

    private fun draftFindings(client: ChatClient, payloadJson: String): List<LlmFinding> {
        val result = client.prompt()
            .system(SYSTEM_PROMPT)
            .user(payloadJson)
            .call()
            .entity(LlmFindingList::class.java)

        return (result?.findings ?: emptyList())
            .filter { it.kind in ALLOWED_KINDS && it.headline.isNotBlank() && it.body.isNotBlank() }
            .take(8)
    }

    // ── Etap 4: LLM weryfikuje konkretność ────────────────────────────────────

    /**
     * Drugi przebieg odrzuca obserwacje bez konkretu albo z liczbami spoza danych.
     * Weryfikator może wyłącznie ODRZUCAĆ — nie przepisuje treści, więc nie ma jak
     * wprowadzić własnego błędu do wyniku.
     */
    private fun verifyFindings(
        client: ChatClient,
        payloadJson: String,
        drafted: List<LlmFinding>
    ): List<EffectivenessFindingDto> {
        if (drafted.isEmpty()) return emptyList()

        val numbered = drafted.mapIndexed { index, finding ->
            mapOf("index" to index, "kind" to finding.kind, "headline" to finding.headline, "body" to finding.body)
        }
        val verifierInput = objectMapper.writeValueAsString(
            mapOf("observations" to numbered, "sourceData" to objectMapper.readValue(payloadJson, Map::class.java))
        )

        val verdicts = runCatching {
            client.prompt()
                .system(VERIFIER_PROMPT)
                .user(verifierInput)
                .call()
                .entity(LlmVerdictList::class.java)
                ?.verdicts
                ?: emptyList()
        }.getOrElse { error ->
            // Weryfikator jest zabezpieczeniem, nie warunkiem działania: gdy padnie,
            // przepuszczamy wersję po pierwszym przebiegu, zamiast zwracać pustkę.
            log.warn("Content effectiveness verifier failed, passing drafted findings through", error)
            emptyList()
        }

        if (verdicts.isEmpty()) return drafted.map { it.toDto() }

        val rejected = verdicts.filter { !it.keep }
        if (rejected.isNotEmpty()) {
            log.info("Content effectiveness: {} observation(s) rejected as vague: {}",
                rejected.size, rejected.joinToString { "#${it.index} (${it.reason})" })
        }

        val keptIndexes = verdicts.filter { it.keep }.map { it.index }.toSet()
        return drafted.filterIndexed { index, _ -> index in keptIndexes }.map { it.toDto() }
    }

    // ── Pomocnicze ────────────────────────────────────────────────────────────

    private fun LlmFinding.toDto() = EffectivenessFindingDto(kind = kind, headline = headline, body = body)

    private fun engagementOf(post: InstagramPostSnapshotEntity): Int = post.likeCount + post.commentCount

    /** 0: 6–11, 1: 11–16, 2: 16–21, 3: 21–6 (zgodnie z heatmapą modułu). */
    private fun daypartOf(takenAt: Instant): Int = when (takenAt.atZone(WARSAW).hour) {
        in 6..10 -> 0
        in 11..15 -> 1
        in 16..20 -> 2
        else -> 3
    }

    /** Najlepsza pora publikacji dla profilu; null przy zbyt małej próbce. */
    private fun bestDaypartFor(rows: List<PostRow>, profileId: UUID): String? {
        val profileRows = rows.filter { it.profileId == profileId }
        if (profileRows.size < 6) return null
        return profileRows.groupBy { it.daypart }
            .filterValues { it.size >= 2 }
            .mapNotNull { (daypart, list) -> MetricsCalculator.median(list.map { it.rel })?.let { daypart to it } }
            .maxByOrNull { it.second }
            ?.let { DAYPART_LABELS[it.first] }
    }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
}
