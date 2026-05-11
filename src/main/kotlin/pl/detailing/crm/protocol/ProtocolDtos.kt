package pl.detailing.crm.protocol

import pl.detailing.crm.shared.*
import java.time.Instant

data class CreateProtocolTemplateRequest(
    val name: String,
    val description: String?
)

data class UpdateProtocolTemplateRequest(
    val name: String?,
    val description: String?,
    val isActive: Boolean?
)

data class CreateProtocolRuleRequest(
    val protocolTemplateId: String,
    val triggerType: ProtocolTriggerType,
    val stage: ProtocolStage,
    val serviceIds: List<String>?,
    val displayOrder: Int?
)

data class SignProtocolRequest(
    val signatureUrl: String,
    val signedBy: String,
    val notes: String?
)

data class CreateProtocolFieldMappingRequest(
    val pdfFieldName: String,
    val crmDataKey: CrmDataKey
)

// ─── Response DTOs ────────────────────────────────────────────────────────────

data class ProtocolTemplateResponse(
    val id: String,
    val name: String,
    val description: String?,
    val templateUrl: String?,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class ProtocolRuleResponse(
    val id: String,
    val protocolTemplateId: String,
    val protocolTemplate: ProtocolTemplateResponse?,
    val triggerType: String,
    val stage: String,
    val serviceIds: List<String>,
    val serviceNames: List<String>,
    val displayOrder: Int,
    val createdAt: String,
    val updatedAt: String
)

data class VisitProtocolResponse(
    val id: String,
    val visitId: String,
    /** Null for consent protocols. */
    val protocolTemplateId: String?,
    val protocolTemplate: ProtocolTemplateResponse?,
    /** Null for visit-document protocols. */
    val consentTemplateId: String?,
    val stage: String,
    val consentDefinitionId: String?,
    val isSigned: Boolean,
    val signedAt: String?,
    val signedBy: String?,
    val filledPdfUrl: String?,
    val signatureUrl: String?,
    val notes: String?,
    val createdAt: String,
    val updatedAt: String
)

data class ProtocolFieldMappingResponse(
    val id: String,
    val pdfFieldName: String,
    val crmDataKey: String,
    val crmDataKeyDescription: String
)

data class UploadUrlResponse(
    val uploadUrl: String,
    val s3Key: String
)

data class CrmDataKeyInfo(
    val key: String,
    val description: String
)
