package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.*

/**
 * Cache sugestii "kont podobnych" per obserwowany profil (globalny, współdzielony).
 * Minimalny zakres danych (RODO): username + nazwa wyświetlana + flaga weryfikacji.
 * Odświeżany przy initial syncu i tygodniowo, gdy starszy niż 30 dni.
 */
@Entity
@Table(
    name = "instagram_profile_suggestions",
    indexes = [
        Index(name = "idx_ig_suggestions_profile", columnList = "profile_id"),
        Index(
            name = "idx_ig_suggestions_profile_username",
            columnList = "profile_id, suggested_username",
            unique = true
        )
    ]
)
class InstagramProfileSuggestionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    /** Profil źródłowy ("podobne do..."). */
    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    @Column(name = "suggested_username", nullable = false, length = 30)
    val suggestedUsername: String,

    @Column(name = "full_name", nullable = true, length = 150)
    val fullName: String?,

    @Column(name = "is_verified", nullable = false)
    val isVerified: Boolean,

    @Column(name = "fetched_at", nullable = false, columnDefinition = "timestamp with time zone")
    val fetchedAt: Instant
)
