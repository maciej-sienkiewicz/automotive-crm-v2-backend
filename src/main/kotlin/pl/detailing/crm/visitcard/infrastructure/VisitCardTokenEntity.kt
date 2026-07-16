package pl.detailing.crm.visitcard.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Persistent, unguessable access token for a customer-facing Visit Card page.
 *
 * The token is the sole credential: whoever holds the link can view the card
 * (no login). One token per visit — generated lazily on first request and
 * reused afterwards, so the link sent to the customer never changes.
 */
@Entity
@Table(
    name = "visit_card_tokens",
    indexes = [
        Index(name = "idx_visit_card_tokens_token", columnList = "token", unique = true),
        Index(name = "idx_visit_card_tokens_visit", columnList = "studio_id, visit_id", unique = true)
    ]
)
class VisitCardTokenEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "token", nullable = false, length = 64, unique = true)
    val token: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
