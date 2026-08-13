package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramDataProvider
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProviderException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*

/**
 * Pobiera i utrwala szczegóły profilu (metryki, bio, flagi) – 1 wywołanie API na profil.
 *
 * Odpowiedzialności:
 * - aktualizacja pól w [InstagramProfileEntity] (followerCount, hasContactData itd.),
 * - dzienny snapshot w [InstagramProfileMetricsSnapshotEntity] (historia obserwujących),
 * - zapis instagramUserId (wymagany przez providera Looter do pobierania postów).
 */
@Service
class InstagramProfileDetailsSyncService(
    private val profileRepository: InstagramProfileRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val provider: InstagramDataProvider
) {
    private val log = LoggerFactory.getLogger(InstagramProfileDetailsSyncService::class.java)

    /** @return true gdy szczegóły zostały pobrane i zapisane. */
    @Transactional
    fun syncProfile(profile: InstagramProfileEntity): Boolean {
        val details = try {
            provider.fetchUserDetails(profile.username)
        } catch (e: InstagramProviderException) {
            log.warn(
                "Instagram details sync: błąd dostawcy dla @{} (HTTP {}): {}",
                profile.username, e.statusCode, e.message
            )
            return false
        }

        if (details == null) {
            log.warn("Instagram details sync: @{} – dostawca nie zwrócił danych profilu", profile.username)
            return false
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

        log.debug(
            "Instagram details sync: @{} – followers={}, media={}, private={}",
            profile.username, details.followerCount, details.mediaCount, details.isPrivate
        )
        return true
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
