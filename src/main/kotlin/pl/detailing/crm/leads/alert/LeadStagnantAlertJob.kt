package pl.detailing.crm.leads.alert

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class LeadStagnantAlertJob(
    private val leadRepository: LeadRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository
) {
    private val log = LoggerFactory.getLogger(LeadStagnantAlertJob::class.java)

    @Scheduled(cron = "0 0 * * * *")
    fun run() = runBlocking {
        val studioIds = leadRepository.findDistinctStudioIds()
        log.debug("[LEAD_ALERT] Running stagnant lead check for {} studios", studioIds.size)

        for (studioId in studioIds) {
            try {
                processStudio(studioId.let { java.util.UUID.fromString(it.toString()) })
            } catch (e: Exception) {
                log.error("[LEAD_ALERT] Error processing studio={}", studioId, e)
            }
        }
    }

    @Transactional
    fun processStudio(studioId: java.util.UUID) {
        val settings = studioSettingsRepository.findById(studioId).orElse(null)
        val ourThresholdHours = settings?.leadStagnantOurThresholdHours?.toLong() ?: 48L
        val clientThresholdHours = settings?.leadStagnantClientThresholdHours?.toLong() ?: 72L

        val now = Instant.now()
        val ourThreshold = now.minus(ourThresholdHours, ChronoUnit.HOURS)
        val clientThreshold = now.minus(clientThresholdHours, ChronoUnit.HOURS)

        // Find a studio owner to use as task creator
        val ownerUser = userRepository.findActiveByStudioId(studioId)
            .minByOrNull { it.createdAt }
            ?: return

        val ownerUserId = UserId(ownerUser.id)

        // NEW leads without our response
        val stagnantNew = leadRepository.findStagnantNewLeads(studioId, ourThreshold)
        for (lead in stagnantNew) {
            val displayName = lead.customerName?.takeIf { it.isNotBlank() } ?: lead.contactIdentifier
            val task = Task(
                id = TaskId.random(),
                studioId = StudioId(studioId),
                createdByUserId = ownerUserId,
                title = "Lead bez naszej odpowiedzi: $displayName",
                meta = "Lead oczekuje odpowiedzi od ${ourThresholdHours}h. ID: ${lead.id}",
                done = false,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
                completedByUserId = null,
                deletedAt = null,
                deletedByUserId = null
            )
            taskRepository.save(TaskEntity.fromDomain(task))

            lead.stagnantAlertSentAt = now
            leadRepository.save(lead)

            log.info("[LEAD_ALERT] Created task for stagnant NEW lead: leadId={}, studioId={}", lead.id, studioId)
        }

        // IN_PROGRESS leads without client response
        val stagnantInProgress = leadRepository.findStagnantInProgressLeads(studioId, clientThreshold)
        for (lead in stagnantInProgress) {
            val displayName = lead.customerName?.takeIf { it.isNotBlank() } ?: lead.contactIdentifier
            val task = Task(
                id = TaskId.random(),
                studioId = StudioId(studioId),
                createdByUserId = ownerUserId,
                title = "Klient czeka na Twoją odpowiedź: $displayName",
                meta = "Lead IN_PROGRESS bez aktywności od ${clientThresholdHours}h. ID: ${lead.id}",
                done = false,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
                completedByUserId = null,
                deletedAt = null,
                deletedByUserId = null
            )
            taskRepository.save(TaskEntity.fromDomain(task))

            lead.stagnantAlertSentAt = now
            leadRepository.save(lead)

            log.info("[LEAD_ALERT] Created task for stagnant IN_PROGRESS lead: leadId={}, studioId={}", lead.id, studioId)
        }
    }
}
