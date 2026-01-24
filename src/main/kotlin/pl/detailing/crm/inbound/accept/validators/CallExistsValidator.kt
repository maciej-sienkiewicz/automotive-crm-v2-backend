package pl.detailing.crm.inbound.accept.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.inbound.accept.AcceptCallValidationContext
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class CallExistsValidator {
    fun validate(context: AcceptCallValidationContext) {
        if (context.callLog == null) {
            throw EntityNotFoundException("CallLog with id ${context.callId} not found")
        }
    }
}
