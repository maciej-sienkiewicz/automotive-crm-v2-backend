package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.EmploymentContract
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "employment_contracts",
    indexes = [
        Index(name = "idx_contracts_studio_employee", columnList = "studio_id, employee_id"),
        Index(name = "idx_contracts_studio_active", columnList = "studio_id, is_active"),
        Index(name = "idx_contracts_employee_active", columnList = "employee_id, is_active")
    ]
)
class EmploymentContractEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 20)
    var contractType: ContractType,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date")
    var endDate: LocalDate?,

    @Column(name = "termination_date")
    var terminationDate: LocalDate?,

    @Column(name = "termination_reason", columnDefinition = "text")
    var terminationReason: String?,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean,

    @Column(name = "document_file_id", length = 500)
    var documentFileId: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): EmploymentContract = EmploymentContract(
        id = EmploymentContractId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        contractType = contractType,
        startDate = startDate,
        endDate = endDate,
        terminationDate = terminationDate,
        terminationReason = terminationReason,
        isActive = isActive,
        documentFileId = documentFileId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(contract: EmploymentContract): EmploymentContractEntity = EmploymentContractEntity(
            id = contract.id.value,
            studioId = contract.studioId.value,
            employeeId = contract.employeeId.value,
            contractType = contract.contractType,
            startDate = contract.startDate,
            endDate = contract.endDate,
            terminationDate = contract.terminationDate,
            terminationReason = contract.terminationReason,
            isActive = contract.isActive,
            documentFileId = contract.documentFileId,
            createdAt = contract.createdAt,
            updatedAt = contract.updatedAt
        )
    }
}
