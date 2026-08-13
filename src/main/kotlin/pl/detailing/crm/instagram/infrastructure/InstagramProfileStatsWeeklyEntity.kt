package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Tygodniowe agregaty per profil – liczone przy zapisie (po syncu), nie przy odczycie.
 * Wszystkie endpointy analityczne czytają z tej tabeli zamiast przeliczać snapshoty postów.
 *
 * Jeden wiersz per (profil, poniedziałek tygodnia ISO, UTC).
 */
@Entity
@Table(
    name = "instagram_profile_stats_weekly",
    indexes = [
        Index(name = "idx_ig_stats_profile_week", columnList = "profile_id, week_start", unique = true),
        Index(name = "idx_ig_stats_week", columnList = "week_start")
    ]
)
class InstagramProfileStatsWeeklyEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    /** Poniedziałek tygodnia ISO (UTC). */
    @Column(name = "week_start", nullable = false)
    val weekStart: LocalDate,

    @Column(name = "post_count", nullable = false)
    var postCount: Int,

    /** Posty typu "clips"/"video" (Reels i wideo). */
    @Column(name = "reels_count", nullable = false)
    var reelsCount: Int,

    @Column(name = "carousel_count", nullable = false)
    var carouselCount: Int,

    @Column(name = "photo_count", nullable = false)
    var photoCount: Int,

    @Column(name = "total_likes", nullable = false)
    var totalLikes: Int,

    @Column(name = "total_comments", nullable = false)
    var totalComments: Int,

    @Column(name = "total_views", nullable = true)
    var totalViews: Long?,

    /**
     * Średnie zaangażowanie na post w % obserwujących:
     * avg(lajki+komentarze na post) / followers na koniec tygodnia × 100.
     * Null gdy brak postów lub brak danych o obserwujących.
     */
    @Column(name = "er_pct", nullable = true)
    var erPct: Double?,

    /** Liczba obserwujących na koniec tygodnia (ostatni snapshot ≤ niedziela). */
    @Column(name = "follower_end", nullable = true)
    var followerEnd: Int?,

    /** Zmiana liczby obserwujących w tygodniu (koniec - początek); null gdy brak danych. */
    @Column(name = "follower_delta", nullable = true)
    var followerDelta: Int?,

    @Column(name = "computed_at", nullable = false, columnDefinition = "timestamp with time zone")
    var computedAt: Instant
)
