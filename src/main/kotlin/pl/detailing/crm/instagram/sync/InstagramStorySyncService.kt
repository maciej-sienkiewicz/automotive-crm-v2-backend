package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotRepository
import pl.detailing.crm.instagram.infrastructure.RapidApiException
import pl.detailing.crm.instagram.infrastructure.RapidApiInstagramClient
import java.time.Instant
import java.util.*

/**
 * Serwis pobierający stories Instagramowe dla aktywnych profili konkurencji.
 *
 * Zasady:
 * - Każdy unikalny profil jest odpytywany dokładnie raz (dane globalne, współdzielone przez studia).
 * - Story już zapisane w DB (znane storyId) są pomijane – nie ma duplikatów.
 * - Używa instagramUserId z InstagramProfileEntity – nie wykonuje dodatkowego zapytania /user/details.
 *
 * Wywołania:
 * - [syncAllActiveProfiles] – codzienny scheduler 9:00
 * - [syncProfileInitial]    – jednorazowo po zatwierdzeniu profilu (wykonuje też /user/details)
 */
@Service
class InstagramStorySyncService(
    private val profileRepository: InstagramProfileRepository,
    private val storyRepository: InstagramStorySnapshotRepository,
    private val rapidApiClient: RapidApiInstagramClient
) {
    private val log = LoggerFactory.getLogger(InstagramStorySyncService::class.java)

    /**
     * Pobiera stories dla wszystkich aktywnych profili, które mają już zapisany instagramUserId.
     * Błąd pojedynczego profilu nie blokuje pozostałych.
     */
    fun syncAllActiveProfiles() {
        val activeProfiles = profileRepository.findAllActiveDistinct()

        val withUserId = activeProfiles.filter { it.instagramUserId != null }

        if (withUserId.isEmpty()) {
            log.info("Instagram stories sync: brak aktywnych profili z userId do synchronizacji.")
            return
        }

        log.info("Instagram stories sync: rozpoczynam dla {} profili.", withUserId.size)

        var success = 0
        var errors = 0

        withUserId.forEach { profile ->
            try {
                syncStories(profile)
                success++
            } catch (e: Exception) {
                log.error(
                    "Instagram stories sync: błąd dla @{}: {}",
                    profile.username, e.message, e
                )
                errors++
            }
        }

        log.info("Instagram stories sync: zakończono. Sukces={}, Błędy={}", success, errors)
    }

    /**
     * Uruchamiane jednorazowo po zatwierdzeniu profilu (po pierwszym sync postów).
     * Kolejność:
     *  1. Pobiera instagramUserId przez /user/details i zapisuje do encji profilu.
     *  2. Pobiera stories.
     */
    @Transactional
    fun syncProfileInitial(profile: InstagramProfileEntity) {
        val userId = resolveUserId(profile) ?: return
        fetchAndSaveStories(profile.id, userId)
    }

    /**
     * Codzienny sync stories dla profilu z już zapisanym instagramUserId.
     */
    @Transactional
    fun syncStories(profile: InstagramProfileEntity) {
        val userId = profile.instagramUserId ?: run {
            log.warn(
                "Instagram stories sync: @{} nie ma instagramUserId – pomijam",
                profile.username
            )
            return
        }
        fetchAndSaveStories(profile.id, userId)
    }

    // ── prywatne ──────────────────────────────────────────────────────────────

    private fun resolveUserId(profile: InstagramProfileEntity): String? {
        if (profile.instagramUserId != null) return profile.instagramUserId

        return try {
            val userId = rapidApiClient.fetchUserDetails(profile.username)
            if (userId == null) {
                log.warn(
                    "Instagram stories: /user/details nie zwróciło userId dla @{}",
                    profile.username
                )
                return null
            }
            profile.instagramUserId = userId
            profile.updatedAt = Instant.now()
            profileRepository.save(profile)
            log.info(
                "Instagram stories: zapisano instagramUserId={} dla @{}",
                userId, profile.username
            )
            userId
        } catch (e: RapidApiException) {
            log.warn(
                "Instagram stories: błąd /user/details dla @{} (HTTP {}): {}",
                profile.username, e.statusCode, e.message
            )
            null
        }
    }

    private fun fetchAndSaveStories(profileId: UUID, instagramUserId: String) {
        val scrapedAt = Instant.now()
        val rawStories = rapidApiClient.fetchStories(instagramUserId)

        if (rawStories.isEmpty()) {
            log.debug("Instagram stories: brak aktywnych stories dla userId={}", instagramUserId)
            return
        }

        // Odfiltruj już znane storyId
        val incomingIds = rawStories.map { it.storyId }
        val existingIds = storyRepository.findByStoryIdIn(incomingIds).map { it.storyId }.toSet()

        val toInsert = rawStories
            .filter { it.storyId !in existingIds }
            .map { raw ->
                InstagramStorySnapshotEntity(
                    id = UUID.randomUUID(),
                    profileId = profileId,
                    storyId = raw.storyId,
                    imageUrl = raw.imageUrl,
                    takenAt = Instant.ofEpochSecond(raw.takenAt),
                    scrapedAt = scrapedAt
                )
            }

        if (toInsert.isNotEmpty()) {
            storyRepository.saveAll(toInsert)
        }

        log.info(
            "Instagram stories: userId={} – nowe={}, pominięte (duplikaty)={} (łącznie z API={})",
            instagramUserId, toInsert.size, existingIds.size, rawStories.size
        )
    }
}
