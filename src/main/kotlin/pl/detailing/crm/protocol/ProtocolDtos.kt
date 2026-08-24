package pl.detailing.crm.protocol

import pl.detailing.crm.shared.*
import java.time.Instant

data class CreateProtocolTemplateRequest(
    val name: String,
    val description: String?,
    /** Template file format: "PDF" (default) or "HTML". */
    val fileFormat: String? = null
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

/** Odpowiedź pracownika o zgodności stanu wizualnego przy wydaniu pojazdu. */
data class VisualConditionRequest(
    val conditionMatch: Boolean,
    val remarks: String? = null
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
    val fileFormat: String,
    val isDefault: Boolean,
    val verificationStatus: String,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

// ─── Template verification ────────────────────────────────────────────────────

data class ProtocolTemplateVerificationResponse(
    val templateId: String,
    val fileFormat: String,
    val verificationStatus: String,
    /** All field names required by the visit pipeline. */
    val requiredFields: List<String>,
    /** Required fields actually present in the uploaded file. */
    val foundFields: List<String>,
    /** Required fields absent from the uploaded file — empty when VERIFIED. */
    val missingFields: List<String>,
    /** Optional fields (e.g. company_signature) that were detected. */
    val optionalFieldsFound: List<String>,
    /** Non-field problems: unparseable file, missing AcroForm, bad signature box, etc. */
    val problems: List<String>
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
