package pl.detailing.crm.instagram.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Wybór aktywnego dostawcy danych Instagramowych.
 *
 * Property `instagram.provider`:
 * - LOOTER      (domyślny) – RapidAPI "Instagram Looter"
 * - IG_SCRAPER5             – legacy RapidAPI "ig-scraper5"
 */
@Configuration
class InstagramProviderConfig {

    private val log = LoggerFactory.getLogger(InstagramProviderConfig::class.java)

    @Bean
    @Primary
    fun instagramDataProvider(
        @Value("\${instagram.provider:LOOTER}") provider: String,
        looter: InstagramLooterClient,
        scraper5: IgScraper5Client
    ): InstagramDataProvider {
        val selected = when (provider.trim().uppercase()) {
            "IG_SCRAPER5" -> scraper5
            else -> looter
        }
        log.info("Instagram: aktywny dostawca danych = {}", selected.providerName)
        return selected
    }
}
