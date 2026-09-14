package pl.detailing.crm.visitcard.upsell

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.domain.PriceCalculator
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.util.UUID

/**
 * Employee-side management of upsell suggestions, for visits and reservations
 * (appointments) alike.
 *
 * Suggestions are attached per visit/reservation, intentionally, one by one —
 * there is no bulk/automatic assignment. Pricing (with an optional discount) is
 * frozen at creation time from the catalog service, using the same price engine
 * as visit service items, so what the customer sees on the card is exactly what
 * would be added.
 */
@Service
class VisitUpsellAdminService(
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val serviceRepository: ServiceRepository,
    private val suggestionRepository: VisitUpsellSuggestionRepository,
    private val notifyHandler: NotifyUpsellSuggestionHandler
) {

    @Transactional(readOnly = true)
    fun list(visitId: VisitId, studioId: StudioId): List<UpsellSuggestionResponse> {
        val visit = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit not found: $visitId")
        // Suggestions assigned to the originating reservation stay visible on the visit.
        return suggestionRepository
            .findAllForVisitCard(visitId.value, visit.appointmentId, studioId.value)
            .map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listForAppointment(appointmentId: AppointmentId, studioId: StudioId): List<UpsellSuggestionResponse> {
        requireAppointment(appointmentId, studioId)
        return suggestionRepository.findAllByAppointmentIdAndStudioId(appointmentId.value, studioId.value)
            .map { it.toResponse() }
    }

    @Transactional
    fun create(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionRequest
    ): UpsellSuggestionResponse = createMany(visitId, studioId, userId, request.asBatch()).single()

    /** Kilka propozycji naraz — patrz [CreateUpsellSuggestionsRequest]. */
    @Transactional
    fun createMany(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionsRequest
    ): CreateUpsellSuggestionsResponse {
        visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit not found: $visitId")
        val entities = createSuggestions(studioId, userId, request, visitId = visitId.value, appointmentId = null)
        val notification =
            if (request.notifyCustomer) notifyHandler.notifyForVisit(visitId, studioId, entities) else null
        return CreateUpsellSuggestionsResponse(entities.map { it.toResponse() }, notification)
    }

    @Transactional
    fun createForAppointment(
        appointmentId: AppointmentId,
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionRequest
    ): UpsellSuggestionResponse =
        createManyForAppointment(appointmentId, studioId, userId, request.asBatch()).single()

    /** Kilka propozycji naraz — patrz [CreateUpsellSuggestionsRequest]. */
    @Transactional
    fun createManyForAppointment(
        appointmentId: AppointmentId,
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionsRequest
    ): CreateUpsellSuggestionsResponse {
        requireAppointment(appointmentId, studioId)
        val entities =
            createSuggestions(studioId, userId, request, visitId = null, appointmentId = appointmentId.value)
        val notification =
            if (request.notifyCustomer) notifyHandler.notifyForAppointment(appointmentId, studioId, entities) else null
        return CreateUpsellSuggestionsResponse(entities.map { it.toResponse() }, notification)
    }

    /**
     * Zapisuje całą listę w jednej transakcji: albo wszystkie propozycje trafiają na
     * kartę, albo żadna. Klient nie ma zobaczyć połowy tego, o czym za chwilę przeczyta
     * w SMS-ie, bo trzecia usługa okazała się nieaktywna.
     */
    private fun createSuggestions(
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionsRequest,
        visitId: UUID?,
        appointmentId: UUID?
    ): List<VisitUpsellSuggestionEntity> {
        if (request.suggestions.isEmpty()) {
            throw ValidationException("Nie wybrano żadnej usługi do zasugerowania")
        }
        if (request.suggestions.size > MAX_SUGGESTIONS_PER_REQUEST) {
            throw ValidationException(
                "Jednorazowo można dodać najwyżej $MAX_SUGGESTIONS_PER_REQUEST propozycji"
            )
        }
        return request.suggestions.map { item ->
            createSuggestion(
                studioId, userId,
                CreateUpsellSuggestionRequest(item.serviceId, item.adjustment, item.note),
                visitId, appointmentId
            )
        }
    }

    private fun createSuggestion(
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionRequest,
        visitId: UUID?,
        appointmentId: UUID?
    ): VisitUpsellSuggestionEntity {
        val serviceId = ServiceId.fromString(request.serviceId)
        val service = serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)
            ?: throw EntityNotFoundException("Usługa nie została znaleziona: ${request.serviceId}")
        if (!service.isActive) {
            throw ValidationException("Nie można zasugerować nieaktywnej usługi „${service.name}”")
        }

        val adjustmentType = request.adjustment?.type ?: AdjustmentType.PERCENT
        val adjustmentValue = when (adjustmentType) {
            AdjustmentType.PERCENT ->
                AdjustmentType.convertPercentValueToBasisPoints(request.adjustment?.value ?: 0.0)
            else -> (request.adjustment?.value ?: 0.0).toLong()
        }

        val basePriceNet = Money.fromCents(service.basePriceNet)
        val basePriceGross = Money.fromCents(service.basePriceGross)
        val vatRate = VatRate.fromInt(service.vatRate)
        val finalNet = PriceCalculator.calculateFinalNet(basePriceNet, vatRate, adjustmentType, adjustmentValue)
        // Preserve exact catalog gross when there is no adjustment; otherwise re-derive from adjusted net.
        val finalGross = vatRate.resolveGrossAmount(finalNet, if (finalNet == basePriceNet) basePriceGross else null)

        val entity = suggestionRepository.save(
            VisitUpsellSuggestionEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                visitId = visitId,
                appointmentId = appointmentId,
                serviceId = service.id,
                serviceName = service.name,
                basePriceNet = service.basePriceNet,
                vatRate = service.vatRate,
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValue,
                finalPriceNet = finalNet.amountInCents,
                finalPriceGross = finalGross.amountInCents,
                note = request.note?.takeIf { it.isNotBlank() },
                createdBy = userId.value
            )
        )
        return entity
    }

    /**
     * Removes a suggestion. Only allowed while it is still SUGGESTED — once the
     * customer has requested it (pending service item + consent SMS exist), the
     * record is part of the visit's history and must stay.
     */
    @Transactional
    fun delete(visitId: VisitId, suggestionId: UUID, studioId: StudioId) {
        val visit = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit not found: $visitId")
        val suggestion = suggestionRepository.findByIdAndStudioId(suggestionId, studioId.value)
            ?.takeIf { it.visitId == visitId.value || it.appointmentId == visit.appointmentId }
            ?: throw EntityNotFoundException("Sugestia nie została znaleziona: $suggestionId")
        deleteSuggestion(suggestion)
    }

    @Transactional
    fun deleteForAppointment(appointmentId: AppointmentId, suggestionId: UUID, studioId: StudioId) {
        val suggestion = suggestionRepository.findByIdAndStudioId(suggestionId, studioId.value)
            ?.takeIf { it.appointmentId == appointmentId.value }
            ?: throw EntityNotFoundException("Sugestia nie została znaleziona: $suggestionId")
        deleteSuggestion(suggestion)
    }

    private fun deleteSuggestion(suggestion: VisitUpsellSuggestionEntity) {
        if (suggestion.status != UpsellSuggestionStatus.SUGGESTED) {
            throw ValidationException("Nie można usunąć sugestii, na którą klient już odpowiedział")
        }
        suggestionRepository.delete(suggestion)
    }

    /** Pojedyncze utworzenie zachowuje dotychczasowy kształt odpowiedzi. */
    private fun CreateUpsellSuggestionsResponse.single(): UpsellSuggestionResponse =
        suggestions.single().copy(customerNotification = customerNotification)

    private fun CreateUpsellSuggestionRequest.asBatch() = CreateUpsellSuggestionsRequest(
        suggestions = listOf(CreateUpsellSuggestionItem(serviceId, adjustment, note)),
        notifyCustomer = notifyCustomer
    )

    /** Jedna wiadomość wymieniająca kilkanaście usług przestaje być czytelna — i tania. */
    private companion object {
        const val MAX_SUGGESTIONS_PER_REQUEST = 20
    }

    private fun requireAppointment(appointmentId: AppointmentId, studioId: StudioId) {
        appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value)
            ?: throw EntityNotFoundException("Appointment not found: $appointmentId")
    }
}

internal fun VisitUpsellSuggestionEntity.toResponse(): UpsellSuggestionResponse {
    val originalGross = VatRate.fromInt(vatRate)
        .calculateGrossAmount(Money.fromCents(basePriceNet))
        .amountInCents
    return UpsellSuggestionResponse.from(this, originalGross)
}
