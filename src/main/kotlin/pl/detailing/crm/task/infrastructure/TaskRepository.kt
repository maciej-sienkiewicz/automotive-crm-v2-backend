package pl.detailing.crm.task.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TaskRepository : JpaRepository<TaskEntity, UUID> {

    fun findByStudioIdAndDeletedAtIsNullOrderByCreatedAtDesc(studioId: UUID): List<TaskEntity>

    @Query("""
        SELECT t FROM TaskEntity t
        WHERE t.studioId = :studioId
        AND t.deletedAt IS NOT NULL
        AND (:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun findArchivedByStudioId(
        @Param("studioId") studioId: UUID,
        @Param("search") search: String?,
        pageable: Pageable
    ): Page<TaskEntity>
}
