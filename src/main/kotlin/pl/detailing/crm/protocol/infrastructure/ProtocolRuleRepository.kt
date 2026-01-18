package pl.detailing.crm.protocol.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.ProtocolTriggerType
import java.util.*

/**
 * Repository for ProtocolRule entities.
 * All queries are filtered by studioId for multi-tenancy.
 */
@Repository
interface ProtocolRuleRepository : JpaRepository<ProtocolRuleEntity, UUID> {

    @Query("SELECT pr FROM ProtocolRuleEntity pr WHERE pr.id = :id AND pr.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ProtocolRuleEntity?

    @Query("""
        SELECT pr FROM ProtocolRuleEntity pr
        WHERE pr.studioId = :studioId
        ORDER BY pr.displayOrder, pr.createdAt
    """)
    fun findAllByStudioId(@Param("studioId") studioId: UUID): List<ProtocolRuleEntity>

    @Query("""
        SELECT pr FROM ProtocolRuleEntity pr
        WHERE pr.studioId = :studioId
        AND pr.stage = :stage
        ORDER BY pr.displayOrder, pr.createdAt
    """)
    fun findAllByStudioIdAndStage(
        @Param("studioId") studioId: UUID,
        @Param("stage") stage: ProtocolStage
    ): List<ProtocolRuleEntity>

    @Query("""
        SELECT pr FROM ProtocolRuleEntity pr
        WHERE pr.studioId = :studioId
        AND pr.stage = :stage
        AND pr.triggerType = :triggerType
        ORDER BY pr.displayOrder, pr.createdAt
    """)
    fun findAllByStudioIdAndStageAndTriggerType(
        @Param("studioId") studioId: UUID,
        @Param("stage") stage: ProtocolStage,
        @Param("triggerType") triggerType: ProtocolTriggerType
    ): List<ProtocolRuleEntity>

    @Query("""
        SELECT pr FROM ProtocolRuleEntity pr
        WHERE pr.studioId = :studioId
        AND pr.serviceId = :serviceId
        ORDER BY pr.displayOrder, pr.createdAt
    """)
    fun findAllByStudioIdAndServiceId(
        @Param("studioId") studioId: UUID,
        @Param("serviceId") serviceId: UUID
    ): List<ProtocolRuleEntity>

    @Query("""
        SELECT pr FROM ProtocolRuleEntity pr
        WHERE pr.studioId = :studioId
        AND pr.stage = :stage
        AND pr.serviceId IN :serviceIds
        ORDER BY pr.displayOrder, pr.createdAt
    """)
    fun findAllByStudioIdAndStageAndServiceIdIn(
        @Param("studioId") studioId: UUID,
        @Param("stage") stage: ProtocolStage,
        @Param("serviceIds") serviceIds: List<UUID>
    ): List<ProtocolRuleEntity>
}
