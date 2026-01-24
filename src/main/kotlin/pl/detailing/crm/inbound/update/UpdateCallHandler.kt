package pl.detailing.crm.inbound.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.shared.EntityNotFoundException
import java.time.Instant

@Service
class UpdateCallHandler(
    private val callLogRepository: CallLogRepository
) {
    @Transactional
    suspend fun handle(command: UpdateCallCommand): UpdateCallResult =
        withContext(Dispatchers.IO) {
            // Find call log
            val entity = callLogRepository.findByIdAndStudioId(
                command.callId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("CallLog with id ${command.callId} not found")

            // Update fields
            entity.callerName = command.callerName?.trim()
            entity.note = command.note?.trim()
            entity.updatedAt = Instant.now()

            // Save
            callLogRepository.save(entity)

            UpdateCallResult(
                callId = command.callId,
                callerName = entity.callerName,
                note = entity.note
            )
        }
}

data class UpdateCallResult(
    val callId: pl.detailing.crm.shared.CallId,
    val callerName: String?,
    val note: String?
)
