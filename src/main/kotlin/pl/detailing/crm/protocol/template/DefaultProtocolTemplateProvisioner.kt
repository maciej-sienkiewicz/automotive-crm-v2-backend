package pl.detailing.crm.protocol.template

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import pl.detailing.crm.protocol.domain.ProtocolTemplateVerificationStatus
import pl.detailing.crm.protocol.infrastructure.ProtocolFieldMappingEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolFieldMappingRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateDefaultRevisionEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateDefaultRevisionRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

/**
 * Guards the multi-tenant invariant: **every studio always has an active,
 * check-in-assigned vehicle acceptance protocol template.**
 *
 * The default template is the bundled AcroForm PDF
 * (resources/templates/protokol_przyjecia_pojazdu_default.pdf) — an unbranded
 * acceptance protocol with all standard field names, seeded with the default
 * field mappings and a GLOBAL_ALWAYS CHECK_IN rule. It carries no logo by design:
 * it is served to every studio, so it must never show one studio's branding.
 *
 * [ensureDefaultCheckInTemplate] is invoked:
 * - after a studio is created (signup, trial, demo accounts),
 * - after a protocol template is deleted or deactivated,
 * - after a protocol rule is deleted,
 * - at application startup for every existing studio (backfill).
 *
 * When the studio still has its own working check-in template the call is a no-op;
 * when the last one disappears, the default is (re)provisioned — so a studio can
 * replace the default with its own template, but can never end up with none.
 */
