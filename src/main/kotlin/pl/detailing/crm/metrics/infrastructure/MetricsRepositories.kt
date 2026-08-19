package pl.detailing.crm.metrics.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.metrics.domain.ErrorGroupStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Spring Data repositories for the metrics module, grouped in one file the way
 * `EntitlementRepositories.kt` already is — these are one-to-five-method interfaces
 * and eleven separate files would obscure rather than organise them.
 *
 * Note on tenant isolation: the platform console reads deliberately **across** tenants,
 * so the project-wide "every query filters by studio_id" rule does not apply to the
 * aggregate queries here. It still applies to everything a studio user can reach —
 * the tenant-facing session and error endpoints always pass the caller's own studioId.
 */

@Repository
interface MetricEventRepository : JpaRepository<MetricEventEntity, UUID> {

    @Modifying
    @Query("DELETE FROM MetricEventEntity e WHERE e.occurredAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface UserSessionRepository : JpaRepository<UserSessionEntity, UUID> {

    /** The open session for a browser session, if any. At most one by construction. */
    @Query(
        """
        SELECT s FROM UserSessionEntity s
        WHERE s.sessionKey = :sessionKey AND s.endedAt IS NULL
        ORDER BY s.startedAt DESC
        """
    )
    fun findOpenBySessionKey(@Param("sessionKey") sessionKey: String): List<UserSessionEntity>

    /** Sessions that stopped reporting in — the sweeper's input. */
    @Query(
        """
        SELECT s FROM UserSessionEntity s
        WHERE s.endedAt IS NULL AND s.lastActivityAt < :threshold
        """
    )
    fun findStale(@Param("threshold") threshold: Instant): List<UserSessionEntity>

    @Query(
        """
        SELECT s FROM UserSessionEntity s
        WHERE s.studioId = :studioId AND s.userId = :userId AND s.endedAt IS NULL
        """
    )
    fun findOpenForUser(
        @Param("studioId") studioId: UUID,
        @Param("userId") userId: UUID
    ): List<UserSessionEntity>

    @Modifying
    @Query("DELETE FROM UserSessionEntity s WHERE s.startedAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface ApiEndpointRepository : JpaRepository<ApiEndpointEntity, UUID> {

    @Query(
        """
        SELECT e FROM ApiEndpointEntity e
        WHERE e.httpMethod = :method AND e.pathTemplate = :path
        """
    )
    fun findBySignature(
        @Param("method") method: String,
        @Param("path") path: String
    ): ApiEndpointEntity?

    @Query("SELECT e FROM ApiEndpointEntity e WHERE e.isActiveInCode = true")
    fun findAllActiveInCode(): List<ApiEndpointEntity>

    /** Earliest catalog entry — how long the audit has actually been observing traffic. */
    @Query("SELECT MIN(e.firstSeenAt) FROM ApiEndpointEntity e")
    fun findObservationStart(): Instant?

    @Modifying
    @Query(
        """
        UPDATE ApiEndpointEntity e
        SET e.isActiveInCode = false
        WHERE e.lastSeenInCodeAt < :bootTime AND e.isActiveInCode = true
        """
    )
    fun markMissingFromCode(@Param("bootTime") bootTime: Instant): Int
}

@Repository
interface ApiEndpointDailyRepository : JpaRepository<ApiEndpointDailyEntity, UUID> {

    @Modifying
    @Query("DELETE FROM ApiEndpointDailyEntity d WHERE d.usageDate < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: LocalDate): Int
}

@Repository
interface StudioApiDailyRepository : JpaRepository<StudioApiDailyEntity, UUID> {

    @Modifying
    @Query("DELETE FROM StudioApiDailyEntity d WHERE d.usageDate < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: LocalDate): Int
}

@Repository
interface ErrorEventRepository : JpaRepository<ErrorEventEntity, UUID> {

    @Query(
        """
        SELECT e FROM ErrorEventEntity e
        WHERE e.fingerprint = :fingerprint
        ORDER BY e.occurredAt DESC
        """
    )
    fun findRecentByFingerprint(@Param("fingerprint") fingerprint: String): List<ErrorEventEntity>

    @Modifying
    @Query("DELETE FROM ErrorEventEntity e WHERE e.occurredAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: Instant): Int
}

@Repository
interface ErrorGroupRepository : JpaRepository<ErrorGroupEntity, String> {

    @Query(
        """
        SELECT g FROM ErrorGroupEntity g
        WHERE (:status IS NULL OR g.status = :status)
        ORDER BY g.lastSeenAt DESC
        """
    )
    fun findByStatusOrderByLastSeen(@Param("status") status: ErrorGroupStatus?): List<ErrorGroupEntity>

    @Query("SELECT COUNT(g) FROM ErrorGroupEntity g WHERE g.firstSeenAt >= :from AND g.firstSeenAt < :to")
    fun countNewBetween(@Param("from") from: Instant, @Param("to") to: Instant): Int
}

@Repository
interface ErrorGroupImpactRepository : JpaRepository<ErrorGroupImpactEntity, UUID> {

    @Query(
        """
        SELECT i FROM ErrorGroupImpactEntity i
        WHERE i.fingerprint = :fingerprint AND i.studioId = :studioId
        """
    )
    fun find(
        @Param("fingerprint") fingerprint: String,
        @Param("studioId") studioId: UUID
    ): ErrorGroupImpactEntity?

    @Query(
        """
        SELECT i FROM ErrorGroupImpactEntity i
        WHERE i.fingerprint = :fingerprint
        ORDER BY i.occurrences DESC
        """
    )
    fun findByFingerprint(@Param("fingerprint") fingerprint: String): List<ErrorGroupImpactEntity>

    @Query("SELECT COUNT(i) FROM ErrorGroupImpactEntity i WHERE i.fingerprint = :fingerprint")
    fun countStudios(@Param("fingerprint") fingerprint: String): Int
}

@Repository
interface StudioDailySnapshotRepository : JpaRepository<StudioDailySnapshotEntity, UUID> {

    @Query(
        """
        SELECT s FROM StudioDailySnapshotEntity s
        WHERE s.studioId = :studioId AND s.snapshotDate BETWEEN :from AND :to
        ORDER BY s.snapshotDate
        """
    )
    fun findForStudio(
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<StudioDailySnapshotEntity>

    @Query(
        """
        SELECT s FROM StudioDailySnapshotEntity s
        WHERE s.snapshotDate = :date
        ORDER BY s.activeMinutesTotal DESC
        """
    )
    fun findAllForDate(@Param("date") date: LocalDate): List<StudioDailySnapshotEntity>
}

@Repository
interface PlatformDailySnapshotRepository : JpaRepository<PlatformDailySnapshotEntity, LocalDate> {

    @Query(
        """
        SELECT p FROM PlatformDailySnapshotEntity p
        WHERE p.snapshotDate BETWEEN :from AND :to
        ORDER BY p.snapshotDate
        """
    )
    fun findRange(
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<PlatformDailySnapshotEntity>
}
