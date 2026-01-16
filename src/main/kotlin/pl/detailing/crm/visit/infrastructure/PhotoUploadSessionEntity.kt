package pl.detailing.crm.visit.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID


@Entity
@Table(
    name = "photo_upload_sessions",
    indexes = [
        Index(name = "idx_photo_sessions_studio_id", columnList = "studio_id"),
        Index(name = "idx_photo_sessions_appointment_id", columnList = "appointment_id"),
        Index(name = "idx_photo_sessions_expires_at", columnList = "expires_at")
    ]
)
class PhotoUploadSessionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "appointment_id", nullable = false, columnDefinition = "uuid")
    val appointmentId: UUID,

    @Column(name = "token", nullable = false, length = 500, unique = true)
    val token: String,

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamp with time zone")
    val expiresAt: Instant,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun isExpired(): Boolean {
        return Instant.now().isAfter(expiresAt)
    }
}

@Repository
interface PhotoUploadSessionRepository : org.springframework.data.jpa.repository.JpaRepository<PhotoUploadSessionEntity, UUID> {

    @Query("SELECT s FROM PhotoUploadSessionEntity s WHERE s.id = :sessionId AND s.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("sessionId") sessionId: UUID,
        @Param("studioId") studioId: UUID
    ): PhotoUploadSessionEntity?

    @Query("SELECT s FROM PhotoUploadSessionEntity s WHERE s.token = :token")
    fun findByToken(@Param("token") token: String): PhotoUploadSessionEntity?

    @Query("SELECT s FROM PhotoUploadSessionEntity s WHERE s.appointmentId = :appointmentId AND s.studioId = :studioId")
    fun findByAppointmentIdAndStudioId(
        @Param("appointmentId") appointmentId: UUID,
        @Param("studioId") studioId: UUID
    ): PhotoUploadSessionEntity?
}
