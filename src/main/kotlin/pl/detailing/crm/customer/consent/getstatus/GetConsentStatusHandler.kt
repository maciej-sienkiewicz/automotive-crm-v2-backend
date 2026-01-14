package pl.detailing.crm.customer.consent.getstatus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.ConsentStatus

/**
 * Handler for getting consent status for a customer.
 *
 * Business Logic:
 * - For each active consent definition, determine the customer's status:
 *   - VALID: Customer signed the current active version
 *   - OUTDATED: Customer signed an older version, and the new version doesn't require re-sign
 *   - REQUIRED: Customer never signed OR the new version requires re-sign
 */
@Service
class GetConsentStatusHandler(
    private val contextBuilder: ConsentStatusValidationContextBuilder,
    private val s3StorageService: S3ConsentStorageService
) {

    suspend fun handle(command: GetConsentStatusCommand): GetConsentStatusResult =
        withContext(Dispatchers.IO) {
            // Step 1: Build context with all necessary data
            val context = contextBuilder.build(command)

            // Step 2: Create a map of definition -> active template for quick lookup
            val templatesByDefinition = context.activeTemplates
                .associateBy { it.definitionId }

            // Step 3: Create a map of template -> customer consent for quick lookup
            val consentsByTemplate = context.customerConsents
                .groupBy { it.templateId }
                .mapValues { it.value.maxByOrNull { consent -> consent.signedAt } }

            // Step 4: Process each active definition
            val consentStatuses = context.activeDefinitions.mapNotNull { definition ->
                val activeTemplate = templatesByDefinition[definition.id] ?: return@mapNotNull null

                // Find all consents for this definition's templates
                val allTemplatesForDefinition = context.activeTemplates
                    .filter { it.definitionId == definition.id }
                    .map { it.id }

                val customerConsentsForDefinition = context.customerConsents
                    .filter { it.templateId in allTemplatesForDefinition }

                // Find the most recent consent for this definition (across all versions)
                val latestConsent = customerConsentsForDefinition
                    .maxByOrNull { it.signedAt }

                // Determine status
                val status = when {
                    // Customer signed the current active version
                    latestConsent?.templateId == activeTemplate.id -> ConsentStatus.VALID

                    // Customer signed an older version
                    latestConsent != null -> {
                        if (activeTemplate.requiresResign) {
                            ConsentStatus.REQUIRED
                        } else {
                            ConsentStatus.OUTDATED
                        }
                    }

                    // Customer never signed this consent
                    else -> ConsentStatus.REQUIRED
                }

                // Find the signed template version (if any)
                val signedTemplate = latestConsent?.let { consent ->
                    context.activeTemplates.find { it.id == consent.templateId }
                }

                // Generate download URL for the current active template
                val downloadUrl = s3StorageService.generateDownloadUrl(activeTemplate.s3Key)

                ConsentStatusItem(
                    definitionId = definition.id,
                    definitionSlug = definition.slug,
                    definitionName = definition.name,
                    status = status,
                    currentTemplateId = activeTemplate.id,
                    currentVersion = activeTemplate.version,
                    signedTemplateId = latestConsent?.templateId,
                    signedVersion = signedTemplate?.version,
                    signedAt = latestConsent?.signedAt,
                    downloadUrl = downloadUrl
                )
            }

            GetConsentStatusResult(consents = consentStatuses)
        }
}
