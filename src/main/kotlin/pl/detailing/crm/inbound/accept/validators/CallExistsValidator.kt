package pl.detailing.crm.inbound.accept.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.inbound.accept.AcceptCallValidationContext
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class CallExistsValidator {
    fun validate(context: AcceptCallValidationContext) {
        if (context.callLog == null) {
            throw EntityNotFoundException("Dziennik połączeń o id ${context.callId} nie został znaleziony")
        }
    }
}
