package pl.detailing.crm.inbound.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.inbound.domain.CallLog
import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.CallLogStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "call_logs",
    indexes = [
        Index(name = "idx_call_logs_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_call_logs_studio_received", columnList = "studio_id, received_at"),
        Index(name = "idx_call_logs_phone", columnList = "phone_number")
    ]
)
class CallLogEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "phone_number", nullable = false, length = 20)
    var phoneNumber: String,

    @Column(name = "caller_name", nullable = true, length = 200)
    var callerName: String?,

    @Column(name = "note", nullable = true, columnDefinition = "text")
    var note: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CallLogStatus,

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamp with time zone")
    val receivedAt: Instant,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): CallLog = CallLog(
        id = CallId(id),
        studioId = StudioId(studioId),
        phoneNumber = phoneNumber,
        callerName = callerName,
        note = note,
        status = status,
        receivedAt = receivedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(callLog: CallLog): CallLogEntity = CallLogEntity(
            id = callLog.id.value,
            studioId = callLog.studioId.value,
            phoneNumber = callLog.phoneNumber,
            callerName = callLog.callerName,
            note = callLog.note,
            status = callLog.status,
            receivedAt = callLog.receivedAt,
            createdAt = callLog.createdAt,
            updatedAt = callLog.updatedAt
        )
    }
}
