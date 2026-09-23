package pl.detailing.crm.rolepreview.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Rejestr piaskownicy podglądu roli (migracja V155).
 *
 * Piaskownica to jednorazowe studio ([sandboxStudioId], rodzaj ROLE_PREVIEW) z danymi
 * przykładowymi, w którym administrator prawdziwego studia ([sourceStudioId]) ogląda CRM
 * oczami pracownika ([employeeUserId]) z podglądaną rolą ([roleId]).
 *
 * Wejście do piaskownicy daje wyłącznie jednorazowy kod, którego w bazie jest tylko skrót
 * ([entryCodeHash]); [enteredAt] mówi, że kod już wymieniono na sesję ([sessionId]).
 */
@Entity
@Table(
    name = "role_preview_sandboxes",
    indexes = [
        Index(name = "uq_role_preview_sandboxes_studio", columnList = "sandbox_studio_id", unique = true),
        Index(name = "uq_role_preview_sandboxes_code", columnList = "entry_code_hash", unique = true),
        Index(name = "idx_role_preview_sandboxes_source", columnList = "source_studio_id"),
        Index(name = "idx_role_preview_sandboxes_expires", columnList = "expires_at")
    ]
)
class RolePreviewSandboxEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "sandbox_studio_id", nullable = false, columnDefinition = "uuid")
    val sandboxStudioId: UUID,

    @Column(name = "source_studio_id", nullable = false, columnDefinition = "uuid")
    val sourceStudioId: UUID,

    @Column(name = "created_by_user_id", nullable = false, columnDefinition = "uuid")
    val createdByUserId: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "owner_user_id", nullable = false, columnDefinition = "uuid")
    val ownerUserId: UUID,

    @Column(name = "employee_user_id", nullable = false, columnDefinition = "uuid")
    val employeeUserId: UUID,

    @Column(name = "role_id", nullable = false, columnDefinition = "uuid")
    val roleId: UUID,

    @Column(name = "role_name", nullable = false, length = 100)
    val roleName: String,

    /** Kody uprawnień roli w chwili otwarcia podglądu, po przecinku - punkt odniesienia dla „Zmian". */
    @Column(name = "initial_permissions", nullable = false, columnDefinition = "text")
    val initialPermissions: String,

    @Column(name = "initial_track_work_time", nullable = false)
    val initialTrackWorkTime: Boolean,

    @Column(name = "entry_code_hash", nullable = false, length = 64)
    val entryCodeHash: String,

    @Column(name = "entry_code_expires_at", nullable = false, columnDefinition = "timestamp with time zone")
    val entryCodeExpiresAt: Instant,

    @Column(name = "entered_at", columnDefinition = "timestamp with time zone")
    var enteredAt: Instant? = null,

    @Column(name = "session_id", length = 100)
    var sessionId: String? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "last_activity_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastActivityAt: Instant,

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamp with time zone")
    val expiresAt: Instant
) {
    fun initialPermissionCodes(): List<String> =
        initialPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
