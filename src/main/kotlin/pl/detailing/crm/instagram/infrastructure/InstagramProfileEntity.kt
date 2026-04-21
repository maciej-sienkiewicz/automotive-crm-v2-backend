package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.*

/**
 * Globalny rejestr unikalnych profili Instagramowych obserwowanych przez dowolne studio.
 * Każdy username jest przechowywany dokładnie raz – wiele studiów może go obserwować
 * przez StudioInstagramProfileEntity (relacja N-do-1).
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

    /**
     * Natywne ID konta Instagram (pole "id" z /user/details).
     * Wymagane do pobierania stories (/user/stories?user_id=...).
     * Null przed pierwszym zatwierdzeniem profilu.
     */
    @Column(name = "instagram_user_id", nullable = true, length = 30)
    var instagramUserId: String? = null,

    /**
     * Flaga ustawiana przez scheduler gdy RapidAPI zwraca błąd dla tego profilu
     * (np. konto usunięte). Administrator może zareagować.
     */
    @Column(name = "api_error", nullable = false)
    var apiError: Boolean = false,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
