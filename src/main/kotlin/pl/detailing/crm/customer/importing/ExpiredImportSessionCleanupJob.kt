package pl.detailing.crm.customer.importing

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Usuwa porzucone sesje importu.
 *
 * To nie jest sprzątanie miejsca w bazie — to higiena danych osobowych. Sesja trzyma
 * czyjąś książkę adresową: numery i adresy ludzi, którzy w większości nigdy nie zostaną
 * klientami tego studia i nie mają pojęcia, że ich dane gdziekolwiek trafiły. Zamknięta
 * karta przeglądarki nie może znaczyć, że zostają u nas na zawsze.
 *
 * Sesja zatwierdzona czyści listę kontaktów od razu (patrz [CustomerImportService.commit]);
 * to zadanie zajmuje się tymi, których nikt nie dokończył.
 */
@Component
class ExpiredImportSessionCleanupJob(
    private val importService: CustomerImportService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${crm.customers.import.purge-cron:0 20 3 * * *}")
    fun purge() {
        val removed = importService.purgeExpired()
        if (removed > 0) {
            logger.info("Sesje importu kontaktów: usunięto {} wygasłych", removed)
        }
    }
}
