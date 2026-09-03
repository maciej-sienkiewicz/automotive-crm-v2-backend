package pl.detailing.crm.worktime.attendance

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Wygenerowana lista obecności.
 *
 * Arkusz jest dokumentem kadrowym, który się podpisuje — więc musi przetrwać zamknięcie
 * okna. Plik (PDF) leży w S3; tutaj zostaje to, co pozwala go odszukać i powiedzieć,
 * czy jest już podpisany.
 *
 * Podpisany arkusz to OSOBNY plik ([signedFileS3Key]) obok oryginału: dzięki temu widać,
 * co dokładnie zostało podpisane, a podpis nie nadpisuje dokumentu, na który się powołuje.
 */
@Entity
@Table(
    name = "attendance_sheets",
    indexes = [
        Index(name = "idx_attendance_sheets_studio", columnList = "studio_id, created_at")
    ]
)
class AttendanceSheetEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Miesiąc arkusza w formacie YYYY-MM. */
    @Column(name = "period", nullable = false, length = 7)
    val period: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "employee_ids", nullable = false, columnDefinition = "jsonb")
    val employeeIdsJson: String,

    @Column(name = "file_s3_key", nullable = false, length = 500)
    val fileS3Key: String,

    @Column(name = "signed_file_s3_key", length = 500)
    var signedFileS3Key: String? = null,

    @Column(name = "signer_name", length = 200)
    var signerName: String? = null,

    @Column(name = "signed_at", columnDefinition = "timestamp with time zone")
    var signedAt: Instant? = null,

    @Column(name = "signed_by", columnDefinition = "uuid")
    var signedBy: UUID? = null,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant
)

@Repository
interface AttendanceSheetRepository : JpaRepository<AttendanceSheetEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): AttendanceSheetEntity?

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<AttendanceSheetEntity>
}
