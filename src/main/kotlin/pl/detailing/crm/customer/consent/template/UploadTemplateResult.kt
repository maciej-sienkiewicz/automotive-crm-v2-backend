package pl.detailing.crm.customer.consent.template

import pl.detailing.crm.shared.ConsentTemplateId

/**
 * Result of uploading a consent template.
 *
 * @param templateId The ID of the newly created template
 * @param version The version number of the template
 * @param uploadUrl Presigned URL for the frontend to upload the PDF file
 * @param s3Key The S3 object key where the file should be stored
 */
data class UploadTemplateResult(
    val templateId: ConsentTemplateId,
    val version: Int,
    val uploadUrl: String,
    val s3Key: String
)
