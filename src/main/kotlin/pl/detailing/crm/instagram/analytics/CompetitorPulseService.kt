package pl.detailing.crm.instagram.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// ── DTO ───────────────────────────────────────────────────────────────────────

/**
 * Pojedyncze zdarzenie.
 *
 * [kind]: FOLLOWER_SPIKE | FOLLOWER_DROP | NEW_TOPIC | SLOWDOWN.
 *
 * Zostały cztery rodzaje i to jest cały kontrakt. Sześć wcześniejszych („twój post",
 * „nie opublikowałeś nic", przyspieszenie tempa, post ponad normę, uruchomienie
 * i zakończenie kampanii) mówiło dokładnie to, co wiersz profilu w zakładce Tydzień —
 * tyle że w osobnej sekcji, pod innym tytułem i w innej zakładce. Sekcja zniknęła,
 * więc znikają i zdarzenia, które istniały wyłącznie dla niej.
 *
 * Te cztery zostają, bo wiersz tygodnia nie zna żadnego z nich: on patrzy na posty
 * i ich zaangażowanie, a nie na liczbę obserwujących ani na to, o czym profil
 * zaczął nagle mówić.
 */
data class PulseEventDto(
    val kind: String,
    val isSelf: Boolean,
    val username: String,
    val headline: String,
    val detail: String,
    /** Link do posta, gdy zdarzenie dotyczy konkretnej publikacji. */
    val permalink: String?,
    val occurredAt: String
)

data class CompetitorPulseDto(
    val events: List<PulseEventDto>,
    val windowFrom: String,
    val windowTo: String,
    /** Ile tygodni historii posłużyło za punkt odniesienia dla „normy" profilu. */
    val baselineWeeks: Int,
    val hasSelfProfile: Boolean,
    val profilesWatched: Int
)

// ── Serwis ────────────────────────────────────────────────────────────────────

/**
 * „Puls tygodnia" — co się wydarzyło u obserwowanych profili.
 *
 * Dlaczego zdarzenia, a nie analiza wzorców:
 * przy ~1,5 posta tygodniowo na profil tygodniowe okno daje kilka postów na całą grupę.
 * To za mało na jakikolwiek wniosek o skuteczności tematów czy formatów — takie wnioski
 * wymagają kwartału w górę. Ale w zupełności wystarczy na FAKTY: kto opublikował, ile,
 * z jakim wynikiem, komu skoczyli obserwujący.
 *
 * Kluczowa zasada: **okno raportu ≠ okno normy**. Raportujemy ostatnie dni, ale każdą
 * wartość zestawiamy z medianą tego profilu z [BASELINE_WEEKS] tygodni. Porównanie jednej
 * obserwacji do dobrze oszacowanej normy jest poprawne; szacowanie normy z jednej
 * obserwacji nie jest — i tego właśnie tutaj nie robimy.
 *
 * Całość liczy kod. Żadnego modelu językowego: zdarzenie to fakt do policzenia,
 * nie tekst do napisania — więc nie ma tu ani kosztu, ani limitu, ani ryzyka konfabulacji.
 */
