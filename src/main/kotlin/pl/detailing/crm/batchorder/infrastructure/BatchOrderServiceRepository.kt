package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BatchOrderServiceRepository : JpaRepository<BatchOrderServiceEntity, UUID> {

    @Query("""
        SELECT s FROM BatchOrderServiceEntity s
        WHERE s.studioId = :studioId AND s.isActive = true
        ORDER BY LOWER(s.name) ASC
    """)
    fun findActiveByStudioId(studioId: UUID): List<BatchOrderServiceEntity>

    @Query("""
        SELECT s FROM BatchOrderServiceEntity s
        WHERE s.studioId = :studioId AND s.isActive = true
          AND LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY LOWER(s.name) ASC
    """)
    fun searchActiveByStudioId(studioId: UUID, q: String): List<BatchOrderServiceEntity>

    @Query("SELECT s FROM BatchOrderServiceEntity s WHERE s.id = :id AND s.studioId = :studioId")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchOrderServiceEntity?

    /**
     * Name lookup for the uniqueness rule. Case-insensitive on purpose: the unique
     * index is on `LOWER(name)`, so a check that respected case would let the database
     * reject what the application had just approved.
     */
    @Query("""
        SELECT s FROM BatchOrderServiceEntity s
        WHERE s.studioId = :studioId AND s.isActive = true AND LOWER(s.name) = LOWER(:name)
    """)
    fun findActiveByStudioIdAndName(studioId: UUID, name: String): BatchOrderServiceEntity?
}
