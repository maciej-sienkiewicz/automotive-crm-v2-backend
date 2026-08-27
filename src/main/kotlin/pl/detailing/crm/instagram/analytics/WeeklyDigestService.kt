package pl.detailing.crm.instagram.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramReportEntity
import pl.detailing.crm.instagram.infrastructure.InstagramReportRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileEntity
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

// ── DTO ───────────────────────────────────────────────────────────────────────

/**
 * Werdykt tygodnia dla jednego profilu. Dokładnie jeden na profil — to jest cała
 * różnica względem listy zdarzeń, która przy jednym ruchliwym koncie zalewała ekran
 * ośmioma wierszami o tym samym profilu.
 */
enum class DigestVerdict {
    /** Nic nie opublikował, choć zwykle publikuje. */
    SILENT,

    /** Post (lub posty) wyraźnie powyżej własnej normy zaangażowania. */
    STANDOUT,

    /** Wyraźnie więcej publikacji niż zwykle. */
    ACCELERATED,

    /** Publikował w swoim zwykłym rytmie. */
    STEADY,

    /** Za krótko obserwowany, żeby cokolwiek nazwać „zwykłym". */
    NEW
}

data class DigestPostDto(
    val permalink: String,
    /** REELS | CAROUSEL | PHOTO */
    val format: String,
    val topicLabel: String,
    val engagement: Int,
    val takenAt: String
)

data class ProfileDigestDto(
    val profileId: String,
    val username: String,
    val isSelf: Boolean,
    val verdict: String,
    /** „Zwiększył aktywność", „Nie dodał żadnego posta" — jedno zdanie na profil. */
    val headline: String,
    /**
     * Co ten profil faktycznie zrobił, wyciągnięte z opisów postów:
     * „Zrealizował oklejenie Porsche Panamery i detailing wnętrza Lamborghini."
     * Null, gdy opisy nic nie mówią o realizacjach (albo profil milczał).
     */
    val achievements: String?,
    /** Liczby stojące za werdyktem: „4 posty przy zwykłych 1,5 tygodniowo". */
    val evidence: String,
    val postsCount: Int,
    val engagementTotal: Int,
    /** Post wart otwarcia — przy werdykcie STANDOUT ten, który wystrzelił. */
    val highlight: DigestPostDto?,
    /** Pozostałe posty tygodnia, malejąco po reakcjach (bez [highlight]). */
    val posts: List<DigestPostDto>
)

/** Jedna sugestia na tydzień — co właściciel ma z tym zrobić. */
data class DigestRecommendationDto(
    val text: String,
    val reason: String
)

data class WeeklyDigestDto(
    val weekStart: String,
    val weekEnd: String,
    /** ISO-8601. String, nie Instant — payload wraca z bazy przez Jacksona i leci dalej do frontu. */
    val generatedAt: String,
    /** LLM | TEMPLATE — skąd pochodzą zdania o realizacjach. */
    val narrativeSource: String,
    val profiles: List<ProfileDigestDto>,
    val recommendation: DigestRecommendationDto?,
    /** Miejsce w grupie — jedyna metryka na tym ekranie, reszta żyje w „Porównaniu". */
    val position: PositionDto?,
    val selfUsername: String?,
    val profilesWatched: Int,
    val hasSelf: Boolean
)

/**
 * Reguły werdyktu — czysta funkcja, bo to jest cała logika produktowa tego ekranu
 * i musi dać się sprawdzić bez bazy, bez Springa i bez modelu językowego.
 *
 * Progi są te same, co w [CompetitorPulseService]: jedna definicja zdarzenia dla
 * całego modułu. Wcześniej „przyspieszenie" znaczyło co innego w liście insightów
 * (średnia z 4 tygodni) niż w pulsie (mediana z 26), więc ten sam profil bywał
 * przyspieszający na jednym ekranie i zwyczajny na drugim.
 */
object DigestRules {

    /** Post powyżej normy profilu: tyle razy ponad jego medianę zaangażowania. */
    const val STANDOUT_FACTOR = 2.5

    /** Przyspieszenie: tyle razy ponad własne tempo i co najmniej tyle postów. */
    const val ACCELERATION_FACTOR = 2.0
    const val ACCELERATION_MIN_POSTS = 2

