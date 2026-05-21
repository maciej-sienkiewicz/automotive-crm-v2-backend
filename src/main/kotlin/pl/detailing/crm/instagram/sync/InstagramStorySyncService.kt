package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotRepository
import pl.detailing.crm.instagram.infrastructure.RapidApiInstagramClient
import java.time.Instant
import java.util.*

/**
 * Serwis pobierający timestamps stories Instagramowych dla aktywnych profili.
 *
 * Przechowujemy wyłącznie storyId i takenAt – dane potrzebne do zliczania aktywności
 * (stories per dzień/tydzień). Treść wizualna (zdjęcia, wideo) nie jest zbierana.
 *
 * Wymaga, aby InstagramProfileEntity.instagramUserId był już ustawiony
 * (przez InstagramProfileDetailsSyncService, który wykonuje się wcześniej).
 *
 * Wywołania:
 * - [syncAllActiveProfiles] – codzienny scheduler 09:00
 * - [syncProfile]           – jednorazowo po zatwierdzeniu profilu
 */
@Service
class InstagramStorySyncService(
    private val profileRepository: InstagramProfileRepository,
    private val storyRepository: InstagramStorySnapshotRepository,
    private val rapidApiClient: RapidApiInstagramClient
) {
    private val log = LoggerFactory.getLogger(InstagramStorySyncService::class.java)

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
                syncProfile(profile)
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

    @Transactional
    fun syncProfile(profile: InstagramProfileEntity) {
        val userId = profile.instagramUserId ?: run {
            log.warn(
                "Instagram stories sync: @{} nie ma instagramUserId – pomijam (uruchom details sync najpierw)",
                profile.username
            )
            return
        }

        val scrapedAt = Instant.now()
        val rawStories = rapidApiClient.fetchStories(userId)

        if (rawStories.isEmpty()) {
            log.debug("Instagram stories sync: brak aktywnych stories dla @{}", profile.username)
            return
        }

        val incomingIds = rawStories.map { it.storyId }
        val existingIds = storyRepository.findByStoryIdIn(incomingIds).map { it.storyId }.toSet()

        val toInsert = rawStories
            .filter { it.storyId !in existingIds }
            .map { raw ->
                InstagramStorySnapshotEntity(
                    id = UUID.randomUUID(),
                    profileId = profile.id,
                    storyId = raw.storyId,
                    takenAt = Instant.ofEpochSecond(raw.takenAt),
                    scrapedAt = scrapedAt
                )
            }

        if (toInsert.isNotEmpty()) {
            storyRepository.saveAll(toInsert)
        }

        log.info(
            "Instagram stories sync: @{} – nowe={}, pominięte (duplikaty)={} (łącznie z API={})",
            profile.username, toInsert.size, existingIds.size, rawStories.size
        )
    }
}
