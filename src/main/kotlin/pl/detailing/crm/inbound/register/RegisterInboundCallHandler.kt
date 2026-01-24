package pl.detailing.crm.inbound.register

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.domain.CallLog
import pl.detailing.crm.inbound.infrastructure.CallLogEntity
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class RegisterInboundCallHandler(
    private val callLogRepository: CallLogRepository
) {
    @Transactional
    suspend fun handle(command: RegisterInboundCallCommand): RegisterInboundCallResult =
        withContext(Dispatchers.IO) {
            // Validate phone format
            val normalizedPhone = normalizePolishPhone(command.phoneNumber.trim())
            if (!isValidPolishPhone(normalizedPhone)) {
                throw ValidationException("Invalid Polish phone number format: ${command.phoneNumber}")
            }

            // Create call log
            val callLog = CallLog(
                id = CallId.random(),
                studioId = command.studioId,
                phoneNumber = normalizedPhone,
                callerName = command.callerName?.trim(),
                note = command.note?.trim(),
                status = CallLogStatus.PENDING,
                receivedAt = command.receivedAt,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Save entity
            val entity = CallLogEntity.fromDomain(callLog)
            callLogRepository.save(entity)

            // TODO: Publish NewCallReceivedEvent for WebSocket integration

            RegisterInboundCallResult(
                callId = callLog.id,
                phoneNumber = callLog.phoneNumber,
                callerName = callLog.callerName,
                status = callLog.status,
                receivedAt = callLog.receivedAt
            )
        }
}

data class RegisterInboundCallResult(
    val callId: CallId,
    val phoneNumber: String,
    val callerName: String?,
    val status: CallLogStatus,
    val receivedAt: Instant
)
