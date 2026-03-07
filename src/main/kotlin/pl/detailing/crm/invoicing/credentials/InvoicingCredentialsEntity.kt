package pl.detailing.crm.invoicing.credentials

import jakarta.persistence.*
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import java.time.Instant
import java.util.UUID

/**
 * Stores API credentials (key/token) for a single external invoicing provider per studio.
 * Each studio can configure exactly one provider.
 *
 * The [apiKey] is stored in plain text. If encryption at rest is required,
 * it should be handled at the infrastructure level (e.g. column encryption).
 */
@Entity
@Table(
    name = "invoicing_credentials",
    uniqueConstraints = [UniqueConstraint(columnNames = ["studio_id"])],
    indexes = [Index(name = "idx_invoicing_creds_studio_id", columnList = "studio_id")]
)
class InvoicingCredentialsEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID = UUID.randomUUID(),

    /** Multi-tenant scope – one studio, one provider. */
    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false, unique = true)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    val provider: InvoiceProviderType,

    /** API key / token for the chosen provider. */
    @Column(name = "api_key", nullable = false, length = 500)
    val apiKey: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
