package pl.detailing.crm.instagram.infrastructure

/**
 * Znormalizowany post z dowolnego dostawcy danych.
 * Świadomie NIE zawiera URL-i mediów – nie przechowujemy i nie kopiujemy zdjęć/wideo
 * (decyzja prawna, patrz docs/instagram-competitor-module-redesign.md §4.2).
 * Link do oryginału budowany jest z [code] (https://www.instagram.com/p/{code}/).
 */
data class RawInstagramPost(
    val pk: String,
    val code: String,
    val likeCount: Int,
    val commentCount: Int,
    /** Wyświetlenia wideo/Reels; null dla zdjęć lub gdy dostawca nie zwraca. */
    val viewCount: Long?,
    val captionText: String?,
    /** Unix timestamp publikacji. */
    val takenAt: Long,
    val isPinned: Boolean = false,
    /** "feed_item" (zdjęcie) | "clips" (Reels) | "video" | "carousel_container". */
    val productType: String? = null,
    val carouselMediaCount: Int = 1,
    /** true gdy autor ukrył liczbę lajków – likeCount jest wtedy niemiarodajny. */
    val likeCountHidden: Boolean = false
)

/**
 * Znormalizowane szczegóły profilu z dowolnego dostawcy danych.
 */
data class RawInstagramUserDetails(
    val instagramUserId: String,
    val followerCount: Int?,
    val followingCount: Int?,
    val mediaCount: Int?,
    val biography: String?,
    val externalUrl: String?,
    /** true gdy profil ma uzupełniony publiczny e-mail LUB numer telefonu */
    val hasContactData: Boolean,
    val publicEmail: String?,
    val publicPhoneNumber: String?,
    val isVerified: Boolean,
    val isBusiness: Boolean,
    /** Typ konta: 1 = personal, 2 = creator, 3 = business/professional */
    val accountType: Int?,
    val category: String?,
    val hasHighlightReels: Boolean,
    val totalClipsCount: Int,
    val isPrivate: Boolean,
    /** Unix timestamp ostatniego Reela – null gdy dostawca nie zwraca. */
    val latestReelMedia: Long?
)
