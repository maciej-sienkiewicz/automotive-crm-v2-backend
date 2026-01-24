package pl.detailing.crm.inbound.accept.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.inbound.accept.AcceptCallValidationContext
import pl.detailing.crm.shared.CallLogStatus
import pl.detailing.crm.shared.ValidationException

@Component
class CallAlreadyProcessedValidator {
    fun validate(context: AcceptCallValidationContext) {
        val callLog = context.callLog ?: return

        if (callLog.status != CallLogStatus.PENDING) {
            throw ValidationException(
                "CallLog with id ${context.callId} has already been processed (status: ${callLog.status})"
            )
        }
    }
}
