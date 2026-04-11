package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate

data class EmploymentContract(
    val id: EmploymentContractId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val contractType: ContractType,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val terminationDate: LocalDate?,
    val terminationReason: String?,
    val isActive: Boolean,
    val documentFileId: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
