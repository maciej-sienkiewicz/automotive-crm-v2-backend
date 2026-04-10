package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface LeaveRequestRepository : JpaRepository<LeaveRequestEntity, UUID> {

    @Query("SELECT l FROM LeaveRequestEntity l WHERE l.id = :id AND l.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): LeaveRequestEntity?

    @Query("""
        SELECT l FROM LeaveRequestEntity l
        WHERE l.employeeId = :employeeId AND l.studioId = :studioId
        ORDER BY l.startDate DESC
    """)
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<LeaveRequestEntity>

    @Query("""
        SELECT l FROM LeaveRequestEntity l
        WHERE l.studioId = :studioId AND l.status = 'PENDING'
        ORDER BY l.startDate ASC
    """)
    fun findPendingByStudioId(@Param("studioId") studioId: UUID): List<LeaveRequestEntity>

    @Query("""
        SELECT l FROM LeaveRequestEntity l
        WHERE l.employeeId = :employeeId AND l.studioId = :studioId
        AND l.status IN ('PENDING', 'APPROVED')
        AND l.startDate <= :endDate AND l.endDate >= :startDate
    """)
    fun findOverlapping(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate
    ): List<LeaveRequestEntity>

    @Query("""
        SELECT SUM(l.businessDaysCount) FROM LeaveRequestEntity l
        WHERE l.employeeId = :employeeId AND l.studioId = :studioId
        AND l.leaveType = 'VACATION'
        AND l.status = 'APPROVED'
        AND YEAR(l.startDate) = :year
    """)
    fun sumApprovedVacationDays(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("year") year: Int
    ): Int?
}
