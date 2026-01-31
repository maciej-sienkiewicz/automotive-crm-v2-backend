package pl.detailing.crm.customer.create

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.create.validators.ContactInfoValidator
import pl.detailing.crm.customer.create.validators.ContactUniquenessValidator
import pl.detailing.crm.customer.create.validators.NipFormatValidator

@Component
class CreateCustomerValidatorComposite(
    private val contextBuilder: CreateCustomerValidationContextBuilder,
    private val contactInfoValidator: ContactInfoValidator,
    private val contactUniquenessValidator: ContactUniquenessValidator,
    private val nipFormatValidator: NipFormatValidator
) {
    suspend fun validate(command: CreateCustomerCommand) {
        val context = contextBuilder.build(command)

        contactInfoValidator.validate(context)
        contactUniquenessValidator.validate(context)
        nipFormatValidator.validate(context)
    }
}
