package pl.detailing.crm.customer.consent.getstatus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentStatus
import pl.detailing.crm.shared.ConsentTemplateId

@Service
class GetConsentStatusHandler(
    private val contextBuilder: ConsentStatusValidationContextBuilder,
    private val s3StorageService: S3ConsentStorageService
) {

    suspend fun handle(command: GetConsentStatusCommand): GetConsentStatusResult =
        withContext(Dispatchers.IO) {
            val context = contextBuilder.build(command)

            val activeTemplateByDefinition: Map<ConsentDefinitionId, ConsentTemplate> = context.allTemplates
                .filter { it.isActive }
                .associateBy { it.definitionId }

            val allTemplateIdsByDefinition: Map<ConsentDefinitionId, List<ConsentTemplateId>> = context.allTemplates
                .groupBy { it.definitionId }
                .mapValues { (_, templates) -> templates.map { it.id } }

            val validConsents = context.customerConsents.filter { it.revokedAt == null }

            fun latestConsentForDefinition(definitionId: ConsentDefinitionId) =
                (allTemplateIdsByDefinition[definitionId] ?: emptyList()).let { templateIds ->
                    validConsents.filter { it.templateId in templateIds }.maxByOrNull { it.signedAt }
                }

            fun templateById(id: ConsentTemplateId) = context.allTemplates.find { it.id == id }

            val activeStatuses = context.allDefinitions.filter { it.isActive }.mapNotNull { definition ->
                val activeTemplate = activeTemplateByDefinition[definition.id] ?: return@mapNotNull null
                val latestConsent = latestConsentForDefinition(definition.id)

                val status = when {
                    latestConsent?.templateId == activeTemplate.id -> ConsentStatus.VALID
                    latestConsent != null ->
                        if (activeTemplate.requiresResign) ConsentStatus.REQUIRED else ConsentStatus.OUTDATED
                    else -> ConsentStatus.REQUIRED
                }

                val signedTemplate = latestConsent?.let { templateById(it.templateId) }

                ConsentStatusItem(
                    definitionId = definition.id,
                    definitionName = definition.name,
                    isDefinitionActive = true,
                    stage = definition.stage,
                    marketingChannels = definition.marketingChannels,
                    displayOrder = definition.displayOrder,
                    status = status,
                    currentTemplateId = activeTemplate.id,
                    currentVersion = activeTemplate.version,
                    signedTemplateId = latestConsent?.templateId,
                    signedVersion = signedTemplate?.version,
                    signedAt = latestConsent?.signedAt,
                    downloadUrl = s3StorageService.generateDownloadUrl(activeTemplate.s3Key),
                    consentId = latestConsent?.id
                )
            }

            val inactiveStatuses = context.allDefinitions.filter { !it.isActive }.mapNotNull { definition ->
                val latestConsent = latestConsentForDefinition(definition.id) ?: return@mapNotNull null
                val signedTemplate = templateById(latestConsent.templateId)

                ConsentStatusItem(
                    definitionId = definition.id,
                    definitionName = definition.name,
                    isDefinitionActive = false,
                    stage = null,
                    marketingChannels = definition.marketingChannels,
                    displayOrder = 0,
                    status = ConsentStatus.VALID,
                    currentTemplateId = null,
                    currentVersion = null,
                    signedTemplateId = latestConsent.templateId,
                    signedVersion = signedTemplate?.version,
                    signedAt = latestConsent.signedAt,
                    downloadUrl = null,
                    consentId = latestConsent.id
                )
            }

            GetConsentStatusResult(consents = activeStatuses + inactiveStatuses)
        }
}
