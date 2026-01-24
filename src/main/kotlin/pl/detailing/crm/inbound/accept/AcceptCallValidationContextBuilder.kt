package pl.detailing.crm.inbound.accept

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.inbound.infrastructure.CallLogRepository

@Component
class AcceptCallValidationContextBuilder(
    private val callLogRepository: CallLogRepository
) {
    suspend fun build(command: AcceptCallCommand): AcceptCallValidationContext =
        withContext(Dispatchers.IO) {
            // Fetch call log
            val callLogEntity = callLogRepository.findByIdAndStudioId(
                command.callId.value,
                command.studioId.value
            )

            AcceptCallValidationContext(
                callId = command.callId,
                studioId = command.studioId,
                callLog = callLogEntity?.toDomain()
            )
        }
}
