package pl.detailing.crm.customer.consent.revoke

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.shared.CustomerConsentId
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@Service
class RevokeConsentHandler(
    private val customerConsentRepository: CustomerConsentRepository
) {

    @Transactional
    suspend fun handle(command: RevokeConsentCommand): Unit = withContext(Dispatchers.IO) {
        val entity = customerConsentRepository.findByIdAndStudioId(
            command.consentId.value,
            command.studioId.value
        ) ?: throw NotFoundException("Consent not found")

        if (entity.revokedAt != null) {
            throw ValidationException("Consent is already revoked")
        }

        entity.revokedAt = Instant.now()
        customerConsentRepository.save(entity)
    }
}

data class RevokeConsentCommand(
    val studioId: StudioId,
    val consentId: CustomerConsentId
)
