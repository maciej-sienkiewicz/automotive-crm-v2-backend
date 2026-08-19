package pl.detailing.crm.protocol.template

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateDefaultRevisionEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateDefaultRevisionRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import java.time.Instant

/**
 * Propagates a changed bundled default template to studios that were already seeded.
 *
 * [DefaultProtocolTemplateProvisioner] gives every studio its OWN COPY of the
 * bundled file in S3 and only runs for a studio that has no working check-in
 * template. Shipping a corrected file therefore reaches new studios only — the
 * copies already sitting in S3 keep the old content indefinitely. That is how a
 * hardcoded third-party logo ended up on other studios' acceptance protocols and
 * survived every redeploy.
 *
 * This runner closes the gap: for every `is_default = true` template recorded below
 * [DefaultProtocolTemplateProvisioner.DEFAULT_TEMPLATE_REVISION] it re-uploads the
 * bundled bytes over the template's EXISTING s3Key. Nothing else changes — the
 * templateId, the CHECK_IN rule and the field mappings all stay valid, so filled
 * protocols keep working across the swap.
 *
 * Studio-uploaded templates are never touched: [pl.detailing.crm.protocol.template.CreateProtocolTemplateHandler]
 * always writes `isDefault = false`, so an `is_default = true` row is by construction
 * system-owned and its S3 object is by construction the bundled file.
 *
 * Runs after [DefaultProtocolTemplateBackfillRunner] (@Order 100) so freshly seeded
 * studios are already recorded at the current revision and skipped here. Per-template
 * fault isolation: one failing upload never blocks the rest, and an unrecorded
 * template is simply retried on the next start.
 *
 * Disable with `protocol.default-template.refresh-on-startup=false`.
 */
@Component
@Order(101)
class DefaultProtocolTemplateRefreshRunner(
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val defaultRevisionRepository: ProtocolTemplateDefaultRevisionRepository,
    private val provisioner: DefaultProtocolTemplateProvisioner,
    private val s3StorageService: S3ProtocolStorageService,
    @Value("\${protocol.default-template.refresh-on-startup:true}") private val enabled: Boolean
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!enabled) {
            logger.info("Default template refresh disabled by configuration — skipping")
            return
        }

        val current = DefaultProtocolTemplateProvisioner.DEFAULT_TEMPLATE_REVISION
        val recorded = defaultRevisionRepository.findAll().associate { it.templateId to it.revision }
        val stale = protocolTemplateRepository.findAllDefaults()
            .filter { (recorded[it.id] ?: 0) < current }

        if (stale.isEmpty()) {
            logger.debug("Default template refresh: all default templates already at revision {}", current)
            return
        }

        // Read once — the same bytes go to every studio.
        val bytes = provisioner.loadBundledTemplateBytes()
        var refreshed = 0
        var failed = 0

        for (template in stale) {
            try {
                s3StorageService.uploadBytes(template.s3Key, bytes, ProtocolTemplateFormat.PDF.contentType)
                defaultRevisionRepository.save(
                    ProtocolTemplateDefaultRevisionEntity(
                        templateId = template.id,
                        revision = current,
                        appliedAt = Instant.now()
                    )
                )
                refreshed++
                logger.info(
                    "Default template refresh: studio={} template={} updated to revision {} ({})",
                    template.studioId, template.id, current, template.s3Key
                )
            } catch (e: Exception) {
                failed++
                logger.error(
                    "Default template refresh failed for studio {} template {}: {}",
                    template.studioId, template.id, e.message, e
                )
            }
        }

        logger.info(
            "Default template refresh complete: revision={} {} stale, {} refreshed, {} failed",
            current, stale.size, refreshed, failed
        )
    }
}
