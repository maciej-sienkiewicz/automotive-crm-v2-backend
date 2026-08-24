package pl.detailing.crm.customer.consent.template

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository

/**
 * Startup backfill for the marketing-consent document.
 *
 * Studia założone zanim systemowa zgoda istniała dostają ją przy starcie aplikacji.
 * Przebieg jest odporny na pojedynczy błąd (np. chwilowy błąd S3): jedno studio nie
 * blokuje pozostałych, a nieudane próby powtórzą się przy następnym starcie.
 *
 * Wyłączane przez `consent.default-document.backfill-on-startup=false`.
 */
@Component
@Order(101)
class DefaultMarketingConsentBackfillRunner(
    private val studioRepository: StudioRepository,
    private val provisioner: DefaultMarketingConsentProvisioner,
    @Value("\${consent.default-document.backfill-on-startup:true}") private val enabled: Boolean
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!enabled) {
            logger.info("Default marketing consent backfill disabled by configuration — skipping")
            return
        }

        val studios = studioRepository.findAll()
        var seeded = 0
        var failed = 0
        for (studio in studios) {
            try {
                if (provisioner.ensureDefaultMarketingConsent(StudioId(studio.id))) {
                    seeded++
                }
            } catch (e: Exception) {
                failed++
                logger.error(
                    "Default marketing consent backfill failed for studio {}: {}",
                    studio.id, e.message, e
                )
            }
        }
        logger.info(
            "Default marketing consent backfill complete: {} studio(s) checked, {} seeded, {} failed",
            studios.size, seeded, failed
        )
    }
}
