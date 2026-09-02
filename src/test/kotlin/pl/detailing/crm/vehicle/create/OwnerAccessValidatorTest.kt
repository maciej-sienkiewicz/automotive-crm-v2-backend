package pl.detailing.crm.vehicle.create

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.validators.OwnerAccessValidator

/**
 * Cross-tenant reference — współwłaściciele pojazdu.
 *
 * Luka: przy tworzeniu pojazdu sprawdzany był tylko `ownerIds[0]`; każdy kolejny id
 * (np. klient studia B) lądował w `vehicle_owners`, a lista pojazdów pokazywała potem
 * imię i nazwisko cudzego klienta.
 */
class OwnerAccessValidatorTest {

    private val validator = OwnerAccessValidator()
    private val studioA = StudioId.random()

    private fun customer(studio: StudioId = studioA, active: Boolean = true) = mockk<CustomerEntity> {
        every { studioId } returns studio.value
        every { isActive } returns active
    }

    private fun context(ownerIds: List<CustomerId>, owners: List<CustomerEntity?>) = CreateVehicleValidationContext(
        studioId = studioA, ownerIds = ownerIds, licensePlate = null, yearOfProduction = null,
        owners = owners, licensePlateExists = false
    )

    @Test
    fun `a second owner that is not found in this studio is rejected as not found`() {
        val own = CustomerId.random()
        val foreign = CustomerId.random()

        // The studio-scoped lookup returned null for the foreign id.
        assertThrows<EntityNotFoundException> {
            validator.validate(context(listOf(own, foreign), listOf(customer(), null)))
        }
    }

    @Test
    fun `an entity from another studio is rejected even if a lookup slipped it through`() {
        val ids = listOf(CustomerId.random(), CustomerId.random())
        assertThrows<EntityNotFoundException> {
            validator.validate(context(ids, listOf(customer(), customer(studio = StudioId.random()))))
        }
    }

    @Test
    fun `duplicate and empty owner lists are validation errors`() {
        val id = CustomerId.random()
        assertThrows<ValidationException> { validator.validate(context(listOf(id, id), listOf(customer(), customer()))) }
        assertThrows<ValidationException> { validator.validate(context(emptyList(), emptyList())) }
    }

    @Test
    fun `all owners from this studio pass`() {
        val ids = listOf(CustomerId.random(), CustomerId.random())
        assertDoesNotThrow { validator.validate(context(ids, listOf(customer(), customer()))) }
    }
}
