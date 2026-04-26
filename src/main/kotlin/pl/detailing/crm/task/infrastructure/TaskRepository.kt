package pl.detailing.crm.task.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TaskRepository : JpaRepository<TaskEntity, UUID> {

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<TaskEntity>
}