    /** Bez tylu tygodni obserwacji i tylu postów profil nie ma wiarygodnej normy. */
    const val MIN_WEEKS_OBSERVED = 4
    const val MIN_BASELINE_POSTS = 6

    fun hasBaseline(weeksObserved: Int, baselinePostCount: Int): Boolean =
        weeksObserved >= MIN_WEEKS_OBSERVED && baselinePostCount >= MIN_BASELINE_POSTS

    /**
     * Ile tygodni historii MAMY dla profilu — liczone od najstarszego posta w bazie,
     * nie od momentu dodania profilu do studia.
     *
     * To jest różnica między „nie mamy danych" a „dopiero zaczęliśmy obserwować".
     * Zatwierdzenie profilu uruchamia `initialSync` w trybie DEEP, który dla profilu
     * bez postów schodzi na BACKFILL i ściąga rok wstecz (InstagramSyncService) —
     * profil dodany wczoraj ma więc w bazie 12 miesięcy publikacji. Liczenie normy
     * od wieku powiązania kazałoby nam wyrzucić te dane do kosza i przez cztery
     * tygodnie pisać „za krótko obserwowany" o profilu, o którym wiemy wszystko.
     *
     * Górne ograniczenie [BASELINE_WEEKS] zostaje: mediana z pół roku wystarczy,
     * a starsze tygodnie tylko rozmywają obraz zmieniającego się profilu.
     */
    fun weeksOfHistory(oldestBaselinePost: LocalDate?, windowStart: LocalDate, baselineWeeks: Int): Int {
        if (oldestBaselinePost == null) return 0
        return ChronoUnit.WEEKS.between(oldestBaselinePost, windowStart)
            .toInt()
            .coerceIn(0, baselineWeeks)
    }

    /**
     * Kolejność rozstrzygania ma znaczenie: cisza wyprzedza wszystko (nie ma czego
     * oceniać), a pojedynczy hit jest ciekawszy niż sam fakt, że ktoś opublikował
     * więcej postów.
     *
     * @param hasStandoutPost czy w tygodniu jest post ≥ [STANDOUT_FACTOR] × mediany profilu
     */
    fun decide(
        weekPostCount: Int,
        hasBaseline: Boolean,
        medianWeeklyPosts: Double,
        hasStandoutPost: Boolean
    ): DigestVerdict = when {
        weekPostCount == 0 && hasBaseline && medianWeeklyPosts >= 1.0 -> DigestVerdict.SILENT
        weekPostCount == 0 -> DigestVerdict.NEW
        hasStandoutPost -> DigestVerdict.STANDOUT
        isAccelerated(weekPostCount, hasBaseline, medianWeeklyPosts) -> DigestVerdict.ACCELERATED
        !hasBaseline -> DigestVerdict.NEW
        else -> DigestVerdict.STEADY
    }

    fun isAccelerated(weekPostCount: Int, hasBaseline: Boolean, medianWeeklyPosts: Double): Boolean =
        hasBaseline &&
            medianWeeklyPosts > 0 &&
            weekPostCount >= ACCELERATION_MIN_POSTS &&
            weekPostCount >= medianWeeklyPosts * ACCELERATION_FACTOR
}

// ── Serwis ────────────────────────────────────────────────────────────────────

/**
 * Tydzień na Instagramie: jeden wiersz na obserwowany profil.
 *
 * Zastępuje dwa ekrany, które mówiły to samo dwoma językami — listę insightów
 * („Co wymaga Twojej uwagi") i raport tygodnia, którego sekcja „Co się wydarzyło"
 * była dosłownie `overview.insights.take(4)`. Powtarzały też metryki, pozycję
 * w rankingu i braki wizytówki, więc ten sam fakt czytało się trzy razy.
 *
 * Co się zmienia w treści: liczby zostają jako DOWÓD, a nie jako komunikat.
 * „@x publikuje więcej niż zwykle: 4 posty przy tempie 1,5" nie mówi właścicielowi
 * nic, czego nie wie — interesuje go, że konkurencja zrobiła oklejenie Panamery.
 * Ta informacja siedzi w opisach postów ([InstagramPostSnapshotEntity.caption]),
 * których do tej pory nie czytaliśmy.
 *
 * Statystyka jest jedna, wzięta z [CompetitorPulseService]: mediana z
 * [CompetitorPulseService.BASELINE_WEEKS] tygodni jako norma profilu, okno raportu
 * nigdy nie pokrywa się z oknem normy. Wcześniej te same zdarzenia liczyły dwa
 * silniki o różnych progach (średnia z 4 tygodni vs mediana z 26), więc ten sam
 * profil bywał „przyspieszający" na jednym ekranie i zwyczajny na drugim.
 *
 * Koszt LLM: jedno wywołanie na generację digestu, nie na post. Digest powstaje raz
 * na tydzień i odświeża się dopiero, gdy synchronizacja przyniesie nowe dane
 * (patrz [isStale]) — model streszcza wyłącznie opisy, których sam nie zmyśli,
 * a wszystkie liczby wstawia kod.
 */
