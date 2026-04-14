package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EmployeeDocumentRepository : JpaRepository<EmployeeDocumentEntity, UUID> {

    @Query("SELECT d FROM EmployeeDocumentEntity d WHERE d.employeeId = :employeeId AND d.studioId = :studioId ORDER BY d.uploadedAt DESC")
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<EmployeeDocumentEntity>

    @Query("SELECT d FROM EmployeeDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): EmployeeDocumentEntity?
}
