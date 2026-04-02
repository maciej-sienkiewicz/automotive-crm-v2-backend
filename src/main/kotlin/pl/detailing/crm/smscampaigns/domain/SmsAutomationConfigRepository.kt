package pl.detailing.crm.smscampaigns.domain

import pl.detailing.crm.shared.StudioId

/**
 * Port (domain interface) for persisting and retrieving per-studio automation configs.
 * Implemented in the infrastructure layer to isolate domain logic from JPA details.
 */
interface SmsAutomationConfigRepository {

    /** Returns the config for the given studio, or null if it has never been saved. */
    fun findByStudioId(studioId: StudioId): SmsAutomationConfig?

    /** Persists (insert-or-update) the config for the studio embedded in [config]. */
    fun save(config: SmsAutomationConfig): SmsAutomationConfig

    /** Returns configs for every studio that has at least one enabled rule. */
    fun findAllWithAnyRuleEnabled(): List<SmsAutomationConfig>
}
