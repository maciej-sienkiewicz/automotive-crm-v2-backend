package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface BatchOrderEntryRepository : JpaRepository<BatchOrderEntryEntity, UUID> {

    @Query("""
        SELECT e FROM BatchOrderEntryEntity e
        WHERE e.contractorId = :contractorId AND e.studioId = :studioId
        ORDER BY e.serviceDate DESC
    """)
    fun findByContractorIdAndStudioId(contractorId: UUID, studioId: UUID): List<BatchOrderEntryEntity>

    @Query("""
        SELECT e FROM BatchOrderEntryEntity e
        WHERE e.contractorId = :contractorId AND e.studioId = :studioId
        AND e.serviceDate >= :from AND e.serviceDate <= :to
        ORDER BY e.serviceDate ASC
    """)
    fun findByContractorIdAndStudioIdAndDateRange(
        contractorId: UUID,
        studioId: UUID,
        from: LocalDate,
        to: LocalDate
    ): List<BatchOrderEntryEntity>

    @Query("SELECT e FROM BatchOrderEntryEntity e WHERE e.id = :id AND e.studioId = :studioId")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchOrderEntryEntity?
}
