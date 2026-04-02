package pl.detailing.crm.smscampaigns.automation

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository

/**
 * Returns the SMS automation config for the given studio.
 * If no config exists yet, a default (both rules disabled) is returned
 * without writing anything to the database — the write happens lazily on
 * the first explicit [UpdateAutomationConfigHandler] call.
 */
@Service
class GetAutomationConfigHandler(
    private val configRepository: SmsAutomationConfigRepository
) {
    fun handle(studioId: StudioId): SmsAutomationConfig =
        configRepository.findByStudioId(studioId) ?: SmsAutomationConfig.defaultFor(studioId)
}
