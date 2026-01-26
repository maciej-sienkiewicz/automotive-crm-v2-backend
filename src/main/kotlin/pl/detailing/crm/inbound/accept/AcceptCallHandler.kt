package pl.detailing.crm.inbound.accept

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class AcceptCallHandler(
    private val validatorComposite: AcceptCallValidatorComposite,
    private val callLogRepository: CallLogRepository,
    private val leadRepository: LeadRepository
) {
    private val log = LoggerFactory.getLogger(AcceptCallHandler::class.java)

    @Transactional
    suspend fun handle(command: AcceptCallCommand): AcceptCallResult =
        withContext(Dispatchers.IO) {
            // Validate
            val context = validatorComposite.validate(command)

            // Get the call log entity
            val callLogEntity = callLogRepository.findByIdAndStudioId(
                command.callId.value,
                command.studioId.value
            )!!

            // Transition to ACCEPTED
            callLogEntity.status = CallLogStatus.ACCEPTED
            callLogEntity.updatedAt = Instant.now()

            // Save call log
            callLogRepository.save(callLogEntity)

            // Create Lead from CallLog
            val lead = Lead(
                id = LeadId.random(),
                studioId = StudioId(callLogEntity.studioId),
                source = LeadSource.PHONE,
                status = LeadStatus.IN_PROGRESS,
                contactIdentifier = callLogEntity.phoneNumber,
                customerName = callLogEntity.callerName,
                initialMessage = callLogEntity.note,
                estimatedValue = 0, // Default value, can be updated later
                requiresVerification = false, // Accepted calls don't require verification
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            val leadEntity = LeadEntity.fromDomain(lead)
            leadRepository.save(leadEntity)

            log.info("[INBOUND] Created lead from accepted call: callId={}, leadId={}, phone={}",
                command.callId.value, lead.id.value, callLogEntity.phoneNumber)

            AcceptCallResult(
                callId = command.callId,
                leadId = lead.id,
                status = CallLogStatus.ACCEPTED
            )
        }
}

data class AcceptCallResult(
    val callId: CallId,
    val leadId: LeadId,
    val status: CallLogStatus
)
