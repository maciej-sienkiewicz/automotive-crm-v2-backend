package pl.detailing.crm.demo

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "demo_accounts",
    indexes = [
        Index(name = "idx_demo_accounts_expires_at", columnList = "expires_at"),
        Index(name = "idx_demo_accounts_studio_id", columnList = "studio_id", unique = true)
    ]
)
class DemoAccountEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid", unique = true)
    val studioId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "email", nullable = false, length = 255)
    val email: String,

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamp with time zone")
    val expiresAt: Instant,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
