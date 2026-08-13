package pl.detailing.crm.instagram.analytics

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.*
import pl.detailing.crm.shared.InstagramProfileStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.ZoneOffset
import java.util.*
import kotlin.math.abs

/**
 * Silnik insightów: deterministyczne detektory z jawnymi progami.
 * Zamienia surowe dane w gotowe wnioski w prostym polskim:
 * "co się stało → dlaczego to ważne → co możesz zrobić".
 *
 * Zasady anty-szumowe:
 * - dedup po (studio, dedup_key) – ten sam wniosek nie powstanie dwa razy,
 * - twardy limit nowych insightów per studio per tydzień ([WEEKLY_CAP]),
 *   kandydaci sortowani wg ważności,
 * - hipotezy (np. "podejrzenie kupionych obserwujących") zawsze oznaczone
 *   jako przypuszczenie w treści.
 */
@Service
class InsightEngine(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postRepository: InstagramPostSnapshotRepository,
    private val topicRepository: InstagramPostTopicRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val statsRepository: InstagramProfileStatsWeeklyRepository,
    private val insightRepository: InstagramInsightRepository
) {
    private val log = LoggerFactory.getLogger(InsightEngine::class.java)

    companion object {
        const val WEEKLY_CAP = 5

        private val SEVERITY_ORDER = mapOf("HIGH" to 0, "MEDIUM" to 1, "LOW" to 2)
        private val DATE_FMT = DateTimeFormatter.ofPattern("d.MM").withZone(ZoneOffset.UTC)
    }

    private data class Candidate(
        val type: String,
        val severity: String,
        val title: String,
        val body: String,
        val action: String,
        val dedupKey: String,
        val profileId: UUID? = null,
        val postId: UUID? = null,
        val probableCause: String? = null
    )

    /** Pełny przebieg po niedzielnym syncu – wszystkie detektory. */
    fun runWeeklyForAllStudios() {
        studioProfileRepository.findDistinctStudioIdsByStatus(InstagramProfileStatus.ACTIVE)
            .forEach { studioId -> runSafely(studioId, daily = false) }
    }

    /** Lekki przebieg po dziennym syncu – tylko detektory świeżych zdarzeń (promocje, viral). */
    fun runDailyForAllStudios() {
        studioProfileRepository.findDistinctStudioIdsByStatus(InstagramProfileStatus.ACTIVE)
            .forEach { studioId -> runSafely(studioId, daily = true) }
    }

    fun runSafely(studioId: UUID, daily: Boolean) {
        try {
            runForStudio(studioId, daily)
        } catch (e: Exception) {
            log.error("Instagram insighty: błąd dla studia {}: {}", studioId, e.message, e)
        }
    }

    @Transactional
    fun runForStudio(studioId: UUID, daily: Boolean) {
        val links = studioProfileRepository.findByStudioIdAndStatus(studioId, InstagramProfileStatus.ACTIVE)
        if (links.isEmpty()) return

        val profiles = profileRepository.findAllById(links.map { it.profileId }).associateBy { it.id }
        val selfProfileId = links.firstOrNull { it.isSelf }?.profileId
        val competitorIds = links.filter { !it.isSelf }.map { it.profileId }

        val now = Instant.now()
        val weekStart = MetricsCalculator.currentWeekStart()
        val candidates = mutableListOf<Candidate>()

        // Statystyki 12 tygodni wstecz – wspólne wejście większości detektorów
        val statsWindow = statsRepository.findByProfileIdInAndWeekStartGreaterThanEqual(
            links.map { it.profileId }, weekStart.minusWeeks(12)
        ).groupBy { it.profileId }

        // ── Detektory świeżych zdarzeń (dzienne i tygodniowe) ──────────────────
        val freshCutoff = now.minus(if (daily) 3 else 8, ChronoUnit.DAYS)
        val freshPosts = if (competitorIds.isEmpty()) emptyList()
        else postRepository.findByProfileIdInAndTakenAtAfter(competitorIds, freshCutoff)
        val topicsByPost = topicRepository.findByPostIdIn(freshPosts.map { it.id }).associateBy { it.postId }

        freshPosts.forEach { post ->
            val profile = profiles[post.profileId] ?: return@forEach
            val topic = topicsByPost[post.id]

            if (topic?.isPromo == true) {
                val discount = topic.discountPct?.let { " (−$it%)" } ?: ""
                candidates += Candidate(
                    type = "PROMO_DETECTED",
                    severity = "HIGH",
                    title = "@${profile.username} ogłasza promocję$discount",
                    body = "W poście z ${DATE_FMT.format(post.takenAt)} pojawiła się oferta promocyjna" +
                        "${topicLabelSuffix(topic)}. Klienci z Twojej okolicy właśnie ją widzą.",
                    action = "Zajrzyj do posta i zdecyduj, czy odpowiadasz własną ofertą – " +
                        "np. pakietem usług albo bonusem zamiast obniżki ceny.",
                    dedupKey = "PROMO:${post.postPk}",
                    profileId = post.profileId,
                    postId = post.id
                )
            }

            if (topic?.isContest == true) {
                candidates += Candidate(
                    type = "CONTEST_DETECTED",
                    severity = "MEDIUM",
                    title = "@${profile.username} organizuje konkurs",
                    body = "Konkursy szybko zwiększają zasięgi i liczbę obserwujących. " +
                        "Post pojawił się ${DATE_FMT.format(post.takenAt)}.",
                    action = "Obserwuj wyniki – jeśli konkurs \"zaskoczy\", warto rozważyć podobną akcję u siebie.",
                    dedupKey = "CONTEST:${post.postPk}",
                    profileId = post.profileId,
                    postId = post.id
                )
            }

            // Viral: zaangażowanie posta znacząco powyżej normy tego profilu
            val profileStats = statsWindow[post.profileId] ?: emptyList()
            val baselinePosts = profileStats.sumOf { it.postCount }
            val baselineEngagement = profileStats.sumOf { (it.totalLikes + it.totalComments).toLong() }
            if (baselinePosts >= 8) {
                val baselinePerPost = baselineEngagement.toDouble() / baselinePosts
                val postEngagement = (post.likeCount + post.commentCount).toDouble()
                if (baselinePerPost > 0 && postEngagement >= 30 && postEngagement > baselinePerPost * 3) {
                    val cause = viralCause(post, topic, profileStats)
                    candidates += Candidate(
                        type = "VIRAL_POST",
                        severity = "HIGH",
                        title = "Post @${profile.username} zebrał ${"%.0f".format(postEngagement / baselinePerPost)}× więcej reakcji niż zwykle",
                        body = "Post z ${DATE_FMT.format(post.takenAt)} ma ${post.likeCount + post.commentCount} reakcji " +
                            "przy średniej ok. ${baselinePerPost.toInt()} u tego profilu. ${causeSentence(cause)}",
                        action = "Otwórz post i zobacz, co zadziałało – temat, format, sposób pokazania efektu. " +
                            "To gotowa inspiracja na Twój następny post.",
                        dedupKey = "VIRAL:${post.postPk}",
                        profileId = post.profileId,
                        postId = post.id,
                        probableCause = cause
                    )
                }
            }
        }

        // ── Detektory tygodniowe ───────────────────────────────────────────────
        if (!daily) {
            detectFollowerJumps(links, profiles, now, weekStart, candidates)
            detectCadenceShifts(links, profiles, statsWindow, weekStart, candidates)
            detectFormatTrend(competitorIds, now, weekStart, candidates)
            detectSelfInsights(selfProfileId, profiles, statsWindow, weekStart, candidates)
        }

        persist(studioId, weekStart, now, candidates)
    }

    // ── Detektory tygodniowe ──────────────────────────────────────────────────

    private fun detectFollowerJumps(
        links: List<StudioInstagramProfileEntity>,
        profiles: Map<UUID, InstagramProfileEntity>,
        now: Instant,
        weekStart: java.time.LocalDate,
        candidates: MutableList<Candidate>
    ) {
        links.forEach { link ->
            val profile = profiles[link.profileId] ?: return@forEach
            val history = metricsRepository.findByProfileIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
                link.profileId, weekStart.minusDays(9)
            ).filter { it.followerCount != null }
            if (history.size < 2) return@forEach

            val start = history.first().followerCount!!
            val end = history.last().followerCount!!
            if (start < 200) return@forEach

            val deltaPct = (end - start).toDouble() / start * 100.0
            val who = if (link.isSelf) "Twój profil" else "@${profile.username}"

            if (deltaPct >= 5.0) {
                candidates += Candidate(
                    type = "FOLLOWER_SPIKE",
                    severity = "MEDIUM",
                    title = "$who zyskał ${end - start} obserwujących w tydzień (+${"%.1f".format(deltaPct)}%)",
                    body = "Tak szybki wzrost zwykle oznacza udany post, konkurs albo płatną kampanię. " +
                        "Jeśli reakcje pod postami nie rosną razem z liczbą obserwujących, " +
                        "część nowych kont może nie być prawdziwymi klientami – traktuj to jako przypuszczenie.",
                    action = "Sprawdź ostatnie posty tego profilu – jeśli któryś zebrał dużo reakcji, tam jest odpowiedź.",
                    dedupKey = "FSPIKE:${link.profileId}:$weekStart",
                    profileId = link.profileId
                )
            } else if (deltaPct <= -3.0) {
                candidates += Candidate(
                    type = "FOLLOWER_DROP",
                    severity = "LOW",
                    title = "$who stracił ${abs(end - start)} obserwujących w tydzień (${"%.1f".format(deltaPct)}%)",
                    body = "Spadki zdarzają się po konkursach (odchodzą \"łowcy nagród\") " +
                        "albo gdy Instagram usuwa nieaktywne konta.",
                    action = "Nie wymaga działania – odnotuj i obserwuj, czy trend się utrzyma.",
                    dedupKey = "FDROP:${link.profileId}:$weekStart",
                    profileId = link.profileId
                )
            }
        }
    }

    private fun detectCadenceShifts(
        links: List<StudioInstagramProfileEntity>,
        profiles: Map<UUID, InstagramProfileEntity>,
        statsWindow: Map<UUID, List<InstagramProfileStatsWeeklyEntity>>,
        weekStart: java.time.LocalDate,
        candidates: MutableList<Candidate>
    ) {
        links.filter { !it.isSelf }.forEach { link ->
            val profile = profiles[link.profileId] ?: return@forEach
            val stats = statsWindow[link.profileId] ?: return@forEach

            val lastWeek = stats.firstOrNull { it.weekStart == weekStart.minusWeeks(1) }?.postCount ?: return@forEach
            val previous4 = stats.filter {
                it.weekStart < weekStart.minusWeeks(1) && it.weekStart >= weekStart.minusWeeks(5)
            }
            if (previous4.size < 4) return@forEach
            val avg = previous4.sumOf { it.postCount }.toDouble() / previous4.size

            if (avg >= 1.0 && lastWeek >= 3 && lastWeek >= avg * 2) {
                candidates += Candidate(
                    type = "CADENCE_SHIFT",
                    severity = "MEDIUM",
                    title = "@${profile.username} wyraźnie przyspieszył z publikacjami",
                    body = "W zeszłym tygodniu opublikował $lastWeek postów przy średniej ${"%.1f".format(avg)} " +
                        "z ostatniego miesiąca. Może szykować coś nowego – kampanię, nową usługę albo sezonową ofensywę.",
                    action = "Przejrzyj jego ostatnie posty w zakładce Treści i sprawdź, o czym nagle publikuje.",
                    dedupKey = "CADENCE:${link.profileId}:$weekStart",
                    profileId = link.profileId
                )
            }
        }
    }

    private fun detectFormatTrend(
        competitorIds: List<UUID>,
        now: Instant,
        weekStart: java.time.LocalDate,
        candidates: MutableList<Candidate>
    ) {
        if (competitorIds.isEmpty()) return
        val posts = postRepository.findByProfileIdInAndTakenAtAfter(competitorIds, now.minus(28, ChronoUnit.DAYS))

        val byFormat = posts.groupBy { MetricsCalculator.formatBucket(it.productType) }
        val reels = byFormat["REELS"] ?: emptyList()
        val photos = (byFormat["PHOTO"] ?: emptyList()) + (byFormat["CAROUSEL"] ?: emptyList())
        if (reels.size < 5 || photos.size < 5) return

        val reelsMedian = MetricsCalculator.median(reels.map { (it.likeCount + it.commentCount).toDouble() }) ?: return
        val photosMedian = MetricsCalculator.median(photos.map { (it.likeCount + it.commentCount).toDouble() }) ?: return
        if (photosMedian <= 0) return

        val ratio = reelsMedian / photosMedian
        if (ratio >= 1.5) {
            candidates += Candidate(
                type = "FORMAT_TREND",
                severity = "MEDIUM",
                title = "Rolki działają u Twojej konkurencji ${"%.1f".format(ratio)}× lepiej niż zdjęcia",
                body = "W ostatnim miesiącu typowa rolka u obserwowanych profili zbiera ${reelsMedian.toInt()} reakcji, " +
                    "a typowe zdjęcie ${photosMedian.toInt()}. Widzowie w Twojej okolicy wyraźnie wolą wideo.",
                action = "Nagraj krótką rolkę z realizacji (np. efekt przed/po) – nie musi być idealna, ważne że jest.",
                dedupKey = "FORMAT:$weekStart"
            )
        }
    }

    private fun detectSelfInsights(
        selfProfileId: UUID?,
        profiles: Map<UUID, InstagramProfileEntity>,
        statsWindow: Map<UUID, List<InstagramProfileStatsWeeklyEntity>>,
        weekStart: java.time.LocalDate,
        candidates: MutableList<Candidate>
    ) {
        if (selfProfileId == null) return
        val selfProfile = profiles[selfProfileId] ?: return

        // Tempo publikacji: Ty vs mediana konkurencji, 2 ostatnie zamknięte tygodnie
        val selfStats = statsWindow[selfProfileId] ?: emptyList()
        val lastTwoWeeks = listOf(weekStart.minusWeeks(1), weekStart.minusWeeks(2))

        val behindBothWeeks = lastTwoWeeks.all { week ->
            val selfPosts = selfStats.firstOrNull { it.weekStart == week }?.postCount ?: 0
            val competitorCounts = statsWindow
                .filterKeys { it != selfProfileId }
                .mapNotNull { (_, stats) -> stats.firstOrNull { it.weekStart == week }?.postCount?.toDouble() }
            val median = MetricsCalculator.median(competitorCounts) ?: return@all false
            median >= 1.0 && selfPosts < median / 2.0
        }

        if (behindBothWeeks) {
            candidates += Candidate(
                type = "YOU_FALLING_BEHIND",
                severity = "HIGH",
                title = "Publikujesz wyraźnie rzadziej niż konkurencja",
                body = "Od dwóch tygodni Twoje tempo publikacji jest poniżej połowy tego, co robi typowe " +
                    "obserwowane studio. Algorytm Instagrama premiuje regularność – dłuższa przerwa " +
                    "oznacza mniejsze zasięgi także po powrocie.",
                action = "Zaplanuj 2–3 posty na najbliższy tydzień. Najprościej: zdjęcia z bieżących realizacji " +
                    "albo wygeneruj treść w Generatorze postów.",
                dedupKey = "BEHIND:$weekStart",
                profileId = selfProfileId
            )
        }

        // Wizytówka profilu
        val storefront = MetricsCalculator.storefrontScore(selfProfile, lastPostAt = null)
        if (storefront.score < 70 && storefront.gaps.isNotEmpty()) {
            val month = weekStart.toString().substring(0, 7)
            val topGaps = storefront.gaps.sortedByDescending { it.points }.take(2).joinToString("; ") { it.label }
            candidates += Candidate(
                type = "PROFILE_GAP",
                severity = "LOW",
                title = "Twoja wizytówka na Instagramie ma braki (${storefront.score}/100)",
                body = "Profil to pierwsza rzecz, którą widzi klient porównujący studia. " +
                    "Najważniejsze do poprawy: $topGaps.",
                action = "Uzupełnienie profilu zajmuje kilka minut i działa od razu – zrób to przed kolejnym postem.",
                dedupKey = "GAP:$selfProfileId:$month",
                profileId = selfProfileId
            )
        }
    }

    // ── Utrwalanie z limitem tygodniowym ──────────────────────────────────────

    private fun persist(studioId: UUID, weekStart: java.time.LocalDate, now: Instant, candidates: List<Candidate>) {
        if (candidates.isEmpty()) return

        val fresh = candidates
            .filter { !insightRepository.existsByStudioIdAndDedupKey(studioId, it.dedupKey) }
            .sortedBy { SEVERITY_ORDER[it.severity] ?: 9 }

        var slots = (WEEKLY_CAP - insightRepository.countByStudioIdAndWeekStart(studioId, weekStart)).toInt()
        var created = 0

        fresh.forEach { candidate ->
            if (slots <= 0) return@forEach
            insightRepository.save(
                InstagramInsightEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId,
                    type = candidate.type,
                    severity = candidate.severity,
                    title = candidate.title,
                    body = candidate.body,
                    actionText = candidate.action,
                    profileId = candidate.profileId,
                    postId = candidate.postId,
                    probableCause = candidate.probableCause,
                    dedupKey = candidate.dedupKey,
                    status = "NEW",
                    weekStart = weekStart,
                    createdAt = now,
                    updatedAt = now
                )
            )
            slots--
            created++
        }

        if (created > 0) {
            log.info("Instagram insighty: studio {} – utworzono {} nowych wniosków", studioId, created)
        }
    }

    // ── Atrybucja przyczyny virala ────────────────────────────────────────────

    private fun viralCause(
        post: InstagramPostSnapshotEntity,
        topic: InstagramPostTopicEntity?,
        profileStats: List<InstagramProfileStatsWeeklyEntity>
    ): String {
        if (topic?.isContest == true) return "KONKURS"
        if (topic?.isPromo == true) return "PROMOCJA"

        // Lajki rosną, komentarze stoją → możliwe sztuczne zaangażowanie
        val totalLikes = profileStats.sumOf { it.totalLikes.toLong() }
        val totalComments = profileStats.sumOf { it.totalComments.toLong() }
        if (totalComments > 0 && post.commentCount > 0) {
            val profileRatio = totalLikes.toDouble() / totalComments
            val postRatio = post.likeCount.toDouble() / post.commentCount
            if (postRatio > profileRatio * 3) return "PODEJRZENIE_SZTUCZNEGO_ZAANGAZOWANIA"
        } else if (post.commentCount == 0 && post.likeCount > 100) {
            return "PODEJRZENIE_SZTUCZNEGO_ZAANGAZOWANIA"
        }

        if (MetricsCalculator.formatBucket(post.productType) == "REELS") return "VIRAL_REELS"
        return "WZROST_ORGANICZNY"
    }

    private fun causeSentence(cause: String): String = when (cause) {
        "KONKURS" -> "Najpewniej to efekt konkursu ogłoszonego w tym poście."
        "PROMOCJA" -> "Najpewniej to efekt promocji ogłoszonej w tym poście."
        "VIRAL_REELS" -> "To rolka – Instagram mocno rozpycha ten format, stąd zasięg."
        "PODEJRZENIE_SZTUCZNEGO_ZAANGAZOWANIA" ->
            "Uwaga: dużo lajków przy niewielu komentarzach może oznaczać kupione reakcje – to tylko przypuszczenie."
        else -> "Wygląda na naturalny, udany post."
    }

    private fun topicLabelSuffix(topic: InstagramPostTopicEntity?): String {
        val label = topic?.topic
            ?.takeIf { it != InstagramPostTopic.INNE.name && it != InstagramPostTopic.PROMOCJA.name }
            ?.let { InstagramPostTopic.labelFor(it) }
        return if (label != null) " (temat: ${label.lowercase()})" else ""
    }
}
