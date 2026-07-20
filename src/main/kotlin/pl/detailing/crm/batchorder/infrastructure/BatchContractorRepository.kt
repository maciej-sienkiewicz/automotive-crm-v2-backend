package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BatchContractorRepository : JpaRepository<BatchContractorEntity, UUID> {

    @Query("SELECT c FROM BatchContractorEntity c WHERE c.studioId = :studioId AND c.isActive = true ORDER BY c.name ASC")
    fun findActiveByStudioId(studioId: UUID): List<BatchContractorEntity>

    @Query("SELECT c FROM BatchContractorEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchContractorEntity?

    @Query("SELECT COUNT(e) FROM BatchOrderEntryEntity e WHERE e.contractorId = :contractorId AND e.studioId = :studioId")
    fun countEntriesByContractorId(contractorId: UUID, studioId: UUID): Long
}
