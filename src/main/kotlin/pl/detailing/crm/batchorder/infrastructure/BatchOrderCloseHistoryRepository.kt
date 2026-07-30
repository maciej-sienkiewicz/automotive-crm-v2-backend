package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BatchOrderCloseHistoryRepository : JpaRepository<BatchOrderCloseHistoryEntity, UUID> {

    @Query("""
        SELECT h FROM BatchOrderCloseHistoryEntity h
        WHERE h.contractorId = :contractorId AND h.studioId = :studioId
        ORDER BY h.closedAt DESC
    """)
    fun findByContractorIdAndStudioId(contractorId: UUID, studioId: UUID): List<BatchOrderCloseHistoryEntity>

    @Query("SELECT h FROM BatchOrderCloseHistoryEntity h WHERE h.id = :id AND h.studioId = :studioId")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchOrderCloseHistoryEntity?
}
