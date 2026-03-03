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
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Serwis odpowiedzialny za jednorazowe pobranie postów dla wszystkich aktywnych
 * i unikalnych profili Instagramowych oraz zapisanie ich do bazy danych.
 *
 * Zasada 1-do-N: każdy unikalny username jest odpytywany przez RapidAPI dokładnie raz.
 * Dane są globalne – współdzielone przez wszystkie studia obserwujące dany profil.
 *
 * Strategia aktualizacji (okno 3 miesięcy):
 * - Nowe posty (nieznany postPk) → INSERT
 * - Istniejące posty w oknie 3 miesięcy od takenAt → UPDATE (like_count, comment_count, view_count, scraped_at)
 * - Istniejące posty starsze niż 3 miesiące → pomijane (dane historyczne nie są nadpisywane)
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

    companion object {
        /** Posty publikowane w tym oknie mają aktualizowane liczniki przy każdym sync */
        private const val UPDATE_WINDOW_MONTHS = 3L
    }

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
            val updateCutoff = scrapedAt.minus(UPDATE_WINDOW_MONTHS * 30, ChronoUnit.DAYS)

            // Podziel posty na nowe i już znane (po postPk)
            val allPks = rawPosts.map { it.pk }
            val existingByPk = postSnapshotRepository.findByPostPkIn(allPks).associateBy { it.postPk }

            val toInsert = mutableListOf<InstagramPostSnapshotEntity>()
            var updatedCount = 0

            rawPosts.forEach { raw ->
                val takenAt = Instant.ofEpochSecond(raw.takenAt)
                val existing = existingByPk[raw.pk]

                if (existing == null) {
                    // Nowy post – wstaw bez względu na wiek
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
                        scrapedAt = scrapedAt
                    )
                } else if (takenAt.isAfter(updateCutoff)) {
                    // Istniejący post w oknie 3 miesięcy – zaktualizuj liczniki
                    existing.likeCount = raw.likeCount
                    existing.commentCount = raw.commentCount
                    existing.viewCount = raw.viewCount
                    existing.scrapedAt = scrapedAt
                    updatedCount++
                }
                // Istniejące posty starsze niż 3 miesiące – pomijamy (historyczne dane zostają)
            }

            if (toInsert.isNotEmpty()) postSnapshotRepository.saveAll(toInsert)
            // Zaktualizowane encje są dirty-tracked przez Hibernate – flush w @Transactional

            log.info(
                "Instagram sync: @{} – nowe={}, zaktualizowane={}, pominięte (>3mies)={} (łącznie z API={})",
                profile.username,
                toInsert.size,
                updatedCount,
                rawPosts.size - toInsert.size - updatedCount,
                rawPosts.size
            )

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
