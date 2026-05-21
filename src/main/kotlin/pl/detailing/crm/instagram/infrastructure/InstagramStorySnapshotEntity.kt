package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.*

/**
 * Snapshot pojedynczego story Instagramowego pobrany przez dzienny scheduler.
 * Dane są globalne (nie per-studio) – każde story jest przechowywane raz
 * i udostępniane wszystkim studiom obserwującym dany profil.
 *
 * Przechowujemy wyłącznie timestamp publikacji (takenAt), który pozwala
 * zliczać aktywność stories per dzień / tydzień. Treść wizualna (zdjęcia, wideo)
 * nie jest zbierana – stories służą wyłącznie do analizy częstotliwości aktywności.
 */
@Entity
@Table(
    name = "instagram_story_snapshots",
    indexes = [
        Index(name = "idx_ig_stories_profile_taken", columnList = "profile_id, taken_at"),
        Index(name = "idx_ig_stories_story_id", columnList = "story_id", unique = true)
    ]
)
class InstagramStorySnapshotEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    /** Natywne ID story z API Instagrama (np. "3880105700017480750_5487602384") */
    @Column(name = "story_id", nullable = false, length = 60, unique = true)
    val storyId: String,

    /** Czas publikacji story (Unix timestamp z API → Instant) */
    @Column(name = "taken_at", nullable = false, columnDefinition = "timestamp with time zone")
    val takenAt: Instant,

    /** Czas pobrania przez scheduler */
    @Column(name = "scraped_at", nullable = false, columnDefinition = "timestamp with time zone")
    val scrapedAt: Instant
)
