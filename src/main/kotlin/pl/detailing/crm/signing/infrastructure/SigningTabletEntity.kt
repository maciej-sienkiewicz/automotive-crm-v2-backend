package pl.detailing.crm.signing.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * A tablet paired with a studio. Durable on purpose: the pairing survives restarts
 * of the cache, of the application and of the whole stack. It ends when somebody
 * revokes it — never because infrastructure was restarted.
 *
 * The token is held as a hash; the clear-text value exists once, in the response to
 * the pairing call, and afterwards only on the device itself.
 */
@Entity
@Table(
    name = "signing_tablets",
    indexes = [
        Index(name = "idx_signing_tablets_token", columnList = "token_hash", unique = true),
        Index(name = "idx_signing_tablets_studio", columnList = "studio_id")
    ]
)
class SigningTabletEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "device_name", nullable = false, length = 200)
    var deviceName: String,

    @Column(name = "token_hash", nullable = false, length = 64)
    val tokenHash: String,

    @Column(name = "paired_at", nullable = false)
    val pairedAt: Instant = Instant.now(),

    @Column(name = "last_seen_at")
    var lastSeenAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
)

@Repository
interface SigningTabletRepository : JpaRepository<SigningTabletEntity, UUID> {

    fun findByTokenHash(tokenHash: String): SigningTabletEntity?

    fun findByStudioIdAndRevokedAtIsNullOrderByPairedAtAsc(studioId: UUID): List<SigningTabletEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): SigningTabletEntity?

    /**
     * Odnotowanie kontaktu z urządzeniem osobnym zapisem, poza transakcją odczytu:
     * tablet uwierzytelnia każde żądanie, a pełny zapis encji przy każdym z nich
     * kosztowałby więcej niż warta jest ta informacja.
     */
    @Modifying
    @Query("UPDATE SigningTabletEntity t SET t.lastSeenAt = :now WHERE t.id = :id")
    fun touchLastSeen(@Param("id") id: UUID, @Param("now") now: Instant)
}
