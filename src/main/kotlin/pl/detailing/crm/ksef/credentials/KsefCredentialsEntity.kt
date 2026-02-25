package pl.detailing.crm.ksef.credentials

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Stores KSeF integration credentials for a studio (tenant).
 *
 * One row per studio. The [ksefToken] is the pre-generated KSeF API token with
 * INVOICE_READ (and optionally INVOICE_WRITE) permission, issued by the KSeF portal.
 *
 * Security note: [ksefToken] should be encrypted at rest in a production environment.
 * Consider using application-level encryption or a secrets manager for higher security.
 */
@Entity
@Table(
    name = "ksef_credentials",
    uniqueConstraints = [UniqueConstraint(columnNames = ["studio_id"])]
)
class KsefCredentialsEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, unique = true)
    val studioId: UUID,

    @Column(name = "nip", nullable = false, length = 10)
    val nip: String,

    @Column(name = "ksef_token", nullable = false, length = 2048)
    val ksefToken: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
)
