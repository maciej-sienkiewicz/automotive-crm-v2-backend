package pl.detailing.crm.inbound.accept

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.CallLogStatus
import java.time.Instant

@Service
class AcceptCallHandler(
    private val validatorComposite: AcceptCallValidatorComposite,
    private val callLogRepository: CallLogRepository
) {
    @Transactional
    suspend fun handle(command: AcceptCallCommand): AcceptCallResult =
        withContext(Dispatchers.IO) {
            // Validate
            val context = validatorComposite.validate(command)

            // Get the call log entity
            val callLogEntity = callLogRepository.findByIdAndStudioId(
                command.callId.value,
                command.studioId.value
            )!!

            // Transition to ACCEPTED
            callLogEntity.status = CallLogStatus.ACCEPTED
            callLogEntity.updatedAt = Instant.now()

            // Save
            callLogRepository.save(callLogEntity)

            // TODO: Optionally trigger CreateVisitHandler or send to a queue for manual processing

            AcceptCallResult(
                callId = command.callId,
                status = CallLogStatus.ACCEPTED
            )
        }
}

data class AcceptCallResult(
    val callId: CallId,
    val status: CallLogStatus
)
