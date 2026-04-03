package pl.detailing.crm.email.provider.javamail

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.detailing.crm.email.provider.EmailProvider

/**
 * Wires [JavaMailProvider] as the active [EmailProvider] bean.
 *
 * To swap to a different delivery provider, replace this @Configuration with
 * another one that registers a different [EmailProvider] implementation.
 */
@Configuration
@EnableConfigurationProperties(JavaMailProperties::class)
class JavaMailConfig {

    @Bean
    fun emailProvider(properties: JavaMailProperties): EmailProvider =
        JavaMailProvider(properties)
}
