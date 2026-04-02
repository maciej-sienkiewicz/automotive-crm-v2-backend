package pl.detailing.crm.smscampaigns.provider.smsapi

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.detailing.crm.smscampaigns.provider.SmsProvider

/**
 * Wires [SmsApiProvider] as the active [SmsProvider] bean.
 *
 * To swap to a different provider, replace this @Configuration class with
 * another one that registers a different [SmsProvider] implementation —
 * no other code needs to change (Open-Closed Principle).
 */
@Configuration
@EnableConfigurationProperties(SmsApiProperties::class)
class SmsApiConfig {

    @Bean
    fun smsProvider(properties: SmsApiProperties): SmsProvider =
        SmsApiProvider(properties)
}