@Service
class WeeklyDigestService(
    private val studioProfileRepository: pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postRepository: InstagramPostSnapshotRepository,
    private val topicRepository: InstagramPostTopicRepository,
    private val readService: InstagramAnalyticsReadService,
    private val reportRepository: InstagramReportRepository,
    private val objectMapper: ObjectMapper,
    @Qualifier("instagramChatClient") private val chatClient: ObjectProvider<ChatClient>,
    @Value("\${instagram.digest.ai.enabled:true}") private val aiEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(WeeklyDigestService::class.java)

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM")

        /** Ile postów jednego profilu trafia do promptu i na listę. */
        private const val MAX_POSTS_PER_PROFILE = 5

        /** Opis ucinany przed wysłaniem do modelu — pierwsze zdania niosą realizację. */
        private const val CAPTION_LIMIT = 400

        /** Kolejność czytania: najpierw Ty, potem to, co w grupie najgłośniejsze. */
        private val VERDICT_ORDER = listOf(
            DigestVerdict.STANDOUT, DigestVerdict.ACCELERATED,
            DigestVerdict.SILENT, DigestVerdict.STEADY, DigestVerdict.NEW
        )
    }

    /**
     * Digest bieżącego tygodnia (poniedziałek → dziś).
     *
     * Bieżącego, nie ostatniego zamkniętego: „w tym tygodniu profil X…" ma znaczyć
     * to, co mówi. Poprzedni raport obejmował wyłącznie tygodnie zamknięte i przez
     * całe siedem dni pokazywał tydzień miniony, co wymagało osobnego akapitu
     * tłumaczącego użytkownikowi, dlaczego widzi stare dane.
     *
     * Świadomie BEZ @Transactional: czytane encje nie mają leniwych powiązań (same
     * kolumny proste), a zapis cache'u w [persist] musi móc polec po cichu. Wewnątrz
     * wspólnej transakcji połknięty wyjątek zapisu i tak wywróciłby commit —
     * dokładnie ta pułapka, którą opisuje AuditService.enrichLatestEntry.
     */
    fun digest(studioId: StudioId): WeeklyDigestDto? {
        val weekStart = MetricsCalculator.currentWeekStart()
        val links = studioProfileRepository
            .findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
        if (links.isEmpty()) return null

        val lastSyncAt = profileRepository.findAllById(links.map { it.profileId })
            .mapNotNull { it.detailsLastSyncedAt }
            .maxOrNull()

        reportRepository.findByStudioIdAndPeriodStart(studioId.value, weekStart)
            ?.takeIf { !isStale(it, lastSyncAt) }
            ?.let { readPayload(it) }
            // Nieczytelny cache (np. po zmianie kształtu DTO) nie może zostawić
            // pustego ekranu — przechodzimy wtedy do generacji, która go nadpisze.
            ?.let { return it }

        return generate(studioId, weekStart, links)
    }

    /**
     * Przeliczenie digestów po tygodniowym syncu — żeby pierwszy właściciel, który
     * wejdzie w poniedziałek, dostał gotowy ekran zamiast czekać na wywołanie LLM.
     * Błąd jednego studia nie może zatrzymać pozostałych.
     */
    fun refreshForAllStudios() {
        studioProfileRepository.findDistinctStudioIdsByStatus(InstagramProfileStatus.ACTIVE).forEach { studioId ->
            runCatching { digest(StudioId(studioId)) }
                .onFailure { log.error("Instagram digest: błąd dla studia {}: {}", studioId, it.message, it) }
        }
    }

    /**
     * Zapisany digest jest nieaktualny, gdy synchronizacja przyniosła dane po jego
     * wygenerowaniu. Bez tego warunku wpis z poniedziałku wisiałby do niedzieli
     * i tydzień „zamarzałby" na pierwszym odczycie.
     */
    private fun isStale(entity: InstagramReportEntity, lastSyncAt: Instant?): Boolean =
        lastSyncAt != null && entity.createdAt.isBefore(lastSyncAt)

    private fun readPayload(entity: InstagramReportEntity): WeeklyDigestDto? =
        runCatching { objectMapper.readValue<WeeklyDigestDto>(entity.payload) }
            .onFailure { log.warn("Instagram digest: nieczytelny payload {}, generuję od nowa", entity.id) }
            .getOrNull()

    // ── generacja ─────────────────────────────────────────────────────────────

    private fun generate(
        studioId: StudioId,
        weekStart: LocalDate,
        links: List<StudioInstagramProfileEntity>
    ): WeeklyDigestDto {
        val today = LocalDate.now(ZoneOffset.UTC)
        val profileIds = links.map { it.profileId }
        val profiles = profileRepository.findAllById(profileIds).associateBy { it.id }

        val baselineStart = weekStart.minusWeeks(CompetitorPulseService.BASELINE_WEEKS.toLong())
        val weekStartInstant = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant()
        val allPosts = postRepository
            .findByProfileIdInAndTakenAtAfter(profileIds, baselineStart.atStartOfDay(ZoneOffset.UTC).toInstant())
            .groupBy { it.profileId }
        val topics = allPosts.values.flatten().map { it.id }
            .takeIf { it.isNotEmpty() }
            ?.let { topicRepository.findByPostIdIn(it).associateBy { topic -> topic.postId } }
            ?: emptyMap()

        val rows = links.mapNotNull { link ->
            val profile = profiles[link.profileId] ?: return@mapNotNull null
            val posts = allPosts[link.profileId] ?: emptyList()
            buildRow(
                link = link,
                username = profile.username,
                weekPosts = posts.filter { it.takenAt >= weekStartInstant }.sortedByDescending { engagementOf(it) },
                baselinePosts = posts.filter { it.takenAt < weekStartInstant },
                topics = topics,
                weekStart = weekStart
            )
        }

        val (enriched, source) = describeAchievements(rows, allPosts, topics, weekStartInstant)

        // Jeden odczyt agregatu na generację: pozycja i sugestia wychodzą z tych samych liczb.
        val overview = runCatching {
            readService.overview(studioId, InstagramAnalyticsReadService.DEFAULT_WEEKS)
        }.getOrNull()

        val ordered = enriched.sortedWith(
            compareByDescending<ProfileDigestDto> { it.isSelf }
                .thenBy { VERDICT_ORDER.indexOf(DigestVerdict.valueOf(it.verdict)) }
                .thenByDescending { it.engagementTotal }
        )

        val generatedAt = Instant.now()
        val digest = WeeklyDigestDto(
            weekStart = weekStart.toString(),
            weekEnd = today.toString(),
            generatedAt = generatedAt.toString(),
            narrativeSource = source,
            profiles = ordered,
            recommendation = overview?.let(::buildRecommendation),
            position = overview?.position,
            selfUsername = overview?.selfUsername,
            profilesWatched = links.size,
            hasSelf = links.any { it.isSelf }
        )

        persist(studioId, weekStart, today, digest)
        return digest
    }

    /** Zapis to cache, nie dane użytkownika — awaria zapisu nie może zabrać treści z ekranu. */
    private fun persist(studioId: StudioId, weekStart: LocalDate, today: LocalDate, digest: WeeklyDigestDto) {
        try {
            reportRepository.findByStudioIdAndPeriodStart(studioId.value, weekStart)?.let {
                // Unikalny indeks (studio_id, period_start): DELETE musi trafić do bazy
                // przed INSERT-em, inaczej Hibernate potrafi wysłać je w odwrotnej kolejności.
                reportRepository.delete(it)
                reportRepository.flush()
            }
            reportRepository.save(
                InstagramReportEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    periodStart = weekStart,
                    periodEnd = today,
                    title = "Tydzień ${DATE_FMT.format(weekStart)}–${DATE_FMT.format(today)}",
                    lead = "",
                    narrativeSource = digest.narrativeSource,
                    payload = objectMapper.writeValueAsString(digest),
                    createdAt = Instant.parse(digest.generatedAt)
                )
            )
        } catch (e: Exception) {
            log.warn("Instagram digest: nie udało się zapisać cache dla studia {}: {}", studioId, e.message)
        }
    }

    // ── werdykt profilu ───────────────────────────────────────────────────────

    private fun buildRow(
        link: StudioInstagramProfileEntity,
        username: String,
        weekPosts: List<InstagramPostSnapshotEntity>,
        baselinePosts: List<InstagramPostSnapshotEntity>,
        topics: Map<UUID, pl.detailing.crm.instagram.infrastructure.InstagramPostTopicEntity>,
        weekStart: LocalDate
    ): ProfileDigestDto {
        val weeksObserved = DigestRules.weeksOfHistory(
            oldestBaselinePost = baselinePosts.minOfOrNull { it.takenAt }
                ?.atZone(ZoneOffset.UTC)?.toLocalDate(),
            windowStart = weekStart,
            baselineWeeks = CompetitorPulseService.BASELINE_WEEKS
        )
        val hasBaseline = DigestRules.hasBaseline(weeksObserved, baselinePosts.size)

        val medianEngagement = MetricsCalculator.median(baselinePosts.map { engagementOf(it).toDouble() })
        val medianWeeklyPosts = medianWeeklyPosts(baselinePosts, weekStart, weeksObserved)

        val standout = if (hasBaseline && medianEngagement != null && medianEngagement > 0.0) {
            weekPosts.firstOrNull { engagementOf(it) >= medianEngagement * DigestRules.STANDOUT_FACTOR }
        } else null

        val verdict = DigestRules.decide(
            weekPostCount = weekPosts.size,
            hasBaseline = hasBaseline,
            medianWeeklyPosts = medianWeeklyPosts,
            hasStandoutPost = standout != null
        )

        val who = if (link.isSelf) "Ty" else "@$username"
        val headline = when (verdict) {
            DigestVerdict.SILENT ->
                if (link.isSelf) "Nie dodałeś w tym tygodniu żadnego posta"
                else "$who nie dodał żadnego posta"
            DigestVerdict.STANDOUT ->
                if (weekPosts.size == 1) "$who dodał jeden post, ale z dużym zaangażowaniem"
                else "$who ma post wyraźnie powyżej swojej normy"
            DigestVerdict.ACCELERATED -> "$who zwiększył aktywność"
            DigestVerdict.STEADY -> "$who publikuje w swoim zwykłym rytmie"
            DigestVerdict.NEW ->
                if (weekPosts.isEmpty()) "$who bez publikacji w tym tygodniu"
                else "$who — ${weekPosts.size} ${postWord(weekPosts.size)} w tym tygodniu"
        }

        val evidence = buildEvidence(verdict, weekPosts, standout, medianEngagement, medianWeeklyPosts, hasBaseline)

        val highlight = standout?.let { toPostDto(it, topics) }
        val rest = weekPosts
            .filter { it.id != standout?.id }
            .take(MAX_POSTS_PER_PROFILE)
            .map { toPostDto(it, topics) }

        return ProfileDigestDto(
            profileId = link.profileId.toString(),
            username = username,
            isSelf = link.isSelf,
            verdict = verdict.name,
            headline = headline,
            achievements = null, // uzupełniane w describeAchievements
            evidence = evidence,
            postsCount = weekPosts.size,
            engagementTotal = weekPosts.sumOf { engagementOf(it) },
            highlight = highlight,
            posts = rest
        )
    }

    private fun buildEvidence(
        verdict: DigestVerdict,
        weekPosts: List<InstagramPostSnapshotEntity>,
        standout: InstagramPostSnapshotEntity?,
        medianEngagement: Double?,
        medianWeeklyPosts: Double,
        hasBaseline: Boolean
    ): String = when (verdict) {
        DigestVerdict.SILENT ->
            "Zwykle publikuje ${formatDecimal(medianWeeklyPosts)} ${postWord(medianWeeklyPosts.roundToInt())} tygodniowo."

        DigestVerdict.STANDOUT -> {
            val engagement = standout?.let { engagementOf(it) } ?: 0
            val median = medianEngagement?.roundToInt() ?: 0
            "$engagement ${reactionWord(engagement)} przy zwykłych $median — " +
                "${formatDecimal(engagement / (medianEngagement ?: 1.0))}× więcej niż typowy post tego profilu."
        }

        DigestVerdict.ACCELERATED ->
            "${weekPosts.size} ${postWord(weekPosts.size)} w tym tygodniu przy zwykłym tempie " +
                "${formatDecimal(medianWeeklyPosts)} tygodniowo."

        DigestVerdict.STEADY -> {
            val total = weekPosts.sumOf { engagementOf(it) }
            "${weekPosts.size} ${postWord(weekPosts.size)}, łącznie $total ${reactionWord(total)}" +
                if (hasBaseline && medianEngagement != null)
                    " — zwykle ${medianEngagement.roundToInt()} na post."
                else "."
        }

        DigestVerdict.NEW ->
            if (weekPosts.isEmpty()) "Za krótko obserwowany, żeby ocenić, czy to nietypowe."
            else "${weekPosts.size} ${postWord(weekPosts.size)}. Za mało historii, żeby porównać z normą profilu."
    }

    // ── co faktycznie zrobili (opisy postów) ──────────────────────────────────

    /**
     * Zamienia opisy postów w jedno zdanie na profil.
     *
     * Model dostaje wyłącznie opisy i etykiety tematów — żadnych liczb, więc nie ma
     * czego zmyślić w metrykach; te wstawia kod w [buildEvidence]. Przy braku klucza,
     * błędzie albo pustej odpowiedzi schodzimy na etykiety tematów, które i tak są
     * policzone deterministycznie przez [TopicClassificationService].
     */
    private fun describeAchievements(
        rows: List<ProfileDigestDto>,
        allPosts: Map<UUID, List<InstagramPostSnapshotEntity>>,
        topics: Map<UUID, pl.detailing.crm.instagram.infrastructure.InstagramPostTopicEntity>,
        weekStartInstant: Instant
    ): Pair<List<ProfileDigestDto>, String> {
        val material = rows.associateWith { row ->
            val posts = allPosts[UUID.fromString(row.profileId)].orEmpty()
                .filter { it.takenAt >= weekStartInstant }
                .sortedByDescending { engagementOf(it) }
                .take(MAX_POSTS_PER_PROFILE)
            posts.mapNotNull { post ->
                val caption = post.caption?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                mapOf(
                    "temat" to InstagramPostTopic.labelFor(topics[post.id]?.topic),
                    "opis" to caption.take(CAPTION_LIMIT)
                )
            }
        }.filterValues { it.isNotEmpty() }

        if (material.isEmpty()) return rows to "TEMPLATE"

        val fromLlm = if (aiEnabled) askLlm(material) else null
        if (fromLlm != null) {
            return rows.map { row -> row.copy(achievements = fromLlm[row.username]?.takeIf { it.isNotBlank() }) } to "LLM"
        }

        return rows.map { row -> row.copy(achievements = topicFallback(material[row])) } to "TEMPLATE"
    }

    private fun askLlm(material: Map<ProfileDigestDto, List<Map<String, String>>>): Map<String, String>? {
        val client = chatClient.ifAvailable ?: return null
        val input = material.entries.associate { (row, posts) -> row.username to posts }

        return try {
            val answer = client.prompt()
                .system(
                    """
                    Czytasz opisy postów studiów auto detailingu z ostatniego tygodnia.
                    Dla każdego profilu napisz JEDNO krótkie zdanie po polsku o tym, jakie
                    realizacje wykonano — z markami i modelami aut, jeśli padły w opisie
                    (np. "Zrealizował oklejenie Porsche Panamery i detailing wnętrza Lamborghini").

                    Zasady:
                    - Opieraj się WYŁĄCZNIE na opisach. Nie dopisuj usług ani aut, których tam nie ma.
                    - Żadnych liczb, procentów, ocen skuteczności ani zachęt marketingowych.
                    - Gdy opisy nie mówią o żadnej realizacji (same hasztagi, zaproszenia, cytaty),
                      zwróć dla tego profilu pusty string.
                    - Odpowiedz WYŁĄCZNIE obiektem JSON: nazwa profilu → zdanie. Bez komentarza,
                      bez bloku kodu.
                    """.trimIndent()
                )
                .user(objectMapper.writeValueAsString(input))
                .call()
                .content()
                ?.trim()
                ?.removeSurrounding("```json", "```")
                ?.removeSurrounding("```")
                ?.trim()

            if (answer.isNullOrBlank()) null
            else objectMapper.readValue<Map<String, String>>(answer)
        } catch (e: Exception) {
            log.warn("Instagram digest: LLM niedostępny, schodzę na etykiety tematów: {}", e.message)
            null
        }
    }

    /** Bez modelu zostają same tematy: „Powłoka ceramiczna, Detailing wnętrza". */
    private fun topicFallback(posts: List<Map<String, String>>?): String? {
        val labels = posts.orEmpty()
            .mapNotNull { it["temat"] }
            .filter { it != InstagramPostTopic.INNE.labelPl }
            .distinct()
        return labels.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    // ── sugestia ──────────────────────────────────────────────────────────────

    /**
     * Jedna sugestia na tydzień — jedyna rzecz z dawnego raportu, która niosła coś
     * ponad Przegląd. Liczby bierze z tego samego agregatu co reszta modułu.
     */
    private fun buildRecommendation(overview: OverviewResponse): DigestRecommendationDto? {
        val ppw = overview.postsPerWeek
        if (overview.hasSelf && ppw.value != null && ppw.benchmark != null && ppw.value < ppw.benchmark / 2) {
            return DigestRecommendationDto(
                text = "Zaplanuj 2–3 posty na najbliższy tydzień — najprościej zdjęcia z bieżących realizacji.",
                reason = "Publikujesz ${"%.1f".format(ppw.value)} posta/tydz. przy ${"%.1f".format(ppw.benchmark)} " +
                    "u typowego studia z Twojej grupy. Regularność to najtańszy sposób na większe zasięgi."
            )
        }

        overview.storefront?.gaps?.firstOrNull()?.let {
            return DigestRecommendationDto(
                text = it,
                reason = "Kompletna wizytówka profilu to pierwsza rzecz, którą widzi klient porównujący studia."
            )
        }

        return null
    }

    // ── pomocnicze ────────────────────────────────────────────────────────────

    private fun toPostDto(
        post: InstagramPostSnapshotEntity,
        topics: Map<UUID, pl.detailing.crm.instagram.infrastructure.InstagramPostTopicEntity>
    ) = DigestPostDto(
        permalink = "https://www.instagram.com/p/${post.postCode}/",
        format = MetricsCalculator.formatBucket(post.productType),
        topicLabel = InstagramPostTopic.labelFor(topics[post.id]?.topic),
        engagement = engagementOf(post),
        takenAt = DATE_FMT.format(post.takenAt.atZone(ZoneOffset.UTC).toLocalDate())
    )

    /** Mediana liczby postów na tydzień; tygodnie bez publikacji liczą się jako zero. */
    private fun medianWeeklyPosts(
        baselinePosts: List<InstagramPostSnapshotEntity>,
        weekStart: LocalDate,
        weeksObserved: Int
    ): Double {
        if (weeksObserved < 1) return 0.0
        val dates = baselinePosts.map { it.takenAt.atZone(ZoneOffset.UTC).toLocalDate() }
        val counts = (0 until weeksObserved).map { offset ->
            val end = weekStart.minusWeeks(offset.toLong())
            val start = end.minusWeeks(1)
            dates.count { it >= start && it < end }.toDouble()
        }
        return MetricsCalculator.median(counts) ?: 0.0
    }

    private fun engagementOf(post: InstagramPostSnapshotEntity): Int = post.likeCount + post.commentCount

    private fun formatDecimal(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else "%.1f".format(value).replace('.', ',')

    private fun reactionWord(count: Int): String = when {
        count == 1 -> "reakcja"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "reakcje"
        else -> "reakcji"
    }

    private fun postWord(count: Int): String = when {
        count == 1 -> "post"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "posty"
        else -> "postów"
    }
}
