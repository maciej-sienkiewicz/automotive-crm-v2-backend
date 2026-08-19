package pl.detailing.crm.metrics.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Activates [MetricsProperties]. Everything else in the module is a `@Component`
 * picked up by component scanning — there is deliberately no bean wiring ceremony here.
 */
@Configuration
@EnableConfigurationProperties(MetricsProperties::class)
class MetricsConfig
