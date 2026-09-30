package pl.detailing.crm.employee.leave.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface EmployeeLeaveRepository : JpaRepository<EmployeeLeaveEntity, UUID> {

    @Query("""
        SELECT l FROM EmployeeLeaveEntity l
        WHERE l.studioId = :studioId AND l.employeeId = :employeeId
        ORDER BY l.startDate DESC
    """)
    fun findByStudioIdAndEmployeeId(
        @Param("studioId") studioId: UUID,
        @Param("employeeId") employeeId: UUID
    ): List<EmployeeLeaveEntity>

    @Query("SELECT l FROM EmployeeLeaveEntity l WHERE l.id = :id AND l.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): EmployeeLeaveEntity?

    @Query("""
        SELECT l FROM EmployeeLeaveEntity l
        WHERE l.studioId = :studioId
          AND l.startDate <= :to
          AND l.endDate >= :from
    """)
    fun findOverlappingRange(
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<EmployeeLeaveEntity>

    fun deleteByStudioIdAndEmployeeId(studioId: UUID, employeeId: UUID)

    /** Nieobecności jednego pracownika nachodzące na zakres — kolizje wniosku urlopowego. */
    @Query("""
        SELECT l FROM EmployeeLeaveEntity l
        WHERE l.studioId = :studioId AND l.employeeId = :employeeId
          AND l.startDate <= :to AND l.endDate >= :from
        ORDER BY l.startDate
    """)
    fun findOverlappingOfEmployee(
        @Param("studioId") studioId: UUID,
        @Param("employeeId") employeeId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<EmployeeLeaveEntity>

    /** Odwołanie zatwierdzonego wniosku zdejmuje z kalendarza dokładnie jego wpis. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM EmployeeLeaveEntity l WHERE l.studioId = :studioId AND l.leaveRequestId = :leaveRequestId")
    fun deleteByLeaveRequestId(
        @Param("studioId") studioId: UUID,
        @Param("leaveRequestId") leaveRequestId: UUID
    ): Int
}
