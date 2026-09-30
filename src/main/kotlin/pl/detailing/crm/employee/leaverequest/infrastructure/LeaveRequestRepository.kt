package pl.detailing.crm.employee.leaverequest.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.employee.leaverequest.domain.ApprovalBasis
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Każde zapytanie filtruje po `studio_id` — jak w całym systemie. Przejścia statusu to
 * warunkowe UPDATE-y zwracające liczbę wierszy: 0 znaczy „ktoś był pierwszy" i kończy
 * się konfliktem, a nie nadpisaniem cudzej decyzji.
 *
 * Wymagają aktywnej transakcji — handlery otwierają ją przez TransactionTemplate.
 */
@Repository
interface LeaveRequestRepository : JpaRepository<LeaveRequestEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): LeaveRequestEntity?

    /** Wnioski jednego pracownika bez szkiców — szkic bez podpisu nie jest dokumentem. */
    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.employeeId = :employeeId AND r.status <> 'DRAFT'
        ORDER BY r.createdAt DESC
        """
    )
    fun findSubmittedByEmployee(
        @Param("studioId") studioId: UUID,
        @Param("employeeId") employeeId: UUID
    ): List<LeaveRequestEntity>

    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.status IN :statuses
        ORDER BY r.createdAt DESC
        """
    )
    fun findByStatuses(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: Collection<LeaveRequestStatus>,
        pageable: Pageable
    ): List<LeaveRequestEntity>

    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.status IN :statuses AND r.employeeId = :employeeId
        ORDER BY r.createdAt DESC
        """
    )
    fun findByStatusesAndEmployee(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: Collection<LeaveRequestStatus>,
        @Param("employeeId") employeeId: UUID,
        pageable: Pageable
    ): List<LeaveRequestEntity>

    @Query("SELECT COUNT(r) FROM LeaveRequestEntity r WHERE r.studioId = :studioId AND r.status = :status")
    fun countByStatus(@Param("studioId") studioId: UUID, @Param("status") status: LeaveRequestStatus): Long

    /**
     * Wnioski oczekujące, które [userId] może rozpatrzyć — bez jego własnych. Licznik przy
     * „Pracownicy" i podpowiedź na Tablicy nie mogą wołać o decyzję, której ta osoba
     * podjąć nie wolno.
     */
    @Query(
        """
        SELECT COUNT(r) FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.status = 'PENDING'
          AND (r.employeeUserId IS NULL OR r.employeeUserId <> :userId)
        """
    )
    fun countPendingDecidableBy(@Param("studioId") studioId: UUID, @Param("userId") userId: UUID): Long

    /** Najświeższy oczekujący wniosek, który [userId] może rozpatrzyć (klucz podpowiedzi na Tablicy). */
    @Query(
        """
        SELECT MAX(r.employeeSignedAt) FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.status = 'PENDING'
          AND (r.employeeUserId IS NULL OR r.employeeUserId <> :userId)
        """
    )
    fun latestPendingSubmissionFor(@Param("studioId") studioId: UUID, @Param("userId") userId: UUID): Instant?

    @Query(
        """
        SELECT COUNT(r) FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.employeeId = :employeeId AND r.status = 'PENDING'
        """
    )
    fun countPendingOfEmployee(@Param("studioId") studioId: UUID, @Param("employeeId") employeeId: UUID): Long

    /**
     * Wnioski pracownika, które zajmują termin (PENDING, APPROVED) i nachodzą na zakres —
     * poza wnioskiem [excludeId] (przy złożeniu szkic sprawdza się względem pozostałych).
     */
    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.employeeId = :employeeId
          AND r.status IN :statuses
          AND r.startDate <= :to AND r.endDate >= :from
          AND r.id <> :excludeId
        ORDER BY r.startDate
        """
    )
    fun findOverlappingOfEmployee(
        @Param("studioId") studioId: UUID,
        @Param("employeeId") employeeId: UUID,
        @Param("statuses") statuses: Collection<LeaveRequestStatus>,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("excludeId") excludeId: UUID
    ): List<LeaveRequestEntity>

    /** Wnioski „na żądanie" pracownika w zakresie (limit 4 dni w roku kalendarzowym). */
    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.employeeId = :employeeId
          AND r.onDemand = true AND r.status IN :statuses
          AND r.startDate <= :to AND r.endDate >= :from
          AND r.id <> :excludeId
        """
    )
    fun findOnDemandOfEmployee(
        @Param("studioId") studioId: UUID,
        @Param("employeeId") employeeId: UUID,
        @Param("statuses") statuses: Collection<LeaveRequestStatus>,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("excludeId") excludeId: UUID
    ): List<LeaveRequestEntity>

    /** Oczekujące wnioski całego studia nachodzące na zakres — obsada w szczegółach wniosku. */
    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.studioId = :studioId AND r.status = 'PENDING'
          AND r.startDate <= :to AND r.endDate >= :from
        """
    )
    fun findPendingOverlapping(
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<LeaveRequestEntity>

    // ── Przejścia statusu ────────────────────────────────────────────────────

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE LeaveRequestEntity r
        SET r.status = 'PENDING', r.employeeSignedAt = :signedAt, r.employeeSignatureMethod = :method,
            r.employeeSignedPdfS3Key = :pdfKey, r.employeeSignedSha256 = :sha256,
            r.employeeSignerIp = :ip, r.employeeSignerUserAgent = :userAgent,
            r.updatedAt = :signedAt, r.version = r.version + 1
        WHERE r.id = :id AND r.studioId = :studioId AND r.status = 'DRAFT'
        """
    )
    fun markSubmitted(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("signedAt") signedAt: Instant,
        @Param("method") method: LeaveSignatureMethod,
        @Param("pdfKey") pdfKey: String,
        @Param("sha256") sha256: String,
        @Param("ip") ip: String?,
        @Param("userAgent") userAgent: String?
    ): Int

    /**
     * Decyzja (APPROVED albo REJECTED) wyłącznie z PENDING, jednym zapytaniem. Przy dwóch
     * rozpatrujących naraz drugi dostaje 0 i konflikt — jego podpisany plik jest kasowany,
     * a zwycięska decyzja zostaje nietknięta.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE LeaveRequestEntity r
        SET r.status = :status, r.decidedBy = :decidedBy, r.decidedByName = :decidedByName,
            r.decidedByBasis = :basis, r.decidedByRoleName = :roleName, r.decidedAt = :decidedAt,
            r.decisionNote = :note, r.decisionSignatureMethod = :method, r.decisionInputSha256 = :inputSha256,
            r.decisionSignerIp = :ip, r.decisionSignerUserAgent = :userAgent,
            r.finalPdfS3Key = :finalKey, r.finalSha256 = :finalSha256, r.employeeLeaveId = :employeeLeaveId,
            r.updatedAt = :decidedAt, r.version = r.version + 1
        WHERE r.id = :id AND r.studioId = :studioId AND r.status = 'PENDING'
        """
    )
    fun markDecided(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("status") status: LeaveRequestStatus,
        @Param("decidedBy") decidedBy: UUID,
        @Param("decidedByName") decidedByName: String,
        @Param("basis") basis: ApprovalBasis,
        @Param("roleName") roleName: String?,
        @Param("decidedAt") decidedAt: Instant,
        @Param("note") note: String?,
        @Param("method") method: LeaveSignatureMethod,
        @Param("inputSha256") inputSha256: String,
        @Param("ip") ip: String?,
        @Param("userAgent") userAgent: String?,
        @Param("finalKey") finalKey: String,
        @Param("finalSha256") finalSha256: String,
        @Param("employeeLeaveId") employeeLeaveId: UUID?
    ): Int

    /** Wycofanie przez pracownika — tylko jego własny wniosek i tylko przed decyzją. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE LeaveRequestEntity r
        SET r.status = 'WITHDRAWN', r.cancelledAt = :at, r.cancelledBy = :by,
            r.updatedAt = :at, r.version = r.version + 1
        WHERE r.id = :id AND r.studioId = :studioId AND r.employeeId = :employeeId
          AND r.status IN ('DRAFT', 'PENDING')
        """
    )
    fun markWithdrawn(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("employeeId") employeeId: UUID,
        @Param("at") at: Instant,
        @Param("by") by: UUID
    ): Int

    /** Odwołanie zatwierdzonego urlopu — wyłącznie z APPROVED. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE LeaveRequestEntity r
        SET r.status = 'CANCELLED', r.cancelledAt = :at, r.cancelledBy = :by, r.cancelReason = :reason,
            r.employeeLeaveId = NULL, r.updatedAt = :at, r.version = r.version + 1
        WHERE r.id = :id AND r.studioId = :studioId AND r.status = 'APPROVED'
        """
    )
    fun markCancelled(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("at") at: Instant,
        @Param("by") by: UUID,
        @Param("reason") reason: String
    ): Int

    /** Oczekujące, których termin rozpoczęcia już minął — przed wygaszeniem (do dziennika zdarzeń). */
    @Query(
        """
        SELECT r FROM LeaveRequestEntity r
        WHERE r.status = 'PENDING' AND r.startDate < :today
        ORDER BY r.startDate
        """
    )
    fun findPendingStartedBefore(@Param("today") today: LocalDate, pageable: Pageable): List<LeaveRequestEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE LeaveRequestEntity r
        SET r.status = 'EXPIRED', r.updatedAt = :now, r.version = r.version + 1
        WHERE r.id = :id AND r.studioId = :studioId AND r.status = 'PENDING' AND r.startDate < :today
        """
    )
    fun markExpired(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("today") today: LocalDate,
        @Param("now") now: Instant
    ): Int

    @Query("SELECT r FROM LeaveRequestEntity r WHERE r.status = 'DRAFT' AND r.createdAt < :threshold ORDER BY r.createdAt")
    fun findDraftsCreatedBefore(@Param("threshold") threshold: Instant, pageable: Pageable): List<LeaveRequestEntity>

    /** Usuwa szkic tylko wtedy, gdy nadal jest szkicem — złożony w międzyczasie zostaje. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LeaveRequestEntity r WHERE r.id = :id AND r.studioId = :studioId AND r.status = 'DRAFT'")
    fun deleteDraft(@Param("id") id: UUID, @Param("studioId") studioId: UUID): Int

    @Query("SELECT r FROM LeaveRequestEntity r WHERE r.studioId = :studioId AND r.employeeId = :employeeId")
    fun findAllOfEmployee(@Param("studioId") studioId: UUID, @Param("employeeId") employeeId: UUID): List<LeaveRequestEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LeaveRequestEntity r WHERE r.studioId = :studioId AND r.employeeId = :employeeId")
    fun deleteAllOfEmployee(@Param("studioId") studioId: UUID, @Param("employeeId") employeeId: UUID): Int
}

/**
 * Ostatni nadany numer wniosku w roku studia (V171). Encja istnieje dla porządku schematu
 * (EntityTableHasMigrationTest, czyszczenie konta); odczyt i zapis idą natywnym upsertem.
 */
@Entity
@Table(name = "leave_request_counters")
@IdClass(LeaveRequestCounterId::class)
class LeaveRequestCounterEntity(
    @Id
    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Id
    @Column(name = "year", nullable = false)
    val year: Int,

    @Column(name = "last_value", nullable = false)
    var lastValue: Long
)

data class LeaveRequestCounterId(
    val studioId: UUID = UUID(0, 0),
    val year: Int = 0
) : Serializable

@Repository
interface LeaveRequestCounterRepository : JpaRepository<LeaveRequestCounterEntity, LeaveRequestCounterId> {

    /**
     * Następny numer w roku. ON CONFLICT bierze blokadę wiersza do końca transakcji, więc
     * równoległe wnioski czekają na siebie; wycofana transakcja nie zostawia luki.
     */
    @Query(
        value = """
            INSERT INTO leave_request_counters (studio_id, year, last_value)
            VALUES (:studioId, :year, 1)
            ON CONFLICT (studio_id, year)
            DO UPDATE SET last_value = leave_request_counters.last_value + 1
            RETURNING last_value
        """,
        nativeQuery = true
    )
    fun nextValue(@Param("studioId") studioId: UUID, @Param("year") year: Int): Long
}
