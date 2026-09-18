package pl.detailing.crm.careinstruction

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository

/**
 * Zasiew słownika instrukcji dla studiów założonych, zanim ten moduł powstał.
 *
 * Ten sam wzorzec co [pl.detailing.crm.protocol.template.DefaultProtocolTemplateBackfillRunner]:
 * przebieg jest odporny na pojedyncze studio — błąd na jednym nie blokuje reszty i wraca
 * przy następnym starcie. Powtórne uruchomienie nic nie robi, bo provisioner ma znacznik.
 */
@Component
@Order(110)
class DefaultCareInstructionBackfillRunner(
    private val studioRepository: StudioRepository,
    private val provisioner: DefaultCareInstructionProvisioner,
    @Value("\${crm.care-instructions.backfill-on-startup:true}") private val enabled: Boolean
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!enabled) {
            logger.info("Care instruction backfill disabled by configuration — skipping")
            return
        }

        var seeded = 0
        var failed = 0
        val studios = studioRepository.findAll()
        for (studio in studios) {
            try {
                if (provisioner.ensureDefaults(StudioId(studio.id))) seeded++
            } catch (e: Exception) {
                failed++
                logger.error("Care instruction backfill failed for studio {}: {}", studio.id, e.message, e)
            }
        }
        logger.info(
            "Care instruction backfill complete: {} studio(s) checked, {} seeded, {} failed",
            studios.size, seeded, failed
        )
    }
}
