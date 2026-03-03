package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.*

/**
 * Snapshot pojedynczego posta Instagramowego pobrany przez scheduler.
 * Dane są globalne (nie per-studio) – każdy post jest przechowywany raz
 * i udostępniany wszystkim studiom obserwującym dany profil.
 */
@Entity
@Table(
    name = "instagram_post_snapshots",
    indexes = [
        Index(name = "idx_ig_posts_profile_scraped", columnList = "profile_id, scraped_at"),
        Index(name = "idx_ig_posts_profile_taken", columnList = "profile_id, taken_at"),
        Index(name = "idx_ig_posts_post_pk", columnList = "post_pk", unique = true)
    ]
)
class InstagramPostSnapshotEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    /** Natywne pk posta z API Instagrama (np. "2537897532813506311") */
    @Column(name = "post_pk", nullable = false, length = 30, unique = true)
    val postPk: String,

    /** Shortcode posta (np. "CM4bFwXsHcH") – używany do budowy linku */
    @Column(name = "post_code", nullable = false, length = 30)
    val postCode: String,

    @Column(name = "like_count", nullable = false)
    val likeCount: Int,

    @Column(name = "comment_count", nullable = false)
    val commentCount: Int,

    /** view_count może być null dla postów zdjęciowych */
    @Column(name = "view_count", nullable = true)
    val viewCount: Long?,

    @Column(name = "caption", nullable = true, columnDefinition = "text")
    val caption: String?,

    /** Czas publikacji posta (Unix timestamp z API → Instant) */
    @Column(name = "taken_at", nullable = false, columnDefinition = "timestamp with time zone")
    val takenAt: Instant,

    /** Czas pobrania danych przez scheduler */
    @Column(name = "scraped_at", nullable = false, columnDefinition = "timestamp with time zone")
    val scrapedAt: Instant
)
