package pl.detailing.crm.audit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Write side of the audit log.
 *
 * All reads go through [AuditFeedQueryRepository]: the derived-query variants that used to
 * live here (by module, by action, by user, by date range) were never called — the list
 * handler always took the combined-filter path — and every combination of filters would
 * have needed its own method. Composed predicates cover them all.
 */
@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntity, UUID> {

    /**
     * Newest feed entry of a given action for a visit — the anchor row that in-flow
     * follow-ups (e.g. protocol signature during check-in) enrich instead of logging
     * their own near-duplicate entry.
     */
    @Query(
        value = """
            SELECT id FROM audit_logs
            WHERE studio_id = :studioId AND action = :action AND visit_id = :visitId
            ORDER BY created_at DESC
            LIMIT 1
        """,
        nativeQuery = true
    )
    fun findLatestIdByStudioIdAndActionAndVisitId(
        @Param("studioId") studioId: UUID,
        @Param("action") action: String,
        @Param("visitId") visitId: UUID
    ): UUID?

    /** Shallow jsonb merge of [patch] into the row's metadata (Postgres `||`). */
    @Modifying
    @Query(
        value = "UPDATE audit_logs SET metadata = COALESCE(metadata, '{}'::jsonb) || CAST(:patch AS jsonb) WHERE id = :id",
        nativeQuery = true
    )
    fun mergeMetadata(@Param("id") id: UUID, @Param("patch") patch: String): Int
}
