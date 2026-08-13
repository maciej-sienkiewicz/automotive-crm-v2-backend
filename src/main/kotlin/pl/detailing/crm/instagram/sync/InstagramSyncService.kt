package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramDataProvider
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProviderException
import pl.detailing.crm.instagram.infrastructure.RawInstagramPost
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/** Wynik synchronizacji postów jednego profilu. */
data class PostSyncResult(
    val newPosts: List<InstagramPostSnapshotEntity>,
    val updatedCount: Int,
    val pagesFetched: Int
)

/**
 * Synchronizacja snapshotów postów – zoptymalizowana pod quotę RapidAPI.
 *
 * Tryby:
 * - [SyncDepth.LIGHT]    – 1 strona (12 najnowszych postów). Używany codziennie:
 *                          wykrywa nowe posty (w tym promocje) i odświeża liczniki
 *                          najnowszych treści kosztem 1 wywołania na profil.
 * - [SyncDepth.DEEP]     – stronicowanie aż do okna aktualizacji (90 dni) lub limitu stron.
 *                          Używany tygodniowo: odświeża liczniki starszych postów.
 * - [SyncDepth.BACKFILL] – pełna historia 12 miesięcy. Używany raz, po zatwierdzeniu profilu.
 *
 * Wczesny stop: pobieranie kolejnych stron kończy się, gdy strona zawiera post starszy niż
 * granica trybu – nie płacimy za dane, których nie zaktualizujemy.
 */
@Service
class InstagramSyncService(
    private val profileRepository: InstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val provider: InstagramDataProvider,
    @Value("\${instagram.sync.deep-max-pages:8}") private val deepMaxPages: Int,
    @Value("\${instagram.sync.backfill-max-pages:31}") private val backfillMaxPages: Int
) {
    private val log = LoggerFactory.getLogger(InstagramSyncService::class.java)

    enum class SyncDepth { LIGHT, DEEP, BACKFILL }

    companion object {
        /** Posty z tego okna mają odświeżane liczniki przy syncu DEEP. */
        const val UPDATE_WINDOW_DAYS = 90L

        /** Zakres pełnej historii przy pierwszym pobraniu profilu. */
        const val BACKFILL_WINDOW_DAYS = 365L
    }

    @Transactional
    fun syncProfilePosts(profile: InstagramProfileEntity, depth: SyncDepth): PostSyncResult {
        val scrapedAt = Instant.now()

        try {
            val (rawPosts, pages) = fetchPosts(profile, depth)
            val result = upsertPosts(profile, rawPosts, scrapedAt, pages)

            if (profile.apiError) {
                profile.apiError = false
                profile.updatedAt = scrapedAt
                profileRepository.save(profile)
            }

            log.info(
                "Instagram sync [{}]: @{} – nowe={}, zaktualizowane={}, strony={}",
                depth, profile.username, result.newPosts.size, result.updatedCount, result.pagesFetched
            )
            return result
        } catch (e: InstagramProviderException) {
            log.warn(
                "Instagram sync [{}]: błąd dostawcy dla @{} (HTTP {}): {}",
                depth, profile.username, e.statusCode, e.message
            )
            profile.apiError = true
            profile.updatedAt = scrapedAt
            profileRepository.save(profile)
            return PostSyncResult(emptyList(), 0, 0)
        }
    }

    // ── prywatne ──────────────────────────────────────────────────────────────

    private fun fetchPosts(profile: InstagramProfileEntity, depth: SyncDepth): Pair<List<RawInstagramPost>, Int> {
        val effectiveDepth = if (depth != SyncDepth.LIGHT && !postSnapshotRepository.existsByProfileId(profile.id)) {
            SyncDepth.BACKFILL
        } else {
            depth
        }

        val (cutoffDays, maxPages) = when (effectiveDepth) {
            SyncDepth.LIGHT -> return fetchSinglePage(profile)
            SyncDepth.DEEP -> UPDATE_WINDOW_DAYS to deepMaxPages
            SyncDepth.BACKFILL -> BACKFILL_WINDOW_DAYS to backfillMaxPages
        }

        val cutoff = Instant.now().minus(cutoffDays, ChronoUnit.DAYS)
        val result = mutableListOf<RawInstagramPost>()
        var cursor: String? = null
        var page = 0

        while (page < maxPages) {
            val pageData = provider.fetchPostsPage(profile.username, profile.instagramUserId, cursor)
            page++

            if (pageData.posts.isEmpty()) break

            val pinned = pageData.posts.filter { it.isPinned }
            val regular = pageData.posts.filter { !it.isPinned }

            // Przypięte posty mogą być stare – bierzemy je tylko z pierwszej strony
            if (page == 1) result.addAll(pinned)
            result.addAll(regular.filter { Instant.ofEpochSecond(it.takenAt).isAfter(cutoff) })

            val reachedCutoff = regular.any { !Instant.ofEpochSecond(it.takenAt).isAfter(cutoff) }
            if (reachedCutoff || !pageData.hasMore || pageData.nextCursor == null) break

            cursor = pageData.nextCursor
        }

        return result to page
    }

    private fun fetchSinglePage(profile: InstagramProfileEntity): Pair<List<RawInstagramPost>, Int> {
        val pageData = provider.fetchPostsPage(profile.username, profile.instagramUserId, cursor = null)
        return pageData.posts to 1
    }

    private fun upsertPosts(
        profile: InstagramProfileEntity,
        rawPosts: List<RawInstagramPost>,
        scrapedAt: Instant,
        pages: Int
    ): PostSyncResult {
        if (rawPosts.isEmpty()) return PostSyncResult(emptyList(), 0, pages)

        val existingByPk = postSnapshotRepository
            .findByPostPkIn(rawPosts.map { it.pk })
            .associateBy { it.postPk }

        val updateCutoff = scrapedAt.minus(UPDATE_WINDOW_DAYS, ChronoUnit.DAYS)
        val toInsert = mutableListOf<InstagramPostSnapshotEntity>()
        var updatedCount = 0

        rawPosts.forEach { raw ->
            val takenAt = Instant.ofEpochSecond(raw.takenAt)
            val existing = existingByPk[raw.pk]

            if (existing == null) {
                toInsert += InstagramPostSnapshotEntity(
                    id = UUID.randomUUID(),
                    profileId = profile.id,
                    postPk = raw.pk,
                    postCode = raw.code,
                    likeCount = raw.likeCount,
                    commentCount = raw.commentCount,
                    viewCount = raw.viewCount,
                    caption = raw.captionText,
                    takenAt = takenAt,
                    scrapedAt = scrapedAt,
                    productType = raw.productType,
                    carouselMediaCount = raw.carouselMediaCount,
                    hashtags = extractHashtags(raw.captionText)
                )
            } else if (takenAt.isAfter(updateCutoff)) {
                existing.likeCount = raw.likeCount
                existing.commentCount = raw.commentCount
                existing.viewCount = raw.viewCount
                existing.scrapedAt = scrapedAt
                updatedCount++
            }
        }

        if (toInsert.isNotEmpty()) postSnapshotRepository.saveAll(toInsert)

        return PostSyncResult(toInsert, updatedCount, pages)
    }

    private fun extractHashtags(caption: String?): String? {
        if (caption.isNullOrBlank()) return null
        val tags = Regex("#(\\w+)").findAll(caption).map { it.groupValues[1].lowercase() }.distinct().toList()
        return if (tags.isEmpty()) null else tags.joinToString(",")
    }
}
