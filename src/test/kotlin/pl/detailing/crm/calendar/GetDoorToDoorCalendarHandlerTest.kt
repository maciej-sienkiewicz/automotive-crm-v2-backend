package pl.detailing.crm.calendar

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorEntity
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Dzień wyjazdu Door to Door liczy backend, w czasie polskim - kalendarz na froncie
 * bierze go wprost (znacznik samochodu w rogu dnia). Rezerwacja całodniowa zaczyna się
 * o północy czasu polskiego, czyli o 22:00 UTC dnia POPRZEDNIEGO; dzień z UTC stawiał
 * odbiór dzień za wcześnie.
 */
class GetDoorToDoorCalendarHandlerTest {

    private val studioId = StudioId(UUID.randomUUID())
    private val customerId = UUID.randomUUID()
    private val vehicleId = UUID.randomUUID()

    private val appointmentRepository = mockk<AppointmentRepository>()
    private val visitRepository = mockk<VisitRepository>()
    private val doorToDoorRepository = mockk<DoorToDoorRepository>()
    private val customerRepository = mockk<CustomerRepository>()
    private val vehicleRepository = mockk<VehicleRepository>()

    private val handler = GetDoorToDoorCalendarHandler(
        appointmentRepository, visitRepository, doorToDoorRepository, customerRepository, vehicleRepository
    )

    init {
        every { customerRepository.findAllById(any()) } returns listOf(mockk<CustomerEntity> {
            every { id } returns customerId
            every { lastName } returns "Mamzerowska"
        })
        every { vehicleRepository.findAllById(any()) } returns listOf(mockk<VehicleEntity> {
            every { id } returns vehicleId
            every { brand } returns "Bmw"
            every { model } returns "Seria 5"
        })
    }

    private fun reservation(start: String, end: String) = AppointmentEntity(
        id = UUID.fromString("792485a0-942e-4f04-af98-3651fe8a65ea"),
        studioId = studioId.value,
        customerId = customerId,
        vehicleId = vehicleId,
        appointmentTitle = "LIDL - BMW 5, pełne zabezpieczenie",
        appointmentColorId = UUID.randomUUID(),
        isAllDay = true,
        startDateTime = Instant.parse(start),
        endDateTime = Instant.parse(end),
        status = AppointmentStatus.CREATED,
        note = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID(),
        d2dPickupCity = "Jankowice",
        d2dPickupStreet = "Poznańska 48",
        d2dDeliveryCity = "Jankowice",
        d2dDeliveryStreet = "Poznańska 48",
    )

    private fun givenReservations(vararg appointments: AppointmentEntity) {
        every { appointmentRepository.findForCalendar(studioId.value, any(), any(), any(), null, null) } returns appointments.toList()
        every { visitRepository.findForCalendar(studioId.value, any(), any(), any(), null, null) } returns emptyList()
    }

    private fun days(from: String, to: String) = runBlocking {
        handler.handle(studioId, LocalDate.parse(from), LocalDate.parse(to))
            .associate { day -> day.date.toString() to day.entries.map { it.direction } }
    }

    @Test
    fun `rezerwacja calodniowa 24-28_09 - odbior 24_09, dostawa 28_09`() {
        givenReservations(reservation("2026-09-23T22:00:00Z", "2026-09-28T21:45:00Z"))

        assertEquals(
            mapOf(
                "2026-09-24" to listOf(DoorToDoorTripDirection.PICKUP),
                "2026-09-28" to listOf(DoorToDoorTripDirection.DELIVERY),
            ),
            days("2026-08-31", "2026-10-11"),
        )
    }

    @Test
    fun `czas zimowy - polnoc to 23_00 UTC dnia poprzedniego`() {
        givenReservations(reservation("2026-01-14T23:00:00Z", "2026-01-16T22:59:59Z"))

        assertEquals(
            mapOf(
                "2026-01-15" to listOf(DoorToDoorTripDirection.PICKUP),
                "2026-01-16" to listOf(DoorToDoorTripDirection.DELIVERY),
            ),
            days("2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `zakres dat obcina po dniu polskim, nie po UTC`() {
        givenReservations(reservation("2026-09-23T22:00:00Z", "2026-09-28T21:45:00Z"))

        // 23.09 w UTC to już 24.09 w Polsce - odbiór nie należy do zakresu kończącego się 23.09.
        assertEquals(emptyMap<String, List<DoorToDoorTripDirection>>(), days("2026-09-20", "2026-09-23"))
        assertEquals(mapOf("2026-09-24" to listOf(DoorToDoorTripDirection.PICKUP)), days("2026-09-24", "2026-09-27"))
    }

    @Test
    fun `dostawa z wizyty w dniu planowanego zakonczenia, liczonym po polsku`() {
        val visitId = UUID.randomUUID()
        every { appointmentRepository.findForCalendar(studioId.value, any(), any(), any(), null, null) } returns emptyList()
        every { visitRepository.findForCalendar(studioId.value, any(), any(), any(), null, null) } returns listOf(
            mockk<VisitEntity> {
                every { id } returns visitId
                every { customerId } returns this@GetDoorToDoorCalendarHandlerTest.customerId
                every { brandSnapshot } returns "Bmw"
                every { modelSnapshot } returns "X5"
                every { scheduledDate } returns Instant.parse("2026-09-14T07:00:00Z")
                // 00:30 17.09 czasu polskiego
                every { estimatedCompletionDate } returns Instant.parse("2026-09-16T22:30:00Z")
            }
        )
        every { doorToDoorRepository.findByVisitIdIn(listOf(visitId)) } returns listOf(mockk<DoorToDoorEntity> {
            every { this@mockk.visitId } returns visitId
            every { deliveryCity } returns "Poznań"
            every { deliveryStreet } returns "Lotnisko Ławica"
        })

        assertEquals(mapOf("2026-09-17" to listOf(DoorToDoorTripDirection.DELIVERY)), days("2026-08-31", "2026-10-11"))
    }
}
