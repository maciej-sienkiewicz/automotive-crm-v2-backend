package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.*

/**
 * Etykieta tematyczna posta – wynik klasyfikacji caption (słownik branżowy detailingu).
 * Jeden wiersz per post; klasyfikacja jest idempotentna (post klasyfikowany raz).
 */
@Entity
@Table(
    name = "instagram_post_topics",
    indexes = [
        Index(name = "idx_ig_topics_post", columnList = "post_id", unique = true),
        Index(name = "idx_ig_topics_topic", columnList = "topic"),
        Index(name = "idx_ig_topics_promo", columnList = "is_promo")
    ]
)
class InstagramPostTopicEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "post_id", nullable = false, columnDefinition = "uuid", unique = true)
    val postId: UUID,

    /** Wartość z [pl.detailing.crm.instagram.analytics.InstagramPostTopic]. */
    @Column(name = "topic", nullable = false, length = 40)
    var topic: String,

    /** true gdy post ogłasza promocję / rabat / obniżkę ceny. */
    @Column(name = "is_promo", nullable = false)
    var isPromo: Boolean,

    /** true gdy post ogłasza konkurs / rozdanie. */
    @Column(name = "is_contest", nullable = false)
    var isContest: Boolean,

    /** Wykryta wysokość rabatu w %, jeśli podana w treści (np. "-20%"). */
    @Column(name = "discount_pct", nullable = true)
    var discountPct: Int?,

    /** REGEX | LLM – sposób klasyfikacji. */
    @Column(name = "method", nullable = false, length = 10)
    var method: String,

    @Column(name = "classified_at", nullable = false, columnDefinition = "timestamp with time zone")
    var classifiedAt: Instant
)
