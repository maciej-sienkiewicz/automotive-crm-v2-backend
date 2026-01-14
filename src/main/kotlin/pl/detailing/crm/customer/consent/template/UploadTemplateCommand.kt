package pl.detailing.crm.customer.consent.template

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * Command to upload a new consent template version.
 *
 * This creates a new template version and optionally sets it as active.
 * If setAsActive is true, all other templates for this definition are deactivated.
 *
 * @param studioId The studio/tenant ID
 * @param definitionId The consent definition this template belongs to
 * @param requiresResign If true, customers must re-sign even if they signed older versions
 * @param setAsActive If true, this template becomes the active version (default: true)
 * @param createdBy The admin/user creating this template
 */
data class UploadTemplateCommand(
    val studioId: StudioId,
    val definitionId: ConsentDefinitionId,
    val requiresResign: Boolean,
    val setAsActive: Boolean = true,
    val createdBy: UserId
)
