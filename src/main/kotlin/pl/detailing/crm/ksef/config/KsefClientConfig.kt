package pl.detailing.crm.ksef.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(KsefProperties::class)
class KsefClientConfig {

    @Bean("ksefRestClient")
    fun ksefRestClient(properties: KsefProperties): RestClient {
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Accept", "application/json")
            .build()
    }
}
