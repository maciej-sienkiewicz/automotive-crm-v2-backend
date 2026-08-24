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
 * Startup backfill for the bundled consent documents (RODO + marketing).
 *
 * Studia założone zanim systemowe dokumenty istniały dostają je przy starcie aplikacji.
 * Przebieg jest odporny na pojedynczy błąd (np. chwilowy błąd S3): jedno studio nie
 * blokuje pozostałych, a nieudane próby powtórzą się przy następnym starcie.
 *
 * Wyłączane przez `consent.default-document.backfill-on-startup=false`.
 */
@Component
@Order(101)
class DefaultConsentDocumentsBackfillRunner(
    private val studioRepository: StudioRepository,
    private val provisioner: DefaultConsentDocumentsProvisioner,
    @Value("\${consent.default-document.backfill-on-startup:true}") private val enabled: Boolean
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!enabled) {
            logger.info("Default consent documents backfill disabled by configuration — skipping")
            return
        }

        val studios = studioRepository.findAll()
        var seeded = 0
        var refreshed = 0
        var failed = 0
        for (studio in studios) {
            try {
                val studioIdValue = StudioId(studio.id)
                if (provisioner.ensureDefaultRodoConsent(studioIdValue)) seeded++
                if (provisioner.ensureDefaultMarketingConsent(studioIdValue)) seeded++
                // Dokumenty, które studio już ma, dostają aktualną treść.
                refreshed += provisioner.refreshBundledDocuments(studioIdValue)
            } catch (e: Exception) {
                failed++
                logger.error(
                    "Default consent documents backfill failed for studio {}: {}",
                    studio.id, e.message, e
                )
            }
        }
        logger.info(
            "Default consent documents backfill complete: {} studio(s) checked, {} seeded, {} refreshed, {} failed",
            studios.size, seeded, refreshed, failed
        )
    }
}