@Service
class CompetitorPulseService(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postRepository: InstagramPostSnapshotRepository,
    private val topicRepository: InstagramPostTopicRepository,
    private val statsRepository: InstagramProfileStatsWeeklyRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository
) {

    companion object {
        /** Okno, z którego liczymy „normę" profilu. Nigdy nie pokrywa się z oknem raportu. */
        const val BASELINE_WEEKS = 26

        // Próg wiarygodnej normy (ile tygodni historii, ile postów) mieszka w
        // DigestRules — jedna definicja zdarzenia dla całego modułu.

        /** Cisza: tyle tygodni bez publikacji u profilu, który normalnie publikuje. */
        private const val SILENCE_WEEKS = 3

        /** Ruch obserwujących: tyle razy powyżej typowego tygodnia i co najmniej tyle osób. */
        private const val FOLLOWER_FACTOR = 3.0
        private const val FOLLOWER_MIN_ABS = 10

        private const val MAX_EVENTS = 14

        /**
         * Kolejność wyświetlania. Cztery rodzaje, bo tylko tyle zostało: reszta
         * („twój post", „nie opublikowałeś nic", przyspieszenie tempa, post ponad
         * normę, start i koniec kampanii) dublowała wiersz profilu w zakładce Tydzień
         * i została stamtąd wycięta razem z sekcją, która ją pokazywała.
         *
         * Najpierw to, co mówi o pieniądzach i zasięgu (ruch obserwujących), potem
         * zmiana w tym, o czym konkurent mówi, na końcu spowolnienie — fakt prawdziwy,
         * ale najwolniej zmieniający cokolwiek.
         */
        private val KIND_ORDER = listOf("FOLLOWER_SPIKE", "FOLLOWER_DROP", "NEW_TOPIC", "SLOWDOWN")

        /** Tematy porządkowe — „pierwszy post o Inne" nie jest zdarzeniem. */
        private val NON_SERVICE_TOPICS = setOf("INNE", "PROMOCJA", "KONKURS")

        private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM")

    }

    @Transactional(readOnly = true)
    fun pulse(studioId: StudioId, weeks: Int): CompetitorPulseDto {
        val links = studioProfileRepository.findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)

        val today = LocalDate.now(ZoneOffset.UTC)
        val windowStart = today.minusDays(7L * weeks)

        if (links.isEmpty()) {
            return CompetitorPulseDto(
                events = emptyList(),
                windowFrom = DATE_FMT.format(windowStart),
                windowTo = DATE_FMT.format(today),
                baselineWeeks = BASELINE_WEEKS,
                hasSelfProfile = false,
                profilesWatched = 0
            )
        }

        val profileIds = links.map { it.profileId }
        val profiles = profileRepository.findAllById(profileIds).associateBy { it.id }

        val baselineStart = windowStart.minusWeeks(BASELINE_WEEKS.toLong())
        val windowStartInstant = windowStart.atStartOfDay(ZoneOffset.UTC).toInstant()
        val baselineStartInstant = baselineStart.atStartOfDay(ZoneOffset.UTC).toInstant()

        val postsByProfile = postRepository
            .findByProfileIdInAndTakenAtAfter(profileIds, baselineStartInstant)
            .groupBy { it.profileId }
        val postIds = postsByProfile.values.flatten().map { it.id }
        val topics = if (postIds.isEmpty()) emptyMap()
        else topicRepository.findByPostIdIn(postIds).associateBy { it.postId }
        val weeklyStatsByProfile = statsRepository
            .findByProfileIdInAndWeekStartGreaterThanEqual(profileIds, baselineStart)
            .groupBy { it.profileId }
        val snapshotsByProfile = metricsRepository
            .findByProfileIdInAndSnapshotDateAfterOrderBySnapshotDateAsc(profileIds, baselineStart)
            .groupBy { it.profileId }

        val events = mutableListOf<PulseEventDto>()

        links.forEach { link ->
            val username = profiles[link.profileId]?.username ?: return@forEach
            val posts = postsByProfile[link.profileId] ?: emptyList()
            val baselinePosts = posts.filter { it.takenAt < windowStartInstant }
            val windowPosts = posts.filter { it.takenAt >= windowStartInstant }.sortedBy { it.takenAt }

            // Historia liczona od najstarszego POSTA, nie od wieku powiązania: profil
            // zatwierdzony wczoraj ma w bazie rok publikacji (initialSync → BACKFILL),
            // a liczenie normy od daty dodania kazałoby te dane zignorować.
            // Wspólna definicja z digestem — patrz DigestRules.weeksOfHistory.
            val weeksObserved = DigestRules.weeksOfHistory(
                oldestBaselinePost = baselinePosts.minOfOrNull { it.takenAt }
                    ?.atZone(ZoneOffset.UTC)?.toLocalDate(),
                windowStart = windowStart,
                baselineWeeks = BASELINE_WEEKS
            )
            val hasBaseline = DigestRules.hasBaseline(weeksObserved, baselinePosts.size)

                val medianWeeklyPosts = medianWeeklyPosts(baselinePosts, windowStart, weeksObserved)

            /*
             * Własny profil nie generuje zdarzeń o publikacjach. Wszystko, co puls
             * miał o nim do powiedzenia — „twój post", „nie opublikowałeś nic" —
             * mówi już wiersz w zakładce Tydzień, i mówi to pełnym zdaniem z werdyktem.
             * Zostają mu wyłącznie ruchy obserwujących, których tamten wiersz nie zna.
             */
            if (!link.isSelf) {
                events += competitorEvents(
                    username = username,
                    windowPosts = windowPosts,
                    baselinePosts = baselinePosts,
                    topics = topics,
                    medianWeeklyPosts = medianWeeklyPosts,
                    hasBaseline = hasBaseline,
                    today = today
                )
            }

            events += followerEvents(
                username = username,
                isSelf = link.isSelf,
                snapshots = snapshotsByProfile[link.profileId] ?: emptyList(),
                weeklyStats = weeklyStatsByProfile[link.profileId] ?: emptyList(),
                windowStart = windowStart,
                hasBaseline = hasBaseline,
                today = today
            )
        }

        val ordered = events
            .sortedWith(
                compareBy<PulseEventDto> { KIND_ORDER.indexOf(it.kind).takeIf { i -> i >= 0 } ?: KIND_ORDER.size }
                    .thenByDescending { it.occurredAt }
            )
            .take(MAX_EVENTS)

        return CompetitorPulseDto(
            events = ordered,
            windowFrom = DATE_FMT.format(windowStart),
            windowTo = DATE_FMT.format(today),
            baselineWeeks = BASELINE_WEEKS,
            hasSelfProfile = links.any { it.isSelf },
            profilesWatched = links.size
        )
    }

    // ── Konkurencja ───────────────────────────────────────────────────────────

    private fun competitorEvents(
        username: String,
        windowPosts: List<InstagramPostSnapshotEntity>,
        baselinePosts: List<InstagramPostSnapshotEntity>,
        topics: Map<UUID, InstagramPostTopicEntity>,
        medianWeeklyPosts: Double,
        hasBaseline: Boolean,
        today: LocalDate
    ): List<PulseEventDto> {
        val events = mutableListOf<PulseEventDto>()

        // Cisza: profil, który normalnie publikuje, milczy od kilku tygodni.
        if (hasBaseline && medianWeeklyPosts >= 1.0 && windowPosts.isEmpty()) {
            val lastPost = baselinePosts.maxByOrNull { it.takenAt }
            val silentWeeks = lastPost
                ?.let { ChronoUnit.WEEKS.between(it.takenAt.atZone(ZoneOffset.UTC).toLocalDate(), today).toInt() }
                ?: 0
            if (silentWeeks >= SILENCE_WEEKS) {
                events += PulseEventDto(
                    kind = "SLOWDOWN",
                    isSelf = false,
                    username = username,
                    headline = "@$username milczy od $silentWeeks ${weekWord(silentWeeks)}",
                    detail = "Wcześniej publikował około ${formatDecimal(medianWeeklyPosts)} ${postWord(medianWeeklyPosts)} tygodniowo.",
                    permalink = null,
                    occurredAt = lastPost?.let { dateOf(it) } ?: ""
                )
            }
        }

        // Nowy temat: usługa, o której ten profil nie mówił przez całe okno bazowe.
        if (hasBaseline) {
            val knownTopics = baselinePosts.mapNotNull { topics[it.id]?.topic }.toSet()
            windowPosts
                .mapNotNull { post -> topics[post.id]?.topic?.let { post to it } }
                .filter { (_, topic) -> topic !in NON_SERVICE_TOPICS && topic !in knownTopics }
                .distinctBy { (_, topic) -> topic }
                .take(2)
                .forEach { (post, topic) ->
                    events += PulseEventDto(
                        kind = "NEW_TOPIC",
                        isSelf = false,
                        username = username,
                        headline = "@$username pierwszy raz o: ${InstagramPostTopic.labelFor(topic)}",
                        detail = "Nie poruszał tego tematu przez ostatnie $BASELINE_WEEKS ${weekWord(BASELINE_WEEKS)}.",
                        permalink = permalinkOf(post),
                        occurredAt = dateOf(post)
                    )
                }
        }

        return events
    }

    // ── Ruch obserwujących ────────────────────────────────────────────────────

    private fun followerEvents(
        username: String,
        isSelf: Boolean,
        snapshots: List<InstagramProfileMetricsSnapshotEntity>,
        weeklyStats: List<InstagramProfileStatsWeeklyEntity>,
        windowStart: LocalDate,
        hasBaseline: Boolean,
        today: LocalDate
    ): List<PulseEventDto> {
        if (!hasBaseline) return emptyList()

        val inWindow = snapshots.filter { it.snapshotDate >= windowStart && it.followerCount != null }
        if (inWindow.size < 2) return emptyList()

        val delta = (inWindow.last().followerCount ?: return emptyList()) -
            (inWindow.first().followerCount ?: return emptyList())

        val typical = MetricsCalculator.median(
            weeklyStats.filter { it.weekStart < windowStart }.mapNotNull { it.followerDelta?.toDouble() }
        ) ?: return emptyList()

        val threshold = maxOf(FOLLOWER_MIN_ABS.toDouble(), abs(typical) * FOLLOWER_FACTOR)
        if (abs(delta) < threshold) return emptyList()

        val who = if (isSelf) "U ciebie" else "@$username"
        return listOf(
            if (delta > 0) PulseEventDto(
                kind = "FOLLOWER_SPIKE",
                isSelf = isSelf,
                username = username,
                headline = "$who wyraźny przyrost obserwujących",
                detail = "+$delta w tym okresie, przy typowym tygodniu ${formatSigned(typical)}.",
                permalink = null,
                occurredAt = DATE_FMT.format(today)
            ) else PulseEventDto(
                kind = "FOLLOWER_DROP",
                isSelf = isSelf,
                username = username,
                headline = "$who spadek obserwujących",
                detail = "$delta w tym okresie, przy typowym tygodniu ${formatSigned(typical)}.",
                permalink = null,
                occurredAt = DATE_FMT.format(today)
            )
        )
    }

    // ── Pomocnicze ────────────────────────────────────────────────────────────

    /** Mediana liczby postów na tydzień; tygodnie bez publikacji liczą się jako zero. */
    private fun medianWeeklyPosts(
        baselinePosts: List<InstagramPostSnapshotEntity>,
        windowStart: LocalDate,
        weeksObserved: Int
    ): Double {
        if (weeksObserved < 1) return 0.0
        val dates = baselinePosts.map { it.takenAt.atZone(ZoneOffset.UTC).toLocalDate() }
        val counts = (0 until weeksObserved).map { offset ->
            val end = windowStart.minusWeeks(offset.toLong())
            val start = end.minusWeeks(1)
            dates.count { it >= start && it < end }.toDouble()
        }
        return MetricsCalculator.median(counts) ?: 0.0
    }

    private fun engagementOf(post: InstagramPostSnapshotEntity): Int = post.likeCount + post.commentCount

    private fun permalinkOf(post: InstagramPostSnapshotEntity): String =
        "https://www.instagram.com/p/${post.postCode}/"

    private fun dateOf(post: InstagramPostSnapshotEntity): String =
        DATE_FMT.format(post.takenAt.atZone(ZoneOffset.UTC).toLocalDate())

    private fun formatDecimal(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else "%.1f".format(value).replace('.', ',')

    private fun formatSigned(value: Double): String {
        val rounded = value.roundToInt()
        return if (rounded > 0) "+$rounded" else rounded.toString()
    }

    private fun postWord(count: Double): String {
        val n = count.roundToInt()
        return when {
            n == 1 -> "post"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "posty"
            else -> "postów"
        }
    }

    private fun weekWord(count: Int): String = when {
        count == 1 -> "tygodnia"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "tygodni"
        else -> "tygodni"
    }
}
