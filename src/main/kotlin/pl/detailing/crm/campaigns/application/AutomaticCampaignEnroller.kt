package pl.detailing.crm.campaigns.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.campaigns.infrastructure.AudienceQueryService
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Hourly job for AUTOMATIC campaigns.
 *
 * For every ACTIVE campaign it finds visits whose pickup_date + afterDays falls today
 * (Europe/Warsaw), verifies the customer against the campaign's audience criteria and
 * the system filters, and enrolls them as PENDING recipients scheduled at the trigger's
 * sendTime. The unique index (campaign, customer, channel, trigger visit) plus the
 * exists-check make the job fully idempotent — running it twice enrolls nobody twice.
 */
@Service
class AutomaticCampaignEnroller(
    private val campaigns: CampaignRepository,
    private val recipients: CampaignRecipientRepository,
    private val service: CampaignService,
    private val audienceQuery: AudienceQueryService,
    private val renderer: CampaignTemplateRenderer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    fun enroll() {
        val active = campaigns.findAllActiveAutomatic()
        active.forEach { campaign ->
            runCatching { enrollFor(campaign) }
                .onFailure { ex -> logger.error("Automatic enrollment failed | campaign={}", campaign.id, ex) }
        }
    }

    private fun enrollFor(campaign: Campaign) {
        val trigger = campaign.trigger ?: return
        val settings = service.getSettings(campaign.studioId)

        val today: LocalDate = LocalDate.now(warsaw)
        // Wizyty odebrane dokładnie afterDays dni temu (okno całodniowe).
        val pickupDay = today.minusDays(trigger.afterDays.toLong())
        val pickupFrom = pickupDay.atStartOfDay(warsaw).toInstant()
        val pickupTo = pickupDay.plusDays(1).atStartOfDay(warsaw).toInstant()

        val triggered = audienceQuery.findTriggeredVisits(
            studioId = campaign.studioId,
            serviceIds = trigger.serviceIds,
            pickupFrom = pickupFrom,
            pickupTo = pickupTo,
            onlyIfNoVisitSince = trigger.onlyIfNoVisitSince
        )
        if (triggered.isEmpty()) return

        val sendTime = runCatching { LocalTime.parse(trigger.sendTime, timeFormat) }.getOrDefault(LocalTime.of(10, 0))
        val sendAtRaw = today.atTime(sendTime).atZone(warsaw).toInstant()
        val sendAt = shiftOutOfQuietHours(maxOf(sendAtRaw, java.time.Instant.now()), settings)

        val channels = when (campaign.channel) {
            CampaignChannel.SMS -> listOf(RecipientChannel.SMS)
            CampaignChannel.EMAIL -> listOf(RecipientChannel.EMAIL)
            CampaignChannel.BOTH -> listOf(RecipientChannel.SMS, RecipientChannel.EMAIL)
        }

        var enrolled = 0
        for (channel in channels) {
            val eligibleRows = audienceQuery.materialize(
                studioId = campaign.studioId,
                criteria = campaign.audience,
                channel = channel,
                frequencyCapDays = settings.frequencyCapDays,
                candidateCustomerIds = triggered.map { it.customerId }.distinct()
            ).associateBy { it.customerId }

            for (visit in triggered) {
                val row = eligibleRows[visit.customerId] ?: continue
                if (recipients.existsForTrigger(campaign.id, visit.customerId, channel, visit.visitId)) continue

                recipients.save(
                    CampaignRecipientFactory.build(
                        campaign = campaign,
                        channel = channel,
                        row = row,
                        sendAt = sendAt,
                        settings = settings,
                        renderer = renderer,
                        triggerVisitId = visit.visitId
                    )
                )
                enrolled++
            }
        }

        if (enrolled > 0) {
            logger.info("Automatic campaign enrolled {} recipient(s) | campaign={}", enrolled, campaign.id)
        }
    }
}
