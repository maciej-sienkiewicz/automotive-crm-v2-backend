package pl.detailing.crm.push.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.push.domain.PushDevice
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "push_devices",
    indexes = [
        Index(name = "idx_push_devices_endpoint", columnList = "endpoint_hash", unique = true),
        Index(name = "idx_push_devices_user", columnList = "studio_id, user_id")
    ]
)
class PushDeviceEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "device_name", nullable = false, length = 200)
    var deviceName: String,

    @Column(name = "user_agent", nullable = true, length = 400)
    var userAgent: String?,

    @Column(name = "endpoint", nullable = false, columnDefinition = "text")
    var endpoint: String,

    @Column(name = "endpoint_hash", nullable = false, length = 64)
    var endpointHash: String,

    @Column(name = "p256dh", nullable = false, columnDefinition = "text")
    var p256dh: String,

    @Column(name = "auth", nullable = false, columnDefinition = "text")
    var auth: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at", nullable = true, columnDefinition = "timestamp with time zone")
    var lastUsedAt: Instant? = null,

    @Column(name = "revoked_at", nullable = true, columnDefinition = "timestamp with time zone")
    var revokedAt: Instant? = null
) {
    fun toDomain(): PushDevice = PushDevice(
        id = id,
        studioId = StudioId(studioId),
        userId = UserId(userId),
        deviceName = deviceName,
        userAgent = userAgent,
        endpoint = endpoint,
        p256dh = p256dh,
        auth = auth,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        revokedAt = revokedAt
    )
}
