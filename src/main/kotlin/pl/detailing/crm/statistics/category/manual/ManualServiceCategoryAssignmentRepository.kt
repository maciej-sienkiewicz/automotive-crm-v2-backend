package pl.detailing.crm.statistics.category.manual

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
interface ManualServiceCategoryAssignmentRepository :
    JpaRepository<ManualServiceCategoryAssignmentEntity, UUID> {

    fun findByStudioId(studioId: UUID): List<ManualServiceCategoryAssignmentEntity>

    fun findByManualServiceIdAndStudioId(
        manualServiceId: UUID,
        studioId: UUID
    ): ManualServiceCategoryAssignmentEntity?

    fun findByCategoryIdAndStudioId(
        categoryId: UUID,
        studioId: UUID
    ): List<ManualServiceCategoryAssignmentEntity>

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM ManualServiceCategoryAssignmentEntity a
        WHERE a.categoryId = :categoryId AND a.studioId = :studioId
    """)
    fun deleteAllByCategoryIdAndStudioId(
        @Param("categoryId") categoryId: UUID,
        @Param("studioId") studioId: UUID
    )

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM ManualServiceCategoryAssignmentEntity a
        WHERE a.manualServiceId = :manualServiceId AND a.studioId = :studioId
    """)
    fun deleteByManualServiceIdAndStudioId(
        @Param("manualServiceId") manualServiceId: UUID,
        @Param("studioId") studioId: UUID
    )
}
