package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

/**
 * Tests for Visit.calculateTotalNet() / calculateTotalGross() across all service states.
 *
 * Rules per spec:
 * - CONFIRMED: included at finalPriceNet / finalPriceGross
 * - PENDING/EDIT: included at confirmedSnapshot.finalPriceNet / confirmedSnapshot.finalPriceGross
 * - PENDING/DELETE: included at finalPriceNet / finalPriceGross (deletion not yet approved)
 * - PENDING/ADD: excluded (new service not yet approved)
 * - REJECTED: excluded
 */
class VisitTotalCostTest {

    private fun makeItem(
        finalPriceNet: Long,
        finalPriceGross: Long,
        status: VisitServiceStatus,
        pendingOperation: PendingOperation? = null,
        confirmedSnapshot: ConfirmedServiceSnapshot? = null
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
        pendingOperation = pendingOperation,
        confirmedSnapshot = confirmedSnapshot,
        customNote = null,
        createdAt = Instant.now(),
        confirmedAt = null,
        pendingAt = null
    )

    private fun makeSnapshot(finalPriceNet: Long, finalPriceGross: Long) = ConfirmedServiceSnapshot(
        basePriceNet = Money(finalPriceNet),
        vatRate = VatRate.VAT_23,
        adjustmentType = AdjustmentType.FIXED_NET,
        adjustmentValue = 0L,
        finalPriceNet = Money(finalPriceNet),
        finalPriceGross = Money(finalPriceGross),
        customNote = null
    )

    private fun makeVisit(items: List<VisitServiceItem>) = Visit(
        id = VisitId(UUID.randomUUID()),
        studioId = StudioId(UUID.randomUUID()),
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
        status = VisitStatus.IN_PROGRESS,
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
        createdBy = UserId(UUID.randomUUID()),
        updatedBy = UserId(UUID.randomUUID()),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    @Test
    fun `CONFIRMED service is included in total`() {
        val visit = makeVisit(listOf(makeItem(10000, 12300, VisitServiceStatus.CONFIRMED)))
        assertEquals(10000, visit.calculateTotalNet().amountInCents)
        assertEquals(12300, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `APPROVED service is included in total`() {
        val visit = makeVisit(listOf(makeItem(5000, 6150, VisitServiceStatus.APPROVED)))
        assertEquals(5000, visit.calculateTotalNet().amountInCents)
        assertEquals(6150, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `REJECTED service is excluded from total`() {
        val visit = makeVisit(listOf(makeItem(10000, 12300, VisitServiceStatus.REJECTED)))
        assertEquals(0, visit.calculateTotalNet().amountInCents)
        assertEquals(0, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `PENDING ADD service is excluded from total`() {
        val visit = makeVisit(listOf(
            makeItem(10000, 12300, VisitServiceStatus.PENDING, PendingOperation.ADD)
        ))
        assertEquals(0, visit.calculateTotalNet().amountInCents)
        assertEquals(0, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `PENDING EDIT uses snapshot (previous confirmed) price not new proposed price`() {
        // New proposed price: 8000 net / 9840 gross
        // Previous confirmed price (snapshot): 10000 net / 12300 gross
        val item = makeItem(
            finalPriceNet = 8000,
            finalPriceGross = 9840,
            status = VisitServiceStatus.PENDING,
            pendingOperation = PendingOperation.EDIT,
            confirmedSnapshot = makeSnapshot(10000, 12300)
        )
        val visit = makeVisit(listOf(item))
        assertEquals(10000, visit.calculateTotalNet().amountInCents)
        assertEquals(12300, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `PENDING DELETE service is included at current price until deletion approved`() {
        val item = makeItem(
            finalPriceNet = 10000,
            finalPriceGross = 12300,
            status = VisitServiceStatus.PENDING,
            pendingOperation = PendingOperation.DELETE
        )
        val visit = makeVisit(listOf(item))
        assertEquals(10000, visit.calculateTotalNet().amountInCents)
        assertEquals(12300, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `empty visit has zero total`() {
        val visit = makeVisit(emptyList())
        assertEquals(0, visit.calculateTotalNet().amountInCents)
        assertEquals(0, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `mixed statuses total correctly`() {
        // CONFIRMED: 10000 net / 12300 gross  → counted
        // PENDING/EDIT snapshot: 5000 net / 6150 gross → counted (snapshot price)
        // PENDING/ADD: 3000 net / 3690 gross → excluded
        // REJECTED: 2000 net / 2460 gross → excluded
        // PENDING/DELETE: 4000 net / 4920 gross → counted
        val items = listOf(
            makeItem(10000, 12300, VisitServiceStatus.CONFIRMED),
            makeItem(
                finalPriceNet = 7000, finalPriceGross = 8610,
                status = VisitServiceStatus.PENDING, pendingOperation = PendingOperation.EDIT,
                confirmedSnapshot = makeSnapshot(5000, 6150)
            ),
            makeItem(3000, 3690, VisitServiceStatus.PENDING, PendingOperation.ADD),
            makeItem(2000, 2460, VisitServiceStatus.REJECTED),
            makeItem(4000, 4920, VisitServiceStatus.PENDING, PendingOperation.DELETE)
        )
        val visit = makeVisit(items)
        // Expected: 10000 + 5000 + 4000 = 19000 net; 12300 + 6150 + 4920 = 23370 gross
        assertEquals(19000, visit.calculateTotalNet().amountInCents)
        assertEquals(23370, visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `spec example two CONFIRMED FIXED_NET services`() {
        // Service 1: finalPriceNet=8333, finalPriceGross=10250
        // Service 2: finalPriceNet=16667, finalPriceGross=20500
        val items = listOf(
            makeItem(8333, 10250, VisitServiceStatus.CONFIRMED),
            makeItem(16667, 20500, VisitServiceStatus.CONFIRMED)
        )
        val visit = makeVisit(items)
        assertEquals(25000, visit.calculateTotalNet().amountInCents)
        assertEquals(30750, visit.calculateTotalGross().amountInCents)
    }
}
