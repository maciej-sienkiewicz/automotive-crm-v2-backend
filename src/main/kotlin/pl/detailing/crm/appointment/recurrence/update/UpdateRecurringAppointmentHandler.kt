package pl.detailing.crm.appointment.recurrence.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

enum class RecurrenceEditScope { THIS, THIS_AND_FUTURE, ALL }

data class UpdateRecurringAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null,
    val scope: RecurrenceEditScope,
    // Fields to update (null = no change)
    val appointmentTitle: String?,
    val appointmentColorId: AppointmentColorId?,
    val note: String?,
    val sendReminderSms: Boolean?,
    // New line items (null = no change to line items)
    val lineItemUpdater: ((AppointmentLineItemEntity) -> Unit)? = null,
    val newLineItems: List<AppointmentLineItemEntity>? = null,
    // If true, copy line items from anchor appointment to all targets
    val copyLineItemsFromAnchor: Boolean = false
)

data class UpdateRecurringAppointmentResult(
    val updatedCount: Int,
    val skippedDetachedCount: Int,
    val skippedConvertedCount: Int
)

@Service
class UpdateRecurringAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val recurrenceSeriesRepository: RecurrenceSeriesRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: UpdateRecurringAppointmentCommand): UpdateRecurringAppointmentResult =
        withContext(Dispatchers.IO) {
            val anchor = appointmentRepository.findByIdAndStudioId(
                command.appointmentId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Rezerwacja nie została znaleziona: ${command.appointmentId}")

            val seriesId = anchor.recurrenceSeriesId
                ?: throw IllegalStateException("Rezerwacja ${command.appointmentId} nie należy do żadnej serii")

            // Verify series belongs to studio
            recurrenceSeriesRepository.findByIdAndStudioId(seriesId, command.studioId.value)
                ?: throw EntityNotFoundException("Seria cykliczna nie została znaleziona")

            val anchorLineItems = if (command.copyLineItemsFromAnchor) anchor.lineItems.toList() else null

            val targets = when (command.scope) {
                RecurrenceEditScope.THIS -> {
                    // Mark as detached and update only this one
                    anchor.isDetached = true
                    listOf(anchor)
                }
                RecurrenceEditScope.THIS_AND_FUTURE -> {
                    val fromIndex = anchor.recurrenceIndex ?: 0
                    appointmentRepository.findBySeriesIdAndIndexGreaterThanEqual(seriesId, fromIndex)
                }
                RecurrenceEditScope.ALL -> {
                    appointmentRepository.findNonDetachedBySeriesId(seriesId)
                }
            }

            var skippedDetached = 0
            var skippedConverted = 0
            val toSave = mutableListOf<pl.detailing.crm.appointment.infrastructure.AppointmentEntity>()

            val now = Instant.now()

            for (entity in targets) {
                if (command.scope == RecurrenceEditScope.THIS_AND_FUTURE || command.scope == RecurrenceEditScope.ALL) {
                    if (entity.isDetached) { skippedDetached++; continue }
                }
                if (entity.status == AppointmentStatus.CONVERTED) { skippedConverted++; continue }

                command.appointmentTitle?.let { entity.appointmentTitle = it }
                command.appointmentColorId?.let { entity.appointmentColorId = it.value }
                command.note?.let { entity.note = it }
                command.sendReminderSms?.let { entity.sendReminderSms = it }
                entity.updatedBy = command.userId.value
                entity.updatedAt = now

                val lineItemSource = command.newLineItems
                    ?: if (anchorLineItems != null && entity.id != anchor.id) anchorLineItems else null

                if (lineItemSource != null) {
                    entity.lineItems.clear()
                    val newItems = lineItemSource.map { template ->
                        AppointmentLineItemEntity(
                            appointment = entity,
                            serviceId = template.serviceId,
                            serviceName = template.serviceName,
                            basePriceNet = template.basePriceNet,
                            vatRate = template.vatRate,
                            adjustmentType = template.adjustmentType,
                            adjustmentValue = template.adjustmentValue,
                            finalPriceNet = template.finalPriceNet,
                            finalPriceGross = template.finalPriceGross,
                            customNote = template.customNote
                        )
                    }
                    entity.lineItems.addAll(newItems)
                }

                toSave.add(entity)
            }

            val updatedCount = toSave.size
            appointmentRepository.saveAll(toSave)

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.APPOINTMENT,
                entityId = seriesId.toString(),
                entityDisplayName = command.appointmentTitle,
                action = AuditAction.UPDATE,
                metadata = mapOf(
                    "scope" to command.scope.name,
                    "updatedCount" to updatedCount.toString(),
                    "skippedDetachedCount" to skippedDetached.toString(),
                    "skippedConvertedCount" to skippedConverted.toString()
                )
            ))

            UpdateRecurringAppointmentResult(
                updatedCount = updatedCount,
                skippedDetachedCount = skippedDetached,
                skippedConvertedCount = skippedConverted
            )
        }
}
