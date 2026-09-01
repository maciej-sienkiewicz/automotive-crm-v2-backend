package pl.detailing.crm.studio.reset

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class StudioResetJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Przebieg wyczyszczenia konta. Jeden wiersz na uruchomienie; `currentStep` rośnie po
 * każdym zatwierdzonym kroku, więc po restarcie instancji job wznawia się od miejsca,
 * w którym przerwał — każdy krok czyszczenia jest idempotentny.
 */
@Entity
@Table(
    name = "studio_reset_jobs",
    indexes = [Index(name = "idx_studio_reset_jobs_studio", columnList = "studio_id")]
)
class StudioResetJobEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Owner, który zlecił reset — jedyne konto użytkownika, które przetrwa czyszczenie. */
    @Column(name = "requested_by", nullable = false, columnDefinition = "uuid")
    val requestedBy: UUID,

    /** Do wpisu audytowego po zakończeniu — principal nie jest już wtedy dostępny. */
    @Column(name = "requested_by_name", nullable = false, length = 200)
    val requestedByName: String,

    /** Czy wyczyścić także dane firmy (nazwa, NIP, adres) w ustawieniach. */
    @Column(name = "wipe_company_data", nullable = false)
    val wipeCompanyData: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: StudioResetJobStatus = StudioResetJobStatus.PENDING,

    @Column(name = "current_step", nullable = false)
    var currentStep: Int = 0,

    @Column(name = "total_steps", nullable = false)
    var totalSteps: Int = 0,

    @Column(name = "current_step_name", length = 200)
    var currentStepName: String? = null,

    @Column(name = "error", columnDefinition = "TEXT")
    var error: String? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "started_at", columnDefinition = "timestamp with time zone")
    var startedAt: Instant? = null,

    @Column(name = "finished_at", columnDefinition = "timestamp with time zone")
    var finishedAt: Instant? = null
)

@Repository
interface StudioResetJobRepository : JpaRepository<StudioResetJobEntity, UUID> {

    @Query(
        """SELECT j FROM StudioResetJobEntity j
           WHERE j.studioId = :studioId AND j.status IN ('PENDING', 'RUNNING')"""
    )
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): StudioResetJobEntity?

    @Query(
        """SELECT j FROM StudioResetJobEntity j
           WHERE j.id = :id AND j.studioId = :studioId"""
    )
    fun findByIdAndStudioId(@Param("id") id: UUID, @Param("studioId") studioId: UUID): StudioResetJobEntity?

    fun findFirstByStudioIdOrderByCreatedAtDesc(studioId: UUID): StudioResetJobEntity?

    @Query("SELECT j FROM StudioResetJobEntity j WHERE j.status IN ('PENDING', 'RUNNING')")
    fun findRunnable(): List<StudioResetJobEntity>

    /**
     * Atomowe przejęcie joba PENDING → RUNNING. Zwraca 0, gdy inna instancja aplikacji
     * zdążyła go przejąć — wtedy ta instancja odpuszcza i job nie wykona się podwójnie.
     */
    @Modifying
    @Query(
        """UPDATE StudioResetJobEntity j
           SET j.status = 'RUNNING', j.startedAt = :now
           WHERE j.id = :id AND j.status = 'PENDING'"""
    )
    fun claim(@Param("id") id: UUID, @Param("now") now: Instant): Int

    /**
     * Przejęcie joba RUNNING porzuconego przez instancję, która padła w trakcie.
     * `startedAt` pełni rolę heartbeatu (runner odświeża go po każdym kroku), więc
     * warunek `startedAt < :staleBefore` gwarantuje, że żywy przebieg nie zostanie
     * przejęty równolegle.
     */
    @Modifying
    @Query(
        """UPDATE StudioResetJobEntity j
           SET j.startedAt = :now
           WHERE j.id = :id AND j.status = 'RUNNING' AND j.startedAt < :staleBefore"""
    )
    fun reclaimStale(
        @Param("id") id: UUID,
        @Param("now") now: Instant,
        @Param("staleBefore") staleBefore: Instant
    ): Int

    /** Heartbeat + postęp po każdym zakończonym kroku. */
    @Modifying
    @Query(
        """UPDATE StudioResetJobEntity j
           SET j.currentStep = :step, j.currentStepName = :stepName, j.startedAt = :now
           WHERE j.id = :id"""
    )
    fun advance(
        @Param("id") id: UUID,
        @Param("step") step: Int,
        @Param("stepName") stepName: String?,
        @Param("now") now: Instant
    ): Int
}
