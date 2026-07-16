package pl.detailing.crm.visitcard.upsell

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.service.infrastructure.ServiceRepository
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
 * Employee-side management of upsell suggestions.
 *
 * Suggestions are attached per visit, intentionally, one by one — there is no
 * bulk/automatic assignment. Pricing (with an optional discount) is frozen at
 * creation time from the catalog service, using the same price engine as visit
 * service items, so what the customer sees on the card is exactly what would be
 * added to the visit.
 */
@Service
class VisitUpsellAdminService(
    private val visitRepository: VisitRepository,
    private val serviceRepository: ServiceRepository,
    private val suggestionRepository: VisitUpsellSuggestionRepository
) {

    @Transactional(readOnly = true)
    fun list(visitId: VisitId, studioId: StudioId): List<UpsellSuggestionResponse> {
        requireVisit(visitId, studioId)
        return suggestionRepository.findAllByVisitIdAndStudioId(visitId.value, studioId.value)
            .map { it.toResponse() }
    }

    @Transactional
    fun create(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        request: CreateUpsellSuggestionRequest
    ): UpsellSuggestionResponse {
        requireVisit(visitId, studioId)

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
        val vatRate = VatRate.fromInt(service.vatRate)
        val finalNet = PriceCalculator.calculateFinalNet(basePriceNet, vatRate, adjustmentType, adjustmentValue)
        val finalGross = vatRate.calculateGrossAmount(finalNet)

        val entity = suggestionRepository.save(
            VisitUpsellSuggestionEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                visitId = visitId.value,
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
        return entity.toResponse()
    }

    /**
     * Removes a suggestion. Only allowed while it is still SUGGESTED — once the
     * customer has requested it (pending service item + consent SMS exist), the
     * record is part of the visit's history and must stay.
     */
    @Transactional
    fun delete(visitId: VisitId, suggestionId: UUID, studioId: StudioId) {
        val suggestion = suggestionRepository.findByIdAndStudioId(suggestionId, studioId.value)
            ?.takeIf { it.visitId == visitId.value }
            ?: throw EntityNotFoundException("Sugestia nie została znaleziona: $suggestionId")

        if (suggestion.status != UpsellSuggestionStatus.SUGGESTED) {
            throw ValidationException("Nie można usunąć sugestii, na którą klient już odpowiedział")
        }
        suggestionRepository.delete(suggestion)
    }

    private fun requireVisit(visitId: VisitId, studioId: StudioId) {
        visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit not found: $visitId")
    }
}

internal fun VisitUpsellSuggestionEntity.toResponse(): UpsellSuggestionResponse {
    val originalGross = VatRate.fromInt(vatRate)
        .calculateGrossAmount(Money.fromCents(basePriceNet))
        .amountInCents
    return UpsellSuggestionResponse.from(this, originalGross)
}
