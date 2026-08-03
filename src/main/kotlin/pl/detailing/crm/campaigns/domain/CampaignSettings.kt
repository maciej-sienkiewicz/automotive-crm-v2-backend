package pl.detailing.crm.campaigns.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalTime

data class CampaignSettings(
    val studioId: StudioId,
    val quietHoursStart: LocalTime,
    val quietHoursEnd: LocalTime,
    val frequencyCapDays: Int,
    val smsFooter: String?,
    val emailFooter: String?,
    val updatedAt: Instant
) {
    companion object {
        fun defaultFor(studioId: StudioId) = CampaignSettings(
            studioId = studioId,
            quietHoursStart = LocalTime.of(20, 0),
            quietHoursEnd = LocalTime.of(8, 0),
            frequencyCapDays = 7,
            smsFooter = null,
            emailFooter = null,
            updatedAt = Instant.now()
        )
    }
}

enum class OptOutSource { SMS_STOP, EMAIL_LINK, MANUAL }
