package pl.detailing.crm.visit.drafts

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Aktywuje [DraftVisitProperties]; reszta modułu to zwykłe komponenty ze skanowania. */
@Configuration
@EnableConfigurationProperties(DraftVisitProperties::class)
class DraftVisitConfig
