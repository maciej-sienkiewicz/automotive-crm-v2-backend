package pl.detailing.crm.appointment.recurrence.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.create.CreateAppointmentHandler
import pl.detailing.crm.appointment.domain.*
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.recurrence.domain.*
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesEntity
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateRecurringAppointmentHandler(
    private val validatorComposite: CreateAppointmentValidatorComposite,
    private val appointmentRepository: AppointmentRepository,
    private val recurrenceSeriesRepository: RecurrenceSeriesRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val serviceRepository: ServiceRepository,
    private val auditService: AuditService,
    private val createAppointmentHandler: CreateAppointmentHandler
) {

    companion object {
        private const val MAX_OCCURRENCES = RecurrenceSeries.MAX_OCCURRENCES_HARD_CAP
    }

    @Transactional
    suspend fun handle(command: CreateRecurringAppointmentCommand): CreateRecurringAppointmentResult =
        withContext(Dispatchers.IO) {
            val rule = command.recurrenceRule

            // Validate rule params
            validateRule(rule)

            // Step 1: resolve customer + vehicle once (by creating the first appointment normally)
            val firstResult = createAppointmentHandler.handle(command.base)

            // Step 2: build recurrence series domain object
            val series = RecurrenceSeries(
                id = RecurrenceSeriesId.random(),
                studioId = command.base.studioId,
                type = rule.type,
                intervalWeeks = rule.intervalWeeks,
                daysOfWeek = rule.daysOfWeek,
                dayOfMonth = rule.dayOfMonth,
                endType = rule.endType,
                endDate = rule.endDate,
                maxOccurrences = rule.maxOccurrences,
                isOpenEnded = rule.endType == RecurrenceEndType.OPEN,
                createdBy = command.base.userId,
                createdAt = Instant.now()
            )

            // Step 3: persist series
            recurrenceSeriesRepository.save(RecurrenceSeriesEntity.fromDomain(series))

            // Step 4: generate occurrence dates (excluding index 0 which is the first appointment)
            val templateStart = command.base.schedule.startDateTime
            val templateEnd = command.base.schedule.endDateTime
            val allDates = series.generateOccurrenceDates(templateStart, templateEnd)

            // Step 5: link first appointment to series (index 0)
            val firstEntity = appointmentRepository.findByIdAndStudioId(
                firstResult.appointmentId.value,
                command.base.studioId.value
            )!!
            firstEntity.recurrenceSeriesId = series.id.value
            firstEntity.recurrenceIndex = 0
            appointmentRepository.save(firstEntity)

            // Step 6: build line item templates from the first entity (prices already resolved)
            val lineItemTemplate = firstEntity.lineItems

            // Step 7: bulk-create remaining occurrences (indices 1..N)
            val additionalEntities = allDates.drop(1).mapIndexed { idx, (start, end) ->
                val occurrence = AppointmentEntity(
                    id = java.util.UUID.randomUUID(),
                    studioId = command.base.studioId.value,
                    customerId = firstResult.customerId.value,
                    vehicleId = firstResult.vehicleId?.value,
                    appointmentTitle = command.base.appointmentTitle,
                    appointmentColorId = command.base.appointmentColorId.value,
                    isAllDay = command.base.schedule.isAllDay,
                    startDateTime = start,
                    endDateTime = end,
                    status = AppointmentStatus.CREATED,
                    note = command.base.note,
                    sendReminderSms = command.base.sendReminderSms,
                    createdBy = command.base.userId.value,
                    updatedBy = command.base.userId.value,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    recurrenceSeriesId = series.id.value,
                    recurrenceIndex = idx + 1,
                    isDetached = false
                )
                // Copy line items from template
                occurrence.lineItems = lineItemTemplate.map { li ->
                    pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity(
                        appointment = occurrence,
                        serviceId = li.serviceId,
                        serviceName = li.serviceName,
                        basePriceNet = li.basePriceNet,
                        vatRate = li.vatRate,
                        adjustmentType = li.adjustmentType,
                        adjustmentValue = li.adjustmentValue,
                        finalPriceNet = li.finalPriceNet,
                        finalPriceGross = li.finalPriceGross,
                        customNote = li.customNote
                    )
                }.toMutableList()
                occurrence
            }

            appointmentRepository.saveAll(additionalEntities)

            // Step 8: audit log
            auditService.log(LogAuditCommand(
                studioId = command.base.studioId,
                userId = command.base.userId,
                userDisplayName = command.base.userName ?: "",
                module = AuditModule.APPOINTMENT,
                entityId = series.id.value.toString(),
                entityDisplayName = command.base.appointmentTitle,
                action = AuditAction.CREATE,
                metadata = mapOf(
                    "seriesId" to series.id.value.toString(),
                    "occurrenceCount" to allDates.size.toString(),
                    "recurrenceType" to rule.type.name
                )
            ))

            CreateRecurringAppointmentResult(
                seriesId = series.id,
                occurrenceCount = allDates.size,
                firstAppointmentId = firstResult.appointmentId,
                customerId = firstResult.customerId,
                vehicleId = firstResult.vehicleId
            )
        }

    private fun validateRule(rule: RecurrenceRuleCommand) {
        when (rule.type) {
            RecurrenceType.WEEKLY -> {
                require((rule.intervalWeeks ?: 0) in 1..52) {
                    "intervalWeeks musi być między 1 a 52"
                }
                require(!rule.daysOfWeek.isNullOrEmpty()) {
                    "daysOfWeek jest wymagane dla reguły tygodniowej"
                }
            }
            RecurrenceType.MONTHLY -> {
                val dom = rule.dayOfMonth ?: 0
                require(dom in 1..28) {
                    "dayOfMonth musi być między 1 a 28"
                }
            }
        }
        when (rule.endType) {
            RecurrenceEndType.DATE -> require(rule.endDate != null) { "endDate jest wymagane dla endType=DATE" }
            RecurrenceEndType.COUNT -> {
                val cnt = rule.maxOccurrences ?: 0
                require(cnt in 1..MAX_OCCURRENCES) { "maxOccurrences musi być między 1 a $MAX_OCCURRENCES" }
            }
            RecurrenceEndType.OPEN -> { /* no additional validation */ }
        }
    }
}
