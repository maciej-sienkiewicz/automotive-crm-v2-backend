package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.CrmDataKey
import pl.detailing.crm.shared.ProtocolFieldMappingId
import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * Protocol field mapping connects a PDF form field to a CRM data source.
 *
 * Example:
 * - pdfFieldName: "customer_name" (technical field ID in the PDF)
 * - crmDataKey: CUSTOMER_FULL_NAME (standardized data key)
 */
data class ProtocolFieldMapping(
    val id: ProtocolFieldMappingId,
    val studioId: StudioId,
    val templateId: ProtocolTemplateId,
    val pdfFieldName: String,           // Technical field name in the PDF AcroForm
    val crmDataKey: CrmDataKey,         // Standardized CRM data key
    val createdAt: Instant
) {
    init {
        require(pdfFieldName.isNotBlank()) { "PDF field name cannot be blank" }
    }
}
