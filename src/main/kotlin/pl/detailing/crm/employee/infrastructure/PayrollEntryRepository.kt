package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PayrollEntryRepository : JpaRepository<PayrollEntryEntity, UUID> {

    @Query("SELECT p FROM PayrollEntryEntity p WHERE p.id = :id AND p.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): PayrollEntryEntity?

    @Query("""
        SELECT p FROM PayrollEntryEntity p
        WHERE p.employeeId = :employeeId AND p.studioId = :studioId
        ORDER BY p.period DESC
    """)
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<PayrollEntryEntity>

    @Query("""
        SELECT p FROM PayrollEntryEntity p
        WHERE p.studioId = :studioId AND p.period = :period
        ORDER BY p.createdAt DESC
    """)
    fun findByStudioIdAndPeriod(
        @Param("studioId") studioId: UUID,
        @Param("period") period: String
    ): List<PayrollEntryEntity>

    @Query("""
        SELECT COUNT(p) > 0 FROM PayrollEntryEntity p
        WHERE p.employeeId = :employeeId AND p.studioId = :studioId AND p.period = :period
    """)
    fun existsByEmployeeIdAndPeriod(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("period") period: String
    ): Boolean
}
