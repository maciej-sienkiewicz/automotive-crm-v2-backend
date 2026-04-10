package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface LeaveBalanceRepository : JpaRepository<LeaveBalanceEntity, UUID> {

    @Query("""
        SELECT b FROM LeaveBalanceEntity b
        WHERE b.employeeId = :employeeId AND b.studioId = :studioId AND b.year = :year
    """)
    fun findByEmployeeIdAndYear(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("year") year: Int
    ): LeaveBalanceEntity?

    @Query("""
        SELECT b FROM LeaveBalanceEntity b
        WHERE b.employeeId = :employeeId AND b.studioId = :studioId
        ORDER BY b.year DESC
    """)
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<LeaveBalanceEntity>
}
