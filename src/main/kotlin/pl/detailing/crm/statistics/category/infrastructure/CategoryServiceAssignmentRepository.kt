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

/**
 * DTO for the batch service-ids-per-category query.
 * Must be a concrete class (not interface) for JPQL constructor expressions.
 */
data class CategoryServiceIdRow(
    val categoryId: UUID,
    val serviceId: UUID
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

    @Query("""
        SELECT a FROM CategoryServiceAssignmentEntity a
        WHERE a.serviceId = :serviceId AND a.studioId = :studioId
    """)
    fun findByServiceIdAndStudioId(
        @Param("serviceId") serviceId: UUID,
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

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM CategoryServiceAssignmentEntity a
        WHERE a.serviceId = :serviceId AND a.studioId = :studioId
    """)
    fun deleteByServiceIdAndStudioId(
        @Param("serviceId") serviceId: UUID,
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

    /**
     * Batch fetch of all (categoryId, serviceId) pairs for a studio.
     * Used to populate serviceIds in the category list response and for breakdown queries.
     */
    @Query("""
        SELECT new pl.detailing.crm.statistics.category.infrastructure.CategoryServiceIdRow(
            a.categoryId,
            a.serviceId
        )
        FROM CategoryServiceAssignmentEntity a
        WHERE a.studioId = :studioId
    """)
    fun findAllServiceIdsByStudio(
        @Param("studioId") studioId: UUID
    ): List<CategoryServiceIdRow>

    @Query("""
        SELECT COUNT(a) FROM CategoryServiceAssignmentEntity a
        WHERE a.categoryId = :categoryId AND a.studioId = :studioId
    """)
    fun countByCategoryIdAndStudioId(
        @Param("categoryId") categoryId: UUID,
        @Param("studioId") studioId: UUID
    ): Long
}
