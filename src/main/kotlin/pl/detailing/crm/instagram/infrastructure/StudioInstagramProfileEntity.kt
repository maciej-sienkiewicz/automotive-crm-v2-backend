package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.InstagramProfileStatus
import java.time.Instant
import java.util.*

/**
 * Powiązanie studio ↔ profil Instagramowy wraz ze statusem moderacji.
 * Wiele studiów może obserwować ten sam globalny profil (instagram_profiles).
 */
@Entity
@Table(
    name = "studio_instagram_profiles",
    indexes = [
        Index(name = "idx_studio_ig_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_studio_ig_studio_profile", columnList = "studio_id, profile_id", unique = true),
        Index(name = "idx_studio_ig_profile", columnList = "profile_id")
    ]
)
class StudioInstagramProfileEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: InstagramProfileStatus,

    @Column(name = "added_by_user_id", nullable = false, columnDefinition = "uuid")
    val addedByUserId: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
