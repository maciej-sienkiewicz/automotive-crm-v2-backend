package pl.detailing.crm.customer.consent.sign

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentEntity
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.CustomerConsentId
import java.time.Instant

/**
 * Handler for signing a consent.
 *
 * This creates an immutable, append-only record of the customer's acceptance
 * of a specific consent template version.
 *
 * If requestAttachmentUpload is true, a pre-generated S3 key is stored on the consent
 * and a presigned upload URL is returned so the admin can upload a scanned document.
 *
 * Note: Never update or delete CustomerConsent records. Always create new ones.
 */
@Service
class SignConsentHandler(
    private val validatorComposite: SignConsentValidatorComposite,
    private val customerConsentRepository: CustomerConsentRepository,
    private val s3ConsentStorageService: S3ConsentStorageService
) {

    @Transactional
    suspend fun handle(command: SignConsentCommand): SignConsentResult =
        withContext(Dispatchers.IO) {
            // Step 1: Validate
            validatorComposite.validate(command)

            val consentId = CustomerConsentId.random()

            // Step 2: Pre-generate attachment S3 key if requested
            val attachmentS3Key: String?
            val attachmentUploadUrl: String?
            if (command.requestAttachmentUpload) {
                attachmentS3Key = s3ConsentStorageService.buildAttachmentS3Key(
                    command.studioId.value,
                    consentId.value
                )
                attachmentUploadUrl = s3ConsentStorageService.generateAttachmentUploadUrl(
                    command.studioId.value,
                    consentId.value
                )
            } else {
                attachmentS3Key = null
                attachmentUploadUrl = null
            }

            // Step 3: Create new consent record
            val consent = CustomerConsent(
                id = consentId,
                studioId = command.studioId,
                customerId = command.customerId,
                templateId = command.templateId,
                signedAt = Instant.now(),
                witnessedBy = command.witnessedBy,
                attachmentS3Key = attachmentS3Key
            )

            // Step 4: Persist (append-only, never update)
            val entity = CustomerConsentEntity.fromDomain(consent)
            customerConsentRepository.save(entity)

            // Step 5: Return result
            SignConsentResult(
                consentId = consent.id,
                signedAt = consent.signedAt,
                attachmentUploadUrl = attachmentUploadUrl,
                attachmentS3Key = attachmentS3Key
            )
        }
}
