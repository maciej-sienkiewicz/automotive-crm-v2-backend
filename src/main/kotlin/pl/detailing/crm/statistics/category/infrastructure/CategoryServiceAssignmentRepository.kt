package pl.detailing.crm.statistics.category.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * DTO for the batch service-count-per-category query.
 * Must be a concrete class (not interface) for JPQL constructor expressions.
 */
data class CategoryServiceCountRow(
    val categoryId: UUID,
    val serviceCount: Long
)

@Repository
interface CategoryServiceAssignmentRepository : JpaRepository<CategoryServiceAssignmentEntity, UUID> {

    @Query("""
        SELECT a FROM CategoryServiceAssignmentEntity a
        WHERE a.categoryId = :categoryId AND a.studioId = :studioId
    """)
    fun findByCategoryIdAndStudioId(
        @Param("categoryId") categoryId: UUID,
        @Param("studioId") studioId: UUID
    ): List<CategoryServiceAssignmentEntity>

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM CategoryServiceAssignmentEntity a
        WHERE a.categoryId = :categoryId AND a.studioId = :studioId
    """)
    fun deleteAllByCategoryIdAndStudioId(
        @Param("categoryId") categoryId: UUID,
        @Param("studioId") studioId: UUID
    )

    /**
     * Batch count of services per category for a studio.
     * Uses JPQL constructor expression to avoid unsafe Object[] casting.
     */
    @Query("""
        SELECT new pl.detailing.crm.statistics.category.infrastructure.CategoryServiceCountRow(
            a.categoryId,
            COUNT(a.serviceId)
        )
        FROM CategoryServiceAssignmentEntity a
        WHERE a.studioId = :studioId
        GROUP BY a.categoryId
    """)
    fun countServicesByCategoryForStudio(
        @Param("studioId") studioId: UUID
    ): List<CategoryServiceCountRow>

    @Query("""
        SELECT COUNT(a) FROM CategoryServiceAssignmentEntity a
        WHERE a.categoryId = :categoryId AND a.studioId = :studioId
    """)
    fun countByCategoryIdAndStudioId(
        @Param("categoryId") categoryId: UUID,
        @Param("studioId") studioId: UUID
    ): Long
}
