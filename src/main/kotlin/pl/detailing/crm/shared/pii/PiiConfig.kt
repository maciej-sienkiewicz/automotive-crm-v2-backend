package pl.detailing.crm.shared.pii

import com.fasterxml.jackson.databind.Module
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [PiiMaskingModule] as a Jackson [Module] bean. Spring Boot merges Module beans
 * into the auto-configured ObjectMapper, which serves both REST responses and STOMP
 * payloads (WebSocketConfig injects the same mapper) — a single serialization choke point.
 */
@Configuration
class PiiConfig {
    @Bean
    fun piiMaskingModule(): Module = PiiMaskingModule()
}
