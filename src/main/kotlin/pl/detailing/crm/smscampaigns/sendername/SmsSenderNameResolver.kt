package pl.detailing.crm.smscampaigns.sendername

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigJpaRepository
import java.util.UUID

/**
 * Single source of truth for the sender header of every outbound SMS.
 *
 * The only place a studio's sender ID may come from is
 * `sms_automation_configs.sms_sender_name` — the value the studio submits in the
 * sender-name screen together with its signed authorization document, flagged as
 * `sms_api_name_confirmed` once SMSAPI accepts it. Anything else (the company name in
 * studio settings, a global property) is guesswork SMSAPI would reject.
 *
 * A null result is not an error: it means "this studio has no confirmed header yet", and
 * [pl.detailing.crm.smscampaigns.provider.smsapi.SmsApiProvider] then sends the message as
 * ECO — from an SMSAPI number rather than under a placeholder name.
 */
@Service
class SmsSenderNameResolver(
    private val configRepository: SmsAutomationConfigJpaRepository
) {

    fun resolve(studioId: UUID): String? {
        val config = configRepository.findByStudioId(studioId) ?: return null
        if (!config.smsApiNameConfirmed) return null
        return config.smsSenderName?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resolve(studioId: StudioId): String? = resolve(studioId.value)
}
