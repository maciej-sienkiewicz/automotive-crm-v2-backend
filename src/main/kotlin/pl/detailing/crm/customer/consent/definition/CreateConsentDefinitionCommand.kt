package pl.detailing.crm.customer.consent.definition

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * Command to create a new consent definition.
 *
 * A consent definition represents a type of consent document (e.g., "RODO", "Marketing").
 * After creating the definition, admin can upload PDF templates for it.
 */
data class CreateConsentDefinitionCommand(
    val studioId: StudioId,
    val slug: String,           // Unique identifier (e.g., "kontakt-po-18")
    val name: String,           // Display name (e.g., "Zgoda na kontakt po godzinie 18")
    val description: String?,   // Optional description
    val createdBy: UserId
)
