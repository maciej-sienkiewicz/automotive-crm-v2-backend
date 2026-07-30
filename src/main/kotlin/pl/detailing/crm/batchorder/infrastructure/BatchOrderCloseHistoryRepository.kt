package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BatchOrderCloseHistoryRepository : JpaRepository<BatchOrderCloseHistoryEntity, UUID> {
    fun findByContractorIdAndStudioIdOrderByClosedAtDesc(
        contractorId: UUID,
        studioId: UUID
    ): List<BatchOrderCloseHistoryEntity>
}
