package pl.detailing.crm.careinstruction.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CareInstructionRepository : JpaRepository<CareInstructionEntity, UUID> {

    @Query(
        """
        SELECT c FROM CareInstructionEntity c
        WHERE c.studioId = :studioId
        ORDER BY c.sortOrder ASC, c.createdAt ASC
        """
    )
    fun findAllByStudio(@Param("studioId") studioId: UUID): List<CareInstructionEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CareInstructionEntity?

    @Query("SELECT COALESCE(MAX(c.sortOrder), -1) FROM CareInstructionEntity c WHERE c.studioId = :studioId")
    fun maxSortOrder(@Param("studioId") studioId: UUID): Int

    fun countByStudioId(studioId: UUID): Long
}

@Repository
interface ServiceCareInstructionRepository : JpaRepository<ServiceCareInstructionEntity, UUID> {

    fun findByStudioId(studioId: UUID): List<ServiceCareInstructionEntity>

    fun findByStudioIdAndServiceId(studioId: UUID, serviceId: UUID): List<ServiceCareInstructionEntity>

    @Modifying
    @Query("DELETE FROM ServiceCareInstructionEntity e WHERE e.studioId = :studioId AND e.serviceId = :serviceId")
    fun deleteByService(@Param("studioId") studioId: UUID, @Param("serviceId") serviceId: UUID)

    @Modifying
    @Query("DELETE FROM ServiceCareInstructionEntity e WHERE e.studioId = :studioId AND e.careInstructionId = :instructionId")
    fun deleteByInstruction(@Param("studioId") studioId: UUID, @Param("instructionId") instructionId: UUID)
}
