package pl.detailing.crm.inbound.register

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.domain.CallLog
import pl.detailing.crm.inbound.infrastructure.CallLogEntity
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class RegisterInboundCallHandler(
    private val callLogRepository: CallLogRepository,
    private val leadRepository: LeadRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(RegisterInboundCallHandler::class.java)
    @Transactional
    suspend fun handle(command: RegisterInboundCallCommand): RegisterInboundCallResult =
        withContext(Dispatchers.IO) {
            // Validate phone format
            val normalizedPhone = normalizePolishPhone(command.phoneNumber.trim())
            if (!isValidPolishPhone(normalizedPhone)) {
                throw ValidationException("Invalid Polish phone number format: ${command.phoneNumber}")
            }

            // Create call log
            val callLog = CallLog(
                id = CallId.random(),
                studioId = command.studioId,
                phoneNumber = normalizedPhone,
                callerName = command.callerName?.trim(),
                note = command.note?.trim(),
                status = CallLogStatus.PENDING,
                receivedAt = command.receivedAt,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Save call log entity
            val entity = CallLogEntity.fromDomain(callLog)
            callLogRepository.save(entity)

            // Create Lead from inbound call with requiresVerification=true
            val lead = Lead(
                id = LeadId.random(),
                studioId = command.studioId,
                source = LeadSource.PHONE,
                status = LeadStatus.IN_PROGRESS,
                contactIdentifier = normalizedPhone,
                customerName = command.callerName?.trim(),
                initialMessage = command.note?.trim(),
                estimatedValue = 0, // Default value, can be updated later
                requiresVerification = true, // Inbound calls require verification
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            val leadEntity = LeadEntity.fromDomain(lead)
            leadRepository.save(leadEntity)

            log.info("[INBOUND] Created lead from inbound call: callId={}, leadId={}, phone={}, requiresVerification=true",
                callLog.id.value, lead.id.value, normalizedPhone)

            // Publish event for WebSocket notification
            log.info("[INBOUND] Publishing NewCallReceivedEvent: callId={}, leadId={}, studioId={}, phone={}",
                callLog.id.value, lead.id.value, callLog.studioId.value, callLog.phoneNumber)
            eventPublisher.publishEvent(
                NewCallReceivedEvent(
                    source = this@RegisterInboundCallHandler,
                    studioId = callLog.studioId,
                    callId = callLog.id,
                    leadId = lead.id,
                    phoneNumber = callLog.phoneNumber,
                    callerName = callLog.callerName,
                    receivedAt = callLog.receivedAt,
                    estimatedValue = lead.estimatedValue
                )
            )
            log.debug("[INBOUND] Event published successfully")

            RegisterInboundCallResult(
                callId = callLog.id,
                leadId = lead.id,
                phoneNumber = callLog.phoneNumber,
                callerName = callLog.callerName,
                status = callLog.status,
                receivedAt = callLog.receivedAt
            )
        }
}

data class RegisterInboundCallResult(
    val callId: CallId,
    val leadId: LeadId,
    val phoneNumber: String,
    val callerName: String?,
    val status: CallLogStatus,
    val receivedAt: Instant
)
