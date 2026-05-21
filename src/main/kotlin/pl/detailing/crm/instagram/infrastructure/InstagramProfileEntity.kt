package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.*

/**
 * Globalny rejestr unikalnych profili Instagramowych obserwowanych przez dowolne studio.
 * Każdy username jest przechowywany dokładnie raz – wiele studiów może go obserwować
 * przez StudioInstagramProfileEntity (relacja N-do-1).
 *
 * Pola metryk (followerCount, mediaCount itp.) zawierają ostatnio pobrane wartości
 * i są aktualizowane przez codzienny InstagramProfileDetailsSyncService.
 */
@Entity
@Table(
    name = "instagram_profiles",
    indexes = [
        Index(name = "idx_ig_profiles_username", columnList = "username", unique = true),
        Index(name = "idx_ig_profiles_api_error", columnList = "api_error")
    ]
)
class InstagramProfileEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "username", nullable = false, length = 30, unique = true)
    val username: String,

    @Column(name = "instagram_user_id", nullable = true, length = 30)
    var instagramUserId: String? = null,

    @Column(name = "api_error", nullable = false)
    var apiError: Boolean = false,

    // ── Metryki profilu (aktualizowane codziennie przez InstagramProfileDetailsSyncService) ──

    @Column(name = "follower_count", nullable = true)
    var followerCount: Int? = null,

    @Column(name = "following_count", nullable = true)
    var followingCount: Int? = null,

    /** Łączna liczba opublikowanych postów (media_count z API). */
    @Column(name = "media_count", nullable = true)
    var mediaCount: Int? = null,

    @Column(name = "biography", nullable = true, columnDefinition = "text")
    var biography: String? = null,

    /** URL strony zewnętrznej podanej w bio (np. https://www.carslab.pl/). */
    @Column(name = "external_url", nullable = true, columnDefinition = "text")
    var externalUrl: String? = null,

    /** true gdy profil ma uzupełniony publiczny e-mail lub numer telefonu. */
    @Column(name = "has_contact_data", nullable = false)
    var hasContactData: Boolean = false,

    /** Zweryfikowany (niebieski) znacznik Instagrama / Meta Verified. */
    @Column(name = "is_verified", nullable = false)
    var isVerified: Boolean = false,

    /** true gdy konto jest prowadzone jako konto firmowe (is_business z API). */
    @Column(name = "is_business", nullable = false)
    var isBusiness: Boolean = false,

    /**
     * Typ konta wg Instagram API (account_type): 1 = personal, 2 = creator, 3 = professional/business.
     * Null przed pierwszym pobraniem szczegółów.
     */
    @Column(name = "account_type", nullable = true)
    var accountType: Int? = null,

    /** Kategoria profilu (np. "Automotive Service", "Local Business"). Null gdy puste. */
    @Column(name = "category", nullable = true, length = 100)
    var category: String? = null,

    /** true gdy profil posiada aktywne kolekcje Highlights. */
    @Column(name = "has_highlight_reels", nullable = false)
    var hasHighlightReels: Boolean = false,

    /** Łączna liczba opublikowanych Reelsów (total_clips_count z API). */
    @Column(name = "total_clips_count", nullable = false)
    var totalClipsCount: Int = 0,

    /** true gdy konto jest prywatne (posty niewidoczne bez obserwacji). */
    @Column(name = "is_private", nullable = false)
    var isPrivate: Boolean = false,

    /** Czas ostatniej aktualizacji szczegółów profilu przez InstagramProfileDetailsSyncService. */
    @Column(name = "details_last_synced_at", nullable = true, columnDefinition = "timestamp with time zone")
    var detailsLastSyncedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
