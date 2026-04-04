package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.InstagramPostReaction
import java.time.Instant
import java.util.*

/**
 * Reakcja studia na post Instagramowy konkurenta.
 * Granulacja per-studio (studioId) – każde studio może niezależnie oceniać posty.
 * Jedna reakcja na parę (studio, post).
 */
@Entity
@Table(
    name = "studio_instagram_post_reactions",
    indexes = [
        Index(name = "idx_ig_reactions_studio_post", columnList = "studio_id, post_id", unique = true),
        Index(name = "idx_ig_reactions_studio", columnList = "studio_id"),
        Index(name = "idx_ig_reactions_post", columnList = "post_id")
    ]
)
class StudioInstagramPostReactionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "post_id", nullable = false, columnDefinition = "uuid")
    val postId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction", nullable = false, length = 20)
    var reaction: InstagramPostReaction,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant
)
