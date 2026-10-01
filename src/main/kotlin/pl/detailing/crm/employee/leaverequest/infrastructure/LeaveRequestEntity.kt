package pl.detailing.crm.employee.leaverequest.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leaverequest.domain.ApprovalBasis
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Wniosek urlopowy — dokument z dwoma podpisami (V171).
 *
 * Każda wersja PDF to osobny obiekt w S3 z własnym skrótem: H1 bez podpisów
 * ([documentS3Key]), H2 po podpisie pracownika ([employeeSignedPdfS3Key]) i H3 z decyzją
 * i kartą podpisów ([finalPdfS3Key]). Żadna nie nadpisuje poprzedniej — podpis, który
 * powołuje się na skrót dokumentu, musi dać się zawsze zestawić z tym dokumentem.
 *
 * Przejścia statusu idą warunkowymi UPDATE-ami w [LeaveRequestRepository], nie przez
 * `save()`: przy dwóch rozpatrujących naraz wygrywa jeden, a drugi dostaje 0 wierszy
 * i konflikt, zamiast nadpisać cudzą decyzję.
 */
@Entity
@Table(
    name = "leave_requests",
    indexes = [
        Index(name = "idx_leave_requests_queue", columnList = "studio_id, status, created_at"),
        Index(name = "idx_leave_requests_employee", columnList = "studio_id, employee_id, start_date")
    ]
)
class LeaveRequestEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "number", nullable = false, length = 32)
    val number: String,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "employee_user_id", columnDefinition = "uuid")
    val employeeUserId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    val leaveType: LeaveType,

    @Column(name = "on_demand", nullable = false)
    val onDemand: Boolean,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    val endDate: LocalDate,

    @Column(name = "working_days", nullable = false)
    val workingDays: Int,

    @Column(name = "reason", length = 1000)
    val reason: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    var status: LeaveRequestStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    val origin: LeaveRequestOrigin = LeaveRequestOrigin.SELF_SERVICE,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", length = 200)
    val createdByName: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "document_s3_key", nullable = false, length = 500)
    val documentS3Key: String,

    @Column(name = "document_sha256", nullable = false, length = 64)
    val documentSha256: String,

    @Column(name = "employee_signed_at", columnDefinition = "timestamp with time zone")
    var employeeSignedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "employee_signature_method", length = 20)
    var employeeSignatureMethod: LeaveSignatureMethod? = null,

    @Column(name = "employee_signed_pdf_s3_key", length = 500)
    var employeeSignedPdfS3Key: String? = null,

    @Column(name = "employee_signed_sha256", length = 64)
    var employeeSignedSha256: String? = null,

    @Column(name = "employee_signer_ip", length = 45)
    var employeeSignerIp: String? = null,

    @Column(name = "employee_signer_user_agent", length = 500)
    var employeeSignerUserAgent: String? = null,

    @Column(name = "decided_by", columnDefinition = "uuid")
    var decidedBy: UUID? = null,

    @Column(name = "decided_by_name", length = 200)
    var decidedByName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by_basis", length = 20)
    var decidedByBasis: ApprovalBasis? = null,

    @Column(name = "decided_by_role_name", length = 200)
    var decidedByRoleName: String? = null,

    @Column(name = "decided_at", columnDefinition = "timestamp with time zone")
    var decidedAt: Instant? = null,

    @Column(name = "decision_note", length = 1000)
    var decisionNote: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_signature_method", length = 20)
    var decisionSignatureMethod: LeaveSignatureMethod? = null,

    @Column(name = "decision_input_sha256", length = 64)
    var decisionInputSha256: String? = null,

    @Column(name = "decision_signer_ip", length = 45)
    var decisionSignerIp: String? = null,

    @Column(name = "decision_signer_user_agent", length = 500)
    var decisionSignerUserAgent: String? = null,

    @Column(name = "final_pdf_s3_key", length = 500)
    var finalPdfS3Key: String? = null,

    @Column(name = "final_sha256", length = 64)
    var finalSha256: String? = null,

    @Column(name = "employee_leave_id", columnDefinition = "uuid")
    var employeeLeaveId: UUID? = null,

    @Column(name = "cancelled_at", columnDefinition = "timestamp with time zone")
    var cancelledAt: Instant? = null,

    @Column(name = "cancelled_by", columnDefinition = "uuid")
    var cancelledBy: UUID? = null,

    @Column(name = "cancel_reason", length = 1000)
    var cancelReason: String? = null,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now(),

    // Null przed pierwszym zapisem: Spring Data rozpoznaje wtedy nowy wiersz po wersji
    // (persist zamiast merge przy przypisanym z góry id), a Hibernate nadaje 0 przy INSERT.
    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
) {
    /** Dokument, który pokazujemy jako „aktualny PDF": final, a przed decyzją wersja z podpisem pracownika. */
    fun currentFileKey(): String? = finalPdfS3Key ?: employeeSignedPdfS3Key
}
