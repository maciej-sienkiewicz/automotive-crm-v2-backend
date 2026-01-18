package pl.detailing.crm.protocol

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Request DTOs
 */
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
    val serviceId: String?,
    val isMandatory: Boolean,
    val displayOrder: Int?
)

data class UpdateProtocolRuleRequest(
    val protocolTemplateId: String?,
    val triggerType: ProtocolTriggerType?,
    val stage: ProtocolStage?,
    val serviceId: String?,
    val isMandatory: Boolean?,
    val displayOrder: Int?
)

data class ReorderProtocolRulesRequest(
    val ruleIds: List<String>
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

/**
 * Response DTOs
 */
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
    val serviceId: String?,
    val serviceName: String?,
    val isMandatory: Boolean,
    val displayOrder: Int,
    val createdAt: String,
    val updatedAt: String
)

data class VisitProtocolResponse(
    val id: String,
    val visitId: String,
    val protocolTemplateId: String,
    val protocolTemplate: ProtocolTemplateResponse?,
    val stage: String,
    val isMandatory: Boolean,
    val isSigned: Boolean,
    val signedAt: String?,
    val signedBy: String?,
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
