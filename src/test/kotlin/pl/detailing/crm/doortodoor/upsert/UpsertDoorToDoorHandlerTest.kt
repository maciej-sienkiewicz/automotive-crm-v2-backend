package pl.detailing.crm.doortodoor.upsert

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorEntity
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Door to Door dostał kierowcę i termin dostarczenia. Dwie rzeczy muszą trzymać
 * się kontraktu niezależnie od tego, co wyśle klient:
 *
 *  - adres jest albo kompletny, albo pusty — samo miasto nikogo nie dowiezie,
 *    a ulica bez miasta nie nadaje się do nawigacji,
 *  - włączona usługa potrzebuje co najmniej JEDNEGO kompletnego adresu; sam
 *    odbiór i sama dostawa to pełnoprawne warianty, nie połowiczne dane,
 *  - kierowca musi należeć do tego samego studia; bez tej kontroli wystarczyło
 *    podać cudze UUID, żeby nazwisko obcego pracownika wróciło w odpowiedzi.
 */
class UpsertDoorToDoorHandlerTest {

    private val doorToDoorRepository: DoorToDoorRepository = mockk {
        every { findByVisitIdAndStudioId(any(), any()) } returns null
        every { save(any<DoorToDoorEntity>()) } answers { firstArg() }
    }
    private val visitRepository: VisitRepository = mockk(relaxed = true)
    private val employeeRepository: EmployeeRepository = mockk()
    private val auditService: AuditService = mockk(relaxed = true)

    private val handler = UpsertDoorToDoorHandler(
        doorToDoorRepository, visitRepository, employeeRepository, auditService
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val otherStudioId = StudioId(UUID.randomUUID())

    @Test
    fun `adres dostarczenia bez miasta jest odrzucany`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { handler.handle(command(deliveryCity = "", deliveryStreet = "ul. Kowalska 12")) }
        }
        assertTrue(ex.message!!.contains("dostarczenia"))
    }

    @Test
    fun `adres odbioru bez ulicy jest odrzucany`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { handler.handle(command(pickupCity = "Warszawa", pickupStreet = "")) }
        }
    }

    @Test
    fun `zlecona usluga bez zadnego adresu jest odrzucana`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                handler.handle(command(
                    enabled = true,
                    pickupCity = "", pickupStreet = "",
                    deliveryCity = "", deliveryStreet = ""
                ))
            }
        }
        assertTrue(ex.message!!.contains("odbioru albo dostarczenia"))
    }

    /* "Odbierzcie sprzed domu, wroce po nie sam" - najczestszy powod, dla ktorego
       wymuszanie adresu dostarczenia bylo bledem. */
    @Test
    fun `sam odbior wystarczy - klient odbierze auto osobiscie`() {
        val result = runBlocking {
            handler.handle(command(enabled = true, deliveryCity = "", deliveryStreet = ""))
        }
        assertEquals("Warszawa", result.pickupAddress.city)
        assertEquals("", result.deliveryAddress.city)
    }

    /* Odwrotnosc: klient przywozi auto sam, ale prosi o odwiezienie. */
    @Test
    fun `sama dostawa wystarczy - klient przywozi auto sam`() {
        val result = runBlocking {
            handler.handle(command(enabled = true, pickupCity = "", pickupStreet = ""))
        }
        assertEquals("", result.pickupAddress.city)
        assertEquals("Warszawa", result.deliveryAddress.city)
    }

    @Test
    fun `wylaczona usluga moze nie miec zadnego adresu`() {
        val result = runBlocking {
            handler.handle(command(
                enabled = false,
                pickupCity = "", pickupStreet = "",
                deliveryCity = "", deliveryStreet = ""
            ))
        }
        assertEquals(false, result.enabled)
    }

    @Test
    fun `kierowca z innego studia jest odrzucany`() {
        val foreign = EmployeeId(UUID.randomUUID())
        every { employeeRepository.findByIdAndStudioId(foreign.value, studioId.value) } returns null

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { handler.handle(command(driverId = foreign)) }
        }
    }

    @Test
    fun `przypisany kierowca zapisuje migawke nazwiska`() {
        val driverId = EmployeeId(UUID.randomUUID())
        every { employeeRepository.findByIdAndStudioId(driverId.value, studioId.value) } returns
            employee(driverId, "Anna", "Zielinska")

        val result = runBlocking { handler.handle(command(driverId = driverId)) }

        assertEquals(driverId, result.driverId)
        assertEquals("Anna Zielinska", result.driverName)
    }

    @Test
    fun `brak kierowcy zostawia puste pola zamiast nazwiska bez przypisania`() {
        val result = runBlocking { handler.handle(command(driverId = null)) }

        assertNull(result.driverId)
        assertNull(result.driverName)
    }

    @Test
    fun `termin dostarczenia w przeszlosci przechodzi - dane bywaja uzupelniane po fakcie`() {
        val yesterday = Instant.now().minusSeconds(86_400)
        val result = runBlocking { handler.handle(command(scheduledAt = yesterday)) }
        assertEquals(yesterday, result.scheduledAt)
    }

    private fun employee(id: EmployeeId, firstName: String, lastName: String) = EmployeeEntity(
        id = id.value,
        studioId = studioId.value,
        userId = null,
        firstName = firstName,
        lastName = lastName,
        phone = null,
        email = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID()
    )

    private fun command(
        enabled: Boolean = true,
        pickupCity: String = "Warszawa",
        pickupStreet: String = "ul. Klonowa 5",
        deliveryCity: String = "Warszawa",
        deliveryStreet: String = "ul. Biurowa 3",
        driverId: EmployeeId? = null,
        scheduledAt: Instant? = null
    ) = UpsertDoorToDoorCommand(
        studioId = studioId,
        visitId = VisitId(UUID.randomUUID()),
        userId = UserId(UUID.randomUUID()),
        userName = "Tester",
        enabled = enabled,
        pickupCity = pickupCity,
        pickupStreet = pickupStreet,
        deliveryCity = deliveryCity,
        deliveryStreet = deliveryStreet,
        notes = null,
        driverId = driverId,
        scheduledAt = scheduledAt
    )
}
