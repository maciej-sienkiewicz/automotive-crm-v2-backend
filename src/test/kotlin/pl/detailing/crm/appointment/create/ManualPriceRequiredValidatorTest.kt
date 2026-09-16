package pl.detailing.crm.appointment.create

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.create.validators.ManualPriceRequiredValidator
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentSchedule
import pl.detailing.crm.service.domain.Service
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Walidator istniał od dawna i nie był podpięty do żadnej kompozycji, więc pozycja
 * bez ceny przechodziła bez słowa i zapisywała się jako 0 zł. Te testy pilnują obu
 * rzeczy naraz: że brak ceny jest odmową, i że OBA kształty, którymi nasze okna
 * przysyłają cenę ręczną, przechodzą.
 */
class ManualPriceRequiredValidatorTest {

    private val validator = ManualPriceRequiredValidator()

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val manualService = service(requireManualPrice = true)
    private val catalogService = service(requireManualPrice = false, net = 10_000L, gross = 12_300L)

    private fun service(
        requireManualPrice: Boolean,
        net: Long = 0L,
        gross: Long = 0L
    ) = Service(
        id = ServiceId.random(),
        studioId = studioId,
        name = if (requireManualPrice) "Detailing indywidualny" else "Mycie zewnętrzne",
        basePriceNet = Money.fromCents(net),
        basePriceGross = Money.fromCents(gross),
        vatRate = VatRate.fromInt(23),
        isActive = true,
        requireManualPrice = requireManualPrice,
        isPackage = false,
        replacesServiceId = null,
        createdBy = userId,
        updatedBy = userId,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun context(vararg lineItems: ServiceLineItemCommand) = CreateAppointmentValidationContext(
        studioId = studioId,
        services = listOf(manualService, catalogService),
        overlappingAppointments = emptyList(),
        existingCustomer = null,
        existingVehicle = null,
        appointmentColorExists = true,
        customerEmailExists = false,
        customerPhoneExists = false,
        requestedServiceIds = lineItems.map { it.serviceId },
        requestedServiceLineItems = lineItems.toList(),
        schedule = AppointmentSchedule(
            isAllDay = false,
            startDateTime = Instant.parse("2026-09-16T09:00:00Z"),
            endDateTime = Instant.parse("2026-09-16T10:00:00Z")
        ),
        customerIdentity = CustomerIdentity.Existing(CustomerId.random()),
        vehicleIdentity = VehicleIdentity.None
    )

    private fun lineItem(
        service: Service,
        basePriceNet: Long = 0L,
        basePriceGross: Long? = null,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Double = 0.0
    ) = ServiceLineItemCommand(
        serviceId = service.id,
        serviceName = service.name,
        basePriceNet = basePriceNet,
        basePriceGross = basePriceGross,
        vatRate = 23,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        customNote = null
    )

    @Test
    fun `usluga z cena reczna bez zadnej kwoty jest odrzucana`() {
        val error = assertThrows<ValidationException> {
            validator.validate(context(lineItem(manualService)))
        }
        assert(error.message!!.contains("Detailing indywidualny"))
    }

    @Test
    fun `cena w basePriceNet wystarczy - tak wysyla kreator wizyty`() {
        assertDoesNotThrow {
            validator.validate(context(lineItem(manualService, basePriceNet = 500_000L)))
        }
    }

    @Test
    fun `cena w rabacie SET_NET wystarczy - tak wysyla przyjecie pojazdu`() {
        assertDoesNotThrow {
            validator.validate(context(lineItem(
                manualService,
                adjustmentType = AdjustmentType.SET_NET,
                adjustmentValue = 500_000.0
            )))
        }
    }

    @Test
    fun `cena w rabacie SET_GROSS wystarczy`() {
        assertDoesNotThrow {
            validator.validate(context(lineItem(
                manualService,
                adjustmentType = AdjustmentType.SET_GROSS,
                adjustmentValue = 615_000.0
            )))
        }
    }

    @Test
    fun `samo brutto tez jest cena`() {
        assertDoesNotThrow {
            validator.validate(context(lineItem(manualService, basePriceGross = 615_000L)))
        }
    }

    @Test
    fun `zwykla usluga z cennika nie podlega tej regule`() {
        assertDoesNotThrow {
            validator.validate(context(lineItem(catalogService)))
        }
    }

    @Test
    fun `pozycja bez serviceId - usluga spoza cennika - nie podlega tej regule`() {
        val custom = ServiceLineItemCommand(
            serviceId = null,
            serviceName = "Robota jednorazowa",
            basePriceNet = 0L,
            basePriceGross = null,
            vatRate = 23,
            adjustmentType = AdjustmentType.PERCENT,
            adjustmentValue = 0.0,
            customNote = null
        )
        assertDoesNotThrow { validator.validate(context(custom)) }
    }
}