@Service
class DefaultProtocolTemplateProvisioner(
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val protocolRuleRepository: ProtocolRuleRepository,
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository,
    private val s3StorageService: S3ProtocolStorageService,
    private val defaultRevisionRepository: ProtocolTemplateDefaultRevisionRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_TEMPLATE_NAME = "Protokół przyjęcia pojazdu"
        const val DEFAULT_TEMPLATE_DESCRIPTION =
            "Systemowy szablon protokołu przyjęcia pojazdu. Przywracany automatycznie, " +
                "gdy studio nie ma żadnego aktywnego szablonu przyjęcia."
        const val DEFAULT_TEMPLATE_RESOURCE = "/templates/protokol_przyjecia_pojazdu_default.pdf"

        /**
         * Content revision of [DEFAULT_TEMPLATE_RESOURCE].
         *
         * **Bump this whenever the bundled file changes.** Each studio holds its own
         * copy of the file in S3, seeded once; without a bump those copies keep the
         * old content forever, because [ensureDefaultCheckInTemplate] returns early
         * for a studio that already has a working check-in template.
         * DefaultProtocolTemplateRefreshRunner re-uploads every default template
         * recorded below this number on the next application start.
         *
         * 1 — logo removed from the header. The bundled file was exported from a
         *     single customer's branded design and carried that customer's logo,
         *     which then appeared on every other studio's acceptance protocol.
         */
        const val DEFAULT_TEMPLATE_REVISION = 1

        /** Synthetic author for system-provisioned rows. */
        val SYSTEM_USER_ID: UUID = UUID(0L, 0L)
    }

    /**
     * Ensure the studio has at least one active template assigned to the CHECK_IN
     * stage. Returns true when provisioning took place, false when the invariant
     * already held.
     *
     * REQUIRES_NEW so the guard commits independently of the (possibly rolling
     * back) surrounding transaction of a delete/update operation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureDefaultCheckInTemplate(studioId: StudioId): Boolean {
        if (hasActiveCheckInTemplate(studioId)) {
            return false
        }

        logger.info("Default template provisioning: studio={} has no active CHECK_IN template — seeding", studioId)

        val template = findOrCreateDefaultTemplate(studioId)
        ensureCheckInRule(studioId, template.id)

        logger.info(
            "Default template provisioning: studio={} — default check-in template ready (templateId={})",
            studioId, template.id
        )
        return true
    }

    private fun hasActiveCheckInTemplate(studioId: StudioId): Boolean {
        val checkInRules = protocolRuleRepository.findAllByStudioIdAndStage(
            studioId.value, ProtocolStage.CHECK_IN
        )
        return checkInRules.any { rule ->
            protocolTemplateRepository.findByIdAndStudioId(rule.templateId, studioId.value)
                ?.isActive == true
        }
    }

    private fun findOrCreateDefaultTemplate(studioId: StudioId): ProtocolTemplateEntity {
        val existing = protocolTemplateRepository.findDefaultByStudioId(studioId.value)
        if (existing != null) {
            if (!existing.isActive) {
                existing.isActive = true
                existing.updatedBy = SYSTEM_USER_ID
                existing.updatedAt = Instant.now()
                protocolTemplateRepository.save(existing)
                logger.info("Default template provisioning: reactivated default template {} for studio {}", existing.id, studioId)
            }
            // Heal the S3 object too — it may have been removed together with the template.
            uploadDefaultTemplateFile(existing.s3Key)
            recordBundledRevision(existing.id)
            ensureDefaultFieldMappings(studioId, existing.id)
            return existing
        }

        val templateId = ProtocolTemplateId.random().value
        val s3Key = s3StorageService.buildTemplateS3Key(studioId.value, templateId, ProtocolTemplateFormat.PDF)
        uploadDefaultTemplateFile(s3Key)

        val now = Instant.now()
        val entity = ProtocolTemplateEntity(
            id = templateId,
            studioId = studioId.value,
            name = DEFAULT_TEMPLATE_NAME,
            description = DEFAULT_TEMPLATE_DESCRIPTION,
            s3Key = s3Key,
            fileFormat = ProtocolTemplateFormat.PDF,
            isDefault = true,
            // The bundled file is known-good: it ships with every required field.
            verificationStatus = ProtocolTemplateVerificationStatus.VERIFIED,
            isActive = true,
            createdBy = SYSTEM_USER_ID,
            updatedBy = SYSTEM_USER_ID,
            createdAt = now,
            updatedAt = now
        )
        protocolTemplateRepository.save(entity)
        recordBundledRevision(templateId)
        ensureDefaultFieldMappings(studioId, templateId)
        return entity
    }

    /**
     * Reads the bundled default template from the classpath. Shared with the startup
     * refresh so both paths upload byte-identical content.
     */
    fun loadBundledTemplateBytes(): ByteArray {
        val resource = javaClass.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)
            ?: throw IllegalStateException(
                "Bundled default protocol template missing from classpath: $DEFAULT_TEMPLATE_RESOURCE"
            )
        return resource.use { it.readBytes() }
    }

    private fun uploadDefaultTemplateFile(s3Key: String) {
        s3StorageService.uploadBytes(s3Key, loadBundledTemplateBytes(), ProtocolTemplateFormat.PDF.contentType)
    }

    /** Marks the template's S3 object as holding [DEFAULT_TEMPLATE_REVISION] of the bundled file. */
    private fun recordBundledRevision(templateId: UUID) {
        defaultRevisionRepository.save(
            ProtocolTemplateDefaultRevisionEntity(
                templateId = templateId,
                revision = DEFAULT_TEMPLATE_REVISION,
                appliedAt = Instant.now()
            )
        )
    }

    private fun ensureDefaultFieldMappings(studioId: StudioId, templateId: UUID) {
        val existing = protocolFieldMappingRepository.findAllByTemplateIdAndStudioId(templateId, studioId.value)
        if (existing.isNotEmpty()) return

        val now = Instant.now()
        val mappings = DefaultProtocolFieldMappings.getDefaultMappings().map { (pdfFieldName, crmDataKey) ->
            ProtocolFieldMappingEntity.fromDomain(
                pl.detailing.crm.protocol.domain.ProtocolFieldMapping(
                    id = ProtocolFieldMappingId.random(),
                    studioId = studioId,
                    templateId = ProtocolTemplateId(templateId),
                    pdfFieldName = pdfFieldName,
                    crmDataKey = crmDataKey,
                    createdAt = now
                )
            )
        }
        protocolFieldMappingRepository.saveAll(mappings)
    }

    private fun ensureCheckInRule(studioId: StudioId, templateId: UUID) {
        val hasRule = protocolRuleRepository.findAllByStudioIdAndStage(studioId.value, ProtocolStage.CHECK_IN)
            .any { it.templateId == templateId }
        if (hasRule) return

        val now = Instant.now()
        protocolRuleRepository.save(
            ProtocolRuleEntity(
                id = ProtocolRuleId.random().value,
                studioId = studioId.value,
                templateId = templateId,
                triggerType = ProtocolTriggerType.GLOBAL_ALWAYS,
                stage = ProtocolStage.CHECK_IN,
                serviceIds = mutableSetOf(),
                displayOrder = 0,
                createdBy = SYSTEM_USER_ID,
                updatedBy = SYSTEM_USER_ID,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
