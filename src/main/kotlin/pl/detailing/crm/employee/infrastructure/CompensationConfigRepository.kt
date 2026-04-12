package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface CompensationConfigRepository : JpaRepository<CompensationConfigEntity, UUID> {

    @Query("SELECT c FROM CompensationConfigEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): CompensationConfigEntity?

    @Query("""
        SELECT c FROM CompensationConfigEntity c
        WHERE c.employeeId = :employeeId AND c.studioId = :studioId
        ORDER BY c.effectiveFrom DESC
    """)
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<CompensationConfigEntity>

    @Query("""
        SELECT c FROM CompensationConfigEntity c
        WHERE c.employeeId = :employeeId AND c.studioId = :studioId AND c.effectiveTo IS NULL
        ORDER BY c.effectiveFrom DESC
    """)
    fun findCurrentByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): CompensationConfigEntity?

    @Query("""
        SELECT c FROM CompensationConfigEntity c
        WHERE c.contractId = :contractId AND c.studioId = :studioId AND c.effectiveTo IS NULL
    """)
    fun findActiveByContractId(
        @Param("contractId") contractId: UUID,
        @Param("studioId") studioId: UUID
    ): CompensationConfigEntity?

    @Query("""
        SELECT c FROM CompensationConfigEntity c
        WHERE c.employeeId = :employeeId AND c.studioId = :studioId
        AND c.effectiveFrom <= :date
        AND (c.effectiveTo IS NULL OR c.effectiveTo >= :date)
        ORDER BY c.effectiveFrom DESC
    """)
    fun findForDate(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("date") date: LocalDate
    ): CompensationConfigEntity?
}
