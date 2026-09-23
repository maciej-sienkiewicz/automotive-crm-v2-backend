package pl.detailing.crm.demo

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Kontakty z danych przykładowych po przejściu przez cały seed - w takiej postaci, w jakiej
 * trafiają do bazy.
 *
 * Telefon i e-mail klienta są unikalne w studiu (częściowe indeksy z [pl.detailing.crm.config.DatabaseInitializer]
 * pomijają tylko wartości puste). Powtórzony zastępnik oznacza, że zakładanie piaskownicy
 * wywraca się na INSERT-cie, a okno podglądu do końca czeka na piaskownicę, której nie ma.
 */
class DemoDataContactsTest {

    private val customerRepository = mockk<CustomerRepository>(relaxed = true)
    private val leadRepository = mockk<LeadRepository>(relaxed = true)
    private val communicationLogRepository = mockk<CommunicationLogJpaRepository>(relaxed = true)
    private val appointmentRepository = mockk<AppointmentRepository>(relaxed = true)
    private val visitRepository = mockk<VisitRepository>(relaxed = true)
    private val serviceCategoryRepository = mockk<ServiceCategoryRepository>(relaxed = true)

    private val initializer = DemoDataInitializer(
        appointmentColorRepository = mockk(relaxed = true),
        serviceRepository = mockk(relaxed = true),
        customerRepository = customerRepository,
        customerNoteRepository = mockk(relaxed = true),
        vehicleRepository = mockk(relaxed = true),
        vehicleOwnerRepository = mockk(relaxed = true),
        appointmentRepository = appointmentRepository,
        visitRepository = visitRepository,
        visitCommentRepository = mockk(relaxed = true),
        vehicleNoteRepository = mockk(relaxed = true),
        leadRepository = leadRepository,
        communicationLogRepository = communicationLogRepository,
        serviceCategoryRepository = serviceCategoryRepository,
        categoryServiceAssignmentRepository = mockk(relaxed = true),
        instagramProfileRepository = mockk(relaxed = true),
        studioInstagramProfileRepository = mockk(relaxed = true)
    )

    private class Seeded(
        val customers: List<CustomerEntity>,
        val leads: List<LeadEntity>,
        val logs: List<CommunicationLogEntity>
    )

    private fun seed(contacts: SeedContacts): Seeded {
        val customers = slot<Iterable<CustomerEntity>>()
        val leads = slot<Iterable<LeadEntity>>()
        val logs = slot<Iterable<CommunicationLogEntity>>()
        every { customerRepository.saveAll(capture(customers)) } answers { customers.captured.toList() }
        every { leadRepository.saveAll(capture(leads)) } answers { leads.captured.toList() }
        every { communicationLogRepository.saveAll(capture(logs)) } answers { logs.captured.toList() }
        every { appointmentRepository.save(any()) } answers { firstArg() }
        every { visitRepository.save(any()) } answers { firstArg() }
        every { serviceCategoryRepository.save(any()) } answers { firstArg() }

        initializer.seed(UUID.randomUUID(), UUID.randomUUID(), contacts = contacts, followInstagramProfiles = false)

        return Seeded(customers.captured.toList(), leads.captured.toList(), logs.captured.toList())
    }

    private fun assertUniqueInStudio(values: List<String?>, what: String) {
        val filled = values.filterNotNull().filter { it.isNotEmpty() }
        val repeated = filled.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals(emptyMap<String, Int>(), repeated, "$what powtarza się u kilku klientów")
    }

    @Test
    fun `klienci piaskownicy maja rozne telefony i adresy e-mail`() {
        val seeded = seed(SeedContacts.undeliverable())

        assertTrue(seeded.customers.size > 20, "Seed ma zakładać pełną listę klientów")
        assertUniqueInStudio(seeded.customers.map { it.phone }, "Telefon")
        assertUniqueInStudio(seeded.customers.map { it.email }, "E-mail")
    }

    @Test
    fun `klienci konta demo maja rozne telefony i adresy e-mail`() {
        val seeded = seed(SeedContacts.REALISTIC)

        assertUniqueInStudio(seeded.customers.map { it.phone }, "Telefon")
        assertUniqueInStudio(seeded.customers.map { it.email }, "E-mail")
    }

    @Test
    fun `w danych piaskownicy nie ma adresu, pod ktory da sie cos dostarczyc`() {
        val seeded = seed(SeedContacts.undeliverable())

        val addresses = seeded.customers.flatMap { listOfNotNull(it.phone, it.email) } +
            seeded.leads.map { it.contactIdentifier } +
            seeded.logs.map { it.recipientAddress }

        assertTrue(seeded.leads.isNotEmpty() && seeded.logs.isNotEmpty())
        val deliverable = addresses.filterNot { it.startsWith("+48000") || it.endsWith("@example.com") }
        assertEquals(emptyList<String>(), deliverable)
    }

    @Test
    fun `sms do klienta piaskownicy idzie na ten sam zastepczy numer, ktory ma klient`() {
        val seeded = seed(SeedContacts.undeliverable())

        // Pierwszy klient (Jan Kowalski) i jego SMS o gotowym aucie z dziennika wysyłek.
        val customer = seeded.customers.first()
        val sms = seeded.logs.filter { it.customerId == customer.id && it.recipientAddress.startsWith("+") }

        assertTrue(sms.isNotEmpty())
        assertTrue(sms.all { it.recipientAddress == customer.phone }, "${sms.map { it.recipientAddress }} vs ${customer.phone}")
    }
}
