package pl.detailing.crm.visit.domain

import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

/** Wspólne fabryki obiektów domenowych wizyty dla testów bezpieczeństwa. */
object VisitFixtures {

    fun serviceItem(
        finalPriceNet: Long = 10_000,
        finalPriceGross: Long = 12_300,
        status: VisitServiceStatus = VisitServiceStatus.CONFIRMED
    ) = VisitServiceItem(
        id = VisitServiceItemId(UUID.randomUUID()),
        serviceId = null,
        serviceName = "Test",
        basePriceNet = Money(finalPriceNet),
        vatRate = VatRate.VAT_23,
        adjustmentType = AdjustmentType.FIXED_NET,
        adjustmentValue = 0L,
        finalPriceNet = Money(finalPriceNet),
        finalPriceGross = Money(finalPriceGross),
        status = status,
        pendingOperation = null,
        confirmedSnapshot = null,
        customNote = null,
        createdAt = Instant.now(),
        confirmedAt = null,
        pendingAt = null
    )

    fun visit(
        studioId: StudioId = StudioId.random(),
        status: VisitStatus = VisitStatus.IN_PROGRESS,
        items: List<VisitServiceItem> = listOf(serviceItem())
    ) = Visit(
        id = VisitId(UUID.randomUUID()),
        studioId = studioId,
        visitNumber = "V/1",
        customerId = CustomerId(UUID.randomUUID()),
        vehicleId = VehicleId(UUID.randomUUID()),
        appointmentId = AppointmentId(UUID.randomUUID()),
        appointmentColorId = null,
        title = null,
        brandSnapshot = "Brand",
        modelSnapshot = "Model",
        licensePlateSnapshot = null,
        vinSnapshot = null,
        yearOfProductionSnapshot = null,
        colorSnapshot = null,
        status = status,
        scheduledDate = Instant.now(),
        estimatedCompletionDate = null,
        actualCompletionDate = null,
        pickupDate = null,
        mileageAtArrival = null,
        keysHandedOver = false,
        documentsHandedOver = false,
        inspectionNotes = null,
        technicalNotes = null,
        vehicleHandoff = null,
        serviceItems = items,
        photos = emptyList(),
        damageMapFileId = null,
        smsReminderSuppressed = false,
        createdBy = UserId(UUID.randomUUID()),
        updatedBy = UserId(UUID.randomUUID()),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
