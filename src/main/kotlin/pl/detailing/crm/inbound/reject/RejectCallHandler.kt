package pl.detailing.crm.inbound.reject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.CallLogStatus
import pl.detailing.crm.shared.EntityNotFoundException
import java.time.Instant

@Service
class RejectCallHandler(
    private val callLogRepository: CallLogRepository
) {
    @Transactional
    suspend fun handle(command: RejectCallCommand): RejectCallResult =
        withContext(Dispatchers.IO) {
            // Find call log
            val callLogEntity = callLogRepository.findByIdAndStudioId(
                command.callId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("CallLog with id ${command.callId} not found")

            // Transition to REJECTED
            callLogEntity.status = CallLogStatus.REJECTED
            callLogEntity.updatedAt = Instant.now()

            // Save
            callLogRepository.save(callLogEntity)

            RejectCallResult(
                callId = command.callId,
                status = CallLogStatus.REJECTED
            )
        }
}

data class RejectCallResult(
    val callId: CallId,
    val status: CallLogStatus
)
