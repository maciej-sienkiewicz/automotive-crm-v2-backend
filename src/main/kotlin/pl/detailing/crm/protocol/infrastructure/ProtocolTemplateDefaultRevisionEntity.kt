package pl.detailing.crm.protocol.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Which revision of the bundled default protocol template a studio's system
 * template currently holds in S3.
 *
 * Not a column on [ProtocolTemplateEntity] on purpose: `fromDomain()` rebuilds the
 * whole row on every PATCH/verify save, so a column here would be reset to its
 * default and trigger a pointless re-upload on the next boot.
 */
@Entity
@Table(name = "protocol_template_default_revisions")
class ProtocolTemplateDefaultRevisionEntity(
    @Id
    @Column(name = "template_id", columnDefinition = "uuid")
    val templateId: UUID,

    @Column(name = "revision", nullable = false)
    var revision: Int,

    @Column(name = "applied_at", nullable = false)
    var appliedAt: Instant = Instant.now()
)

@Repository
interface ProtocolTemplateDefaultRevisionRepository :
    JpaRepository<ProtocolTemplateDefaultRevisionEntity, UUID>
