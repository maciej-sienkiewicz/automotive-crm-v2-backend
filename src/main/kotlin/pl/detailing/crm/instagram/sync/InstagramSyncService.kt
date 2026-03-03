package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.RapidApiException
import pl.detailing.crm.instagram.infrastructure.RapidApiInstagramClient
import java.time.Instant
import java.util.*

/**
 * Serwis odpowiedzialny za jednorazowe pobranie postów dla wszystkich aktywnych
 * i unikalnych profili Instagramowych oraz zapisanie ich do bazy danych.
 *
 * Zasada 1-do-N: każdy unikalny username jest odpytywany przez RapidAPI dokładnie raz.
 * Dane są globalne – współdzielone przez wszystkie studia obserwujące dany profil.
 *
 * WAŻNE: działa poza kontekstem Spring Security (wywoływane przez scheduler).
 */
@Service
class InstagramSyncService(
    private val profileRepository: InstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val rapidApiClient: RapidApiInstagramClient
) {
    private val log = LoggerFactory.getLogger(InstagramSyncService::class.java)

    /**
     * Pobiera posty dla wszystkich aktywnych profili (distinct usernames).
     * Błąd pojedynczego profilu nie blokuje pozostałych.
     */
    fun syncAllActiveProfiles() {
        val activeProfiles = profileRepository.findAllActiveDistinct()

        if (activeProfiles.isEmpty()) {
            log.info("Instagram sync: brak aktywnych profili do synchronizacji.")
            return
        }

        log.info("Instagram sync: rozpoczynam dla {} unikalnych profili.", activeProfiles.size)

        var success = 0
        var errors = 0

        activeProfiles.forEach { profile ->
            try {
                syncProfile(profile)
                success++
            } catch (e: Exception) {
                log.error(
                    "Instagram sync: błąd dla @{}: {}",
                    profile.username, e.message, e
                )
                errors++
            }
        }

        log.info(
            "Instagram sync: zakończono. Sukces={}, Błędy={}",
            success, errors
        )
    }

    @Transactional
    fun syncProfile(profile: InstagramProfileEntity) {
        log.debug("Instagram sync: pobieranie postów dla @{}", profile.username)

        val scrapedAt = Instant.now()

        try {
            val rawPosts = rapidApiClient.fetchPosts(profile.username)

            val newPosts = rawPosts.filter { raw ->
                !postSnapshotRepository.existsByPostPk(raw.pk)
            }

            if (newPosts.isNotEmpty()) {
                val entities = newPosts.map { raw ->
                    InstagramPostSnapshotEntity(
                        id = UUID.randomUUID(),
                        profileId = profile.id,
                        postPk = raw.pk,
                        postCode = raw.code,
                        likeCount = raw.likeCount,
                        commentCount = raw.commentCount,
                        viewCount = raw.viewCount,
                        caption = raw.captionText,
                        takenAt = Instant.ofEpochSecond(raw.takenAt),
                        scrapedAt = scrapedAt
                    )
                }
                postSnapshotRepository.saveAll(entities)
                log.info(
                    "Instagram sync: @{} – zapisano {} nowych postów (łącznie {})",
                    profile.username, newPosts.size, rawPosts.size
                )
            } else {
                log.debug(
                    "Instagram sync: @{} – brak nowych postów (sprawdzono {}).",
                    profile.username, rawPosts.size
                )
            }

            // Jeśli poprzednio był błąd, wyczyść flagę
            if (profile.apiError) {
                profile.apiError = false
                profile.updatedAt = scrapedAt
                profileRepository.save(profile)
            }

        } catch (e: RapidApiException) {
            log.warn(
                "Instagram sync: błąd API dla @{} (HTTP {}): {}",
                profile.username, e.statusCode, e.message
            )
            // Oznacz profil jako wymagający uwagi admina
            profile.apiError = true
            profile.updatedAt = scrapedAt
            profileRepository.save(profile)
        }
    }
}
