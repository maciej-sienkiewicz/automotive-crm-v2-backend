package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "employee_documents",
    indexes = [
        Index(name = "idx_employee_documents_employee_studio", columnList = "employee_id, studio_id"),
        Index(name = "idx_employee_documents_uploaded_at", columnList = "employee_id, uploaded_at")
    ]
)
class EmployeeDocumentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    val name: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "file_id", nullable = false, length = 500)
    val fileId: String,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant,

    @Column(name = "uploaded_by", nullable = false, columnDefinition = "uuid")
    val uploadedBy: UUID,

    @Column(name = "uploaded_by_name", nullable = false, length = 200)
    val uploadedByName: String
)
