package pl.detailing.crm.protocol.template

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository

/**
 * Startup backfill for the check-in template invariant.
 *
 * Studios created before default-template provisioning existed (or healed after a
 * failed seed at signup time) receive the bundled default acceptance protocol on
 * application start. The sweep is per-studio fault-isolated: one failing studio
 * (e.g. transient S3 error) never blocks the rest, and any failure is retried on
 * the next application start.
 *
 * Can be disabled with `protocol.default-template.backfill-on-startup=false`
 * (e.g. for one-off maintenance environments without S3 access).
 */
@Component
@Order(100)
class DefaultProtocolTemplateBackfillRunner(
    private val studioRepository: StudioRepository,
    private val provisioner: DefaultProtocolTemplateProvisioner,
    @Value("\${protocol.default-template.backfill-on-startup:true}") private val enabled: Boolean
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!enabled) {
            logger.info("Default template backfill disabled by configuration — skipping")
            return
        }

        val studios = studioRepository.findAll()
        var seeded = 0
        var failed = 0
        for (studio in studios) {
            try {
                if (provisioner.ensureDefaultCheckInTemplate(StudioId(studio.id))) {
                    seeded++
                }
            } catch (e: Exception) {
                failed++
                logger.error(
                    "Default template backfill failed for studio {}: {}",
                    studio.id, e.message, e
                )
            }
        }
        logger.info(
            "Default template backfill complete: {} studio(s) checked, {} seeded, {} failed",
            studios.size, seeded, failed
        )
    }
}
