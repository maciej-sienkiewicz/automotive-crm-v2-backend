package pl.detailing.crm.audit.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import java.time.Instant
import java.util.UUID

@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntity, UUID> {

    /**
     * Get all audit logs for a studio, ordered by most recent first.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioId(
        @Param("studioId") studioId: UUID,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs for a studio filtered by module.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.module = :module
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndModule(
        @Param("studioId") studioId: UUID,
        @Param("module") module: AuditModule,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs for a specific entity within a module.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.module = :module
        AND a.entityId = :entityId
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndModuleAndEntityId(
        @Param("studioId") studioId: UUID,
        @Param("module") module: AuditModule,
        @Param("entityId") entityId: String,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs filtered by multiple modules (for the "exclude" filter on UI).
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.module IN :modules
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndModuleIn(
        @Param("studioId") studioId: UUID,
        @Param("modules") modules: List<AuditModule>,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs filtered by multiple actions.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.action IN :actions
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndActionIn(
        @Param("studioId") studioId: UUID,
        @Param("actions") actions: List<AuditAction>,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs filtered by modules AND actions.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.module IN :modules
        AND a.action IN :actions
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndModuleInAndActionIn(
        @Param("studioId") studioId: UUID,
        @Param("modules") modules: List<AuditModule>,
        @Param("actions") actions: List<AuditAction>,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs for a specific user within a studio.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.userId = :userId
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndUserId(
        @Param("studioId") studioId: UUID,
        @Param("userId") userId: UUID,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Get audit logs within a date range.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND a.createdAt >= :from
        AND a.createdAt <= :to
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdAndCreatedAtBetween(
        @Param("studioId") studioId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable
    ): Page<AuditLogEntity>

    /**
     * Full filter: modules + actions + user + date range.
     */
    @Query("""
        SELECT a FROM AuditLogEntity a
        WHERE a.studioId = :studioId
        AND (:modules IS NULL OR a.module IN :modules)
        AND (:actions IS NULL OR a.action IN :actions)
        AND (:userId IS NULL OR a.userId = :userId)
        AND (:from IS NULL OR a.createdAt >= :from)
        AND (:to IS NULL OR a.createdAt <= :to)
        ORDER BY a.createdAt DESC
    """)
    fun findByStudioIdWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("modules") modules: List<AuditModule>?,
        @Param("actions") actions: List<AuditAction>?,
        @Param("userId") userId: UUID?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable
    ): Page<AuditLogEntity>
}
