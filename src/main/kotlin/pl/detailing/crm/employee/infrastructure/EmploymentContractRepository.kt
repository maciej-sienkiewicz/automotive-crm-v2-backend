package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EmploymentContractRepository : JpaRepository<EmploymentContractEntity, UUID> {

    @Query("SELECT c FROM EmploymentContractEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): EmploymentContractEntity?

    @Query("SELECT c FROM EmploymentContractEntity c WHERE c.employeeId = :employeeId AND c.studioId = :studioId ORDER BY c.startDate DESC")
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<EmploymentContractEntity>

    @Query("SELECT c FROM EmploymentContractEntity c WHERE c.employeeId = :employeeId AND c.studioId = :studioId AND c.isActive = true")
    fun findActiveByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): EmploymentContractEntity?

    @Query("SELECT COUNT(c) > 0 FROM EmploymentContractEntity c WHERE c.employeeId = :employeeId AND c.isActive = true")
    fun existsActiveByEmployeeId(@Param("employeeId") employeeId: UUID): Boolean
}
