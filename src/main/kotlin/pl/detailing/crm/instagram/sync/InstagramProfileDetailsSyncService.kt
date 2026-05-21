package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.RapidApiException
import pl.detailing.crm.instagram.infrastructure.RapidApiInstagramClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*

/**
 * Serwis pobierający i utrwalający szczegóły profilu Instagramowego z /user/details.
 *
 * Odpowiedzialności:
 * - Aktualizacja metryk w InstagramProfileEntity (followerCount, mediaCount, hasContactData itp.)
 * - Tworzenie dziennych snapshotów w InstagramProfileMetricsSnapshotEntity (trend followerów)
 * - Zapis instagramUserId przy pierwszym pobraniu (wymagany do story sync)
 *
 * Wywołania:
 * - [syncAllActiveProfiles] – codzienny scheduler 08:00 (przed story sync o 09:00)
 * - [syncProfile]           – jednorazowo po zatwierdzeniu profilu
 */
@Service
class InstagramProfileDetailsSyncService(
    private val profileRepository: InstagramProfileRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val rapidApiClient: RapidApiInstagramClient
) {
    private val log = LoggerFactory.getLogger(InstagramProfileDetailsSyncService::class.java)

    fun syncAllActiveProfiles() {
        val activeProfiles = profileRepository.findAllActiveDistinct()

        if (activeProfiles.isEmpty()) {
            log.info("Instagram details sync: brak aktywnych profili do synchronizacji.")
            return
        }

        log.info("Instagram details sync: rozpoczynam dla {} profili.", activeProfiles.size)

        var success = 0
        var errors = 0

        activeProfiles.forEach { profile ->
            try {
                syncProfile(profile)
                success++
            } catch (e: Exception) {
                log.error(
                    "Instagram details sync: błąd dla @{}: {}",
                    profile.username, e.message, e
                )
                errors++
            }
        }

        log.info("Instagram details sync: zakończono. Sukces={}, Błędy={}", success, errors)
    }

    @Transactional
    fun syncProfile(profile: InstagramProfileEntity) {
        val details = try {
            rapidApiClient.fetchUserDetails(profile.username)
        } catch (e: RapidApiException) {
            log.warn(
                "Instagram details sync: błąd API dla @{} (HTTP {}): {}",
                profile.username, e.statusCode, e.message
            )
            return
        }

        if (details == null) {
            log.warn("Instagram details sync: @{} – brak danych z /user/details", profile.username)
            return
        }

        val now = Instant.now()

        profile.instagramUserId = details.instagramUserId
        profile.followerCount = details.followerCount
        profile.followingCount = details.followingCount
        profile.mediaCount = details.mediaCount
        profile.biography = details.biography
        profile.externalUrl = details.externalUrl
        profile.hasContactData = details.hasContactData
        profile.isVerified = details.isVerified
        profile.isBusiness = details.isBusiness
        profile.accountType = details.accountType
        profile.category = details.category
        profile.hasHighlightReels = details.hasHighlightReels
        profile.totalClipsCount = details.totalClipsCount
        profile.isPrivate = details.isPrivate
        profile.detailsLastSyncedAt = now
        profile.updatedAt = now
        profileRepository.save(profile)

        saveMetricsSnapshot(profile.id, details.followerCount, details.followingCount, details.mediaCount, now)

        log.info(
            "Instagram details sync: @{} – zaktualizowano (followers={}, mediaCount={}, hasContact={})",
            profile.username, details.followerCount, details.mediaCount, details.hasContactData
        )
    }

    private fun saveMetricsSnapshot(
        profileId: UUID,
        followerCount: Int?,
        followingCount: Int?,
        mediaCount: Int?,
        now: Instant
    ) {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (metricsRepository.existsByProfileIdAndSnapshotDate(profileId, today)) return

        metricsRepository.save(
            InstagramProfileMetricsSnapshotEntity(
                id = UUID.randomUUID(),
                profileId = profileId,
                snapshotDate = today,
                followerCount = followerCount,
                followingCount = followingCount,
                mediaCount = mediaCount,
                snapshotAt = now
            )
        )
    }
}
