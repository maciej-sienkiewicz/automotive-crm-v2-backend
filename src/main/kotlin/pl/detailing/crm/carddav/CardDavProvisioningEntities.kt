package pl.detailing.crm.carddav

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Hasło aplikacyjne CardDAV — sekret wygenerowany per TELEFON, wpisany do
 * profilu .mobileconfig. Użytkownik nigdy go nie widzi; odwołanie odcina jeden
 * telefon, nie ruszając konta ani pozostałych urządzeń.
 */
@Entity
@Table(name = "carddav_app_passwords")
class CardDavAppPasswordEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "device_name", nullable = false, length = 120)
    val deviceName: String,

    @Column(name = "secret_hash", nullable = false, length = 255)
    val secretHash: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at", columnDefinition = "timestamp with time zone")
    var lastUsedAt: Instant? = null,

    @Column(name = "revoked_at", columnDefinition = "timestamp with time zone")
    var revokedAt: Instant? = null,
)

@Repository
interface CardDavAppPasswordRepository : JpaRepository<CardDavAppPasswordEntity, UUID> {

    @Query("SELECT p FROM CardDavAppPasswordEntity p WHERE p.userId = :userId AND p.revokedAt IS NULL")
    fun findActiveByUserId(@Param("userId") userId: UUID): List<CardDavAppPasswordEntity>

    @Query("SELECT p FROM CardDavAppPasswordEntity p WHERE p.userId = :userId ORDER BY p.createdAt DESC")
    fun findAllByUserId(@Param("userId") userId: UUID): List<CardDavAppPasswordEntity>

    @Query("SELECT p FROM CardDavAppPasswordEntity p WHERE p.id = :id AND p.userId = :userId")
    fun findByIdAndUserId(@Param("id") id: UUID, @Param("userId") userId: UUID): CardDavAppPasswordEntity?
}

/**
 * Jednorazowy link instalacyjny profilu. Niesie hasło aplikacyjne otwartym
 * tekstem (profil budujemy dopiero przy pobraniu), więc żyje minuty, ma jedno
 * użycie, a sekret jest zerowany w chwili wydania profilu.
 */
@Entity
@Table(name = "carddav_provisionings")
class CardDavProvisioningEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "app_password_id", nullable = false, columnDefinition = "uuid")
    val appPasswordId: UUID,

    @Column(name = "token", nullable = false, unique = true, length = 64)
    val token: String,

    @Column(name = "secret_plain", length = 64)
    var secretPlain: String?,

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamp with time zone")
    val expiresAt: Instant,

    @Column(name = "used_at", columnDefinition = "timestamp with time zone")
    var usedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),
)

@Repository
interface CardDavProvisioningRepository : JpaRepository<CardDavProvisioningEntity, UUID> {
    fun findByToken(token: String): CardDavProvisioningEntity?
}
