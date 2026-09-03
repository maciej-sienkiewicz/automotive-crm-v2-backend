package pl.detailing.crm.instagram.ai.infrastructure

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Post wygenerowany przez AI dla studia — zapisywany ZAWSZE, także gdy weryfikacja
 * reguł się nie powiodła.
 *
 * Bez tego zapisu ocena posta (POSITIVE/NEGATIVE) nie miałaby do czego się odnieść,
 * a pętla uczenia (ocena → VectorStore → kolejne generowania) nie miałaby wejścia.
 *
 * [rulesSnapshotJson] przechowuje reguły z chwili generowania: ocena „ten post łamie
 * regułę" jest czytana względem ÓWCZESNYCH reguł, nie dzisiejszych.
 */
@Entity
@Table(
    name = "instagram_generated_posts",
    indexes = [
        Index(name = "idx_instagram_generated_posts_studio", columnList = "studio_id, created_at")
    ]
)
class InstagramGeneratedPostEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "topic", nullable = false, columnDefinition = "TEXT")
    val topic: String,

    @Column(name = "additional_context", columnDefinition = "TEXT")
    val additionalContext: String?,

    @Column(name = "requested_tone", length = 32)
    val requestedTone: String?,

    @Column(name = "requested_length", length = 16)
    val requestedLength: String?,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    val content: String,

    /** 'POSITIVE' | 'NEGATIVE'; null dopóki studio nie oceniło posta. */
    @Column(name = "rating", length = 16)
    var rating: String? = null,

    @Column(name = "rating_comment", columnDefinition = "TEXT")
    var ratingComment: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_report", columnDefinition = "jsonb")
    val verificationReportJson: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_snapshot", nullable = false, columnDefinition = "jsonb")
    val rulesSnapshotJson: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "rated_at", columnDefinition = "timestamp with time zone")
    var ratedAt: Instant? = null
)
