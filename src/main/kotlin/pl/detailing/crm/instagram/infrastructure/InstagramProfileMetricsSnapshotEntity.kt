package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Dzienny snapshot metryk profilu Instagramowego – pozwala śledzić trendy wzrostu
 * (liczba obserwujących, liczba postów) w czasie.
 *
 * Jeden rekord per profil per dzień; scheduler pomija dzień jeśli snapshot już istnieje.
 * Dane są globalne (nie per-studio) i współdzielone przez wszystkie studia obserwujące profil.
 */
@Entity
@Table(
    name = "instagram_profile_metrics_snapshots",
    indexes = [
        Index(
            name = "idx_ig_metrics_profile_date",
            columnList = "profile_id, snapshot_date",
            unique = true
        ),
        Index(name = "idx_ig_metrics_profile_id", columnList = "profile_id")
    ]
)
class InstagramProfileMetricsSnapshotEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    /** Data (UTC) wykonania snapshotu – klucz unikalności per profil. */
    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,

    @Column(name = "follower_count", nullable = true)
    val followerCount: Int?,

    @Column(name = "following_count", nullable = true)
    val followingCount: Int?,

    /** Łączna liczba postów w momencie wykonania snapshotu. */
    @Column(name = "media_count", nullable = true)
    val mediaCount: Int?,

    @Column(name = "snapshot_at", nullable = false, columnDefinition = "timestamp with time zone")
    val snapshotAt: Instant
)
