package pl.detailing.crm.task.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TaskRepository : JpaRepository<TaskEntity, UUID> {

    fun findByStudioIdAndDeletedAtIsNullOrderByCreatedAtDesc(studioId: UUID): List<TaskEntity>

    fun findByStudioIdAndDeletedAtIsNotNull(studioId: UUID, pageable: Pageable): Page<TaskEntity>
}
