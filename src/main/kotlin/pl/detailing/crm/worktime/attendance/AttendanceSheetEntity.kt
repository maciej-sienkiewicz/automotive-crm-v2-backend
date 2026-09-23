package pl.detailing.crm.worktime.attendance

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
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
 *
 * Lista to zarazem rozliczenie miesiąca: [status] mówi wszystkim administratorom, czy
 * ktoś ją już sprawdził i zatwierdził. Bez tego plik znikał w folderze Pobrane jednej
 * osoby i nikt inny nie wiedział, czy poszedł do księgowości.
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
    val createdAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(20) not null default 'GENERATED'")
    var status: AttendanceSheetStatus = AttendanceSheetStatus.GENERATED,

    /** Imię i nazwisko z chwili generowania — konto może zmienić nazwę albo zniknąć. */
    @Column(name = "created_by_name", length = 200)
    val createdByName: String? = null,

    @Column(name = "approved_at", columnDefinition = "timestamp with time zone")
    var approvedAt: Instant? = null,

    @Column(name = "approved_by", columnDefinition = "uuid")
    var approvedBy: UUID? = null,

    @Column(name = "approved_by_name", length = 200)
    var approvedByName: String? = null
)

/**
 * Stan rozliczenia. Docelowo dojdzie wysyłka do księgowości (SENT) — dlatego status,
 * a nie flaga „zatwierdzona".
 */
enum class AttendanceSheetStatus {
    /** Wygenerowana, czeka, aż ktoś ją sprawdzi i zatwierdzi. */
    GENERATED,

    /** Sprawdzona i zatwierdzona — gotowa dla księgowości. */
    APPROVED
}

@Repository
interface AttendanceSheetRepository : JpaRepository<AttendanceSheetEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): AttendanceSheetEntity?

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<AttendanceSheetEntity>

    /**
     * Zatwierdzenie wyłącznie z [AttendanceSheetStatus.GENERATED], jednym zapytaniem: dwóch
     * administratorów klikających „Zatwierdź" naraz nie nadpisze sobie, kto i kiedy
     * zatwierdził — drugi dostaje 0 i konflikt.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AttendanceSheetEntity s
        SET s.status = 'APPROVED', s.approvedAt = :at, s.approvedBy = :by, s.approvedByName = :byName
        WHERE s.id = :id AND s.studioId = :studioId AND s.status = 'GENERATED'
        """
    )
    fun markApproved(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("at") at: Instant,
        @Param("by") by: UUID,
        @Param("byName") byName: String
    ): Int

    /** Jak [markApproved], razem z podpisem złożonym przy zatwierdzeniu. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AttendanceSheetEntity s
        SET s.status = 'APPROVED', s.approvedAt = :at, s.approvedBy = :by, s.approvedByName = :byName,
            s.signedFileS3Key = :signedKey, s.signerName = :byName, s.signedAt = :at, s.signedBy = :by
        WHERE s.id = :id AND s.studioId = :studioId AND s.status = 'GENERATED'
          AND s.signedFileS3Key IS NULL
        """
    )
    fun markApprovedWithSignature(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("at") at: Instant,
        @Param("by") by: UUID,
        @Param("byName") byName: String,
        @Param("signedKey") signedKey: String
    ): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AttendanceSheetEntity s WHERE s.id = :id AND s.studioId = :studioId")
    fun deleteByIdAndStudioId(@Param("id") id: UUID, @Param("studioId") studioId: UUID): Int
}
