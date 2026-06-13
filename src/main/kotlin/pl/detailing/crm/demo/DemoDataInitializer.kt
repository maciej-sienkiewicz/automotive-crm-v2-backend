package pl.detailing.crm.demo

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.customer.notes.CustomerNoteEntity
import pl.detailing.crm.customer.notes.CustomerNoteRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentEntity
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryEntity
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.vehicle.infrastructure.*
import pl.detailing.crm.vehicle.notes.VehicleNoteEntity
import pl.detailing.crm.vehicle.notes.VehicleNoteRepository
import pl.detailing.crm.visit.infrastructure.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class DemoDataInitializer(
    private val appointmentColorRepository: AppointmentColorRepository,
    private val serviceRepository: ServiceRepository,
    private val customerRepository: CustomerRepository,
    private val customerNoteRepository: CustomerNoteRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val appointmentRepository: AppointmentRepository,
    private val visitRepository: VisitRepository,
    private val visitCommentRepository: VisitCommentRepository,
    private val vehicleNoteRepository: VehicleNoteRepository,
    private val leadRepository: LeadRepository,
    private val communicationLogRepository: CommunicationLogJpaRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val instagramProfileRepository: InstagramProfileRepository,
    private val studioInstagramProfileRepository: StudioInstagramProfileRepository
) {

    @Transactional
    fun seed(studioId: UUID, userId: UUID) {
        val colors = createColors(studioId, userId)
        val services = createServices(studioId, userId)
        createServiceCategories(studioId, userId, services)
        val customers = createCustomers(studioId, userId)
        createCustomerNotes(studioId, userId, customers)
        val vehicles = createVehicles(studioId, userId, customers)
        createVehicleNotes(studioId, userId, vehicles)
        val pastVisits = createHistoricalVisits(studioId, userId, customers, vehicles, services, colors)
        val inProgressVisits = createInProgressVisits(studioId, userId, customers, vehicles, services, colors)
        createVisitComments(studioId, userId, pastVisits + inProgressVisits)
        createFutureAppointments(studioId, userId, customers, vehicles, services, colors)
        createLeads(studioId, customers, vehicles)
        createCommunicationLogs(studioId, customers, pastVisits + inProgressVisits)
        createInstagramProfiles(studioId, userId)
    }

    private fun createColors(studioId: UUID, userId: UUID): List<AppointmentColorEntity> {
        val now = Instant.now()
        val colorData = listOf(
            "Niebieski Standard" to "#2563EB",
            "Pomarańczowy VIP" to "#F97316",
            "Zielony EKO" to "#16A34A",
            "Czerwony PILNE" to "#DC2626",
            "Fioletowy FLOTA" to "#7C3AED"
        )
        return colorData.map { (name, hex) ->
            AppointmentColorEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                name = name,
                hexColor = hex,
                isActive = true,
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now
            )
        }.also { appointmentColorRepository.saveAll(it) }
    }

    private fun createServices(studioId: UUID, userId: UUID): List<ServiceEntity> {
        val now = Instant.now()
        val serviceData = listOf(
            Triple("Mycie detailingowe + osuszanie", 13900L, 23),
            Triple("Detailing wnętrza kompleksowy", 34900L, 23),
            Triple("Pranie tapicerki - komplet", 49900L, 23),
            Triple("Korekta lakieru 1-etapowa", 69900L, 23),
            Triple("Korekta lakieru 2-etapowa", 109900L, 23),
            Triple("Powłoka ceramiczna IGL Eclipse", 179900L, 23),
            Triple("Ozonowanie kabiny", 17900L, 23),
            Triple("Impregnacja szyb nano", 24900L, 23)
        )
        return serviceData.map { (name, price, vat) ->
            ServiceEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                name = name,
                basePriceNet = price,
                vatRate = vat,
                isActive = true,
                requireManualPrice = false,
                replacesServiceId = null,
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now
            )
        }.also { serviceRepository.saveAll(it) }
    }

    private fun createCustomers(studioId: UUID, userId: UUID): List<CustomerEntity> {
        val now = Instant.now()
        data class CustomerData(
            val firstName: String?,
            val lastName: String?,
            val email: String?,
            val phone: String?,
            val street: String? = null,
            val city: String? = null,
            val postal: String? = null,
            val companyName: String? = null,
            val companyNip: String? = null
        )

        val customerData = listOf(
            CustomerData("Jan", "Kowalski", "jan.kowalski@gmail.com", "+48512345678", "ul. Marszałkowska 15/3", "Warszawa", "00-001"),
            CustomerData("Anna", "Nowak", "anna.nowak@wp.pl", "+48698765432", "ul. Krakowska 7", "Kraków", "30-001"),
            CustomerData("Piotr", "Wiśniewski", "piotr.wisniewski@onet.pl", "+48723456789", "ul. Długa 22", "Gdańsk", "80-001"),
            CustomerData("Katarzyna", "Wójcik", "k.wojcik@interia.pl", "+48601234567", "ul. Poznańska 5/8", "Poznań", "60-001"),
            CustomerData("Marek", "Kowalczyk", "marek.kowalczyk@gmail.com", "+48512987654", "ul. Wrocławska 3", "Wrocław", "50-001"),
            CustomerData("Magdalena", "Kamińska", "m.kaminska@gmail.com", "+48798654321", "al. Jerozolimskie 44", "Warszawa", "00-024"),
            CustomerData("Tomasz", "Lewandowski", "t.lewandowski@wp.pl", "+48501234567", "ul. Floriańska 12", "Kraków", "31-019"),
            CustomerData("Agnieszka", "Zielińska", "agnieszka.z@onet.pl", "+48698234567", "ul. Wały Piastowskie 1", "Gdańsk", "80-855"),
            CustomerData("Michał", "Szymański", "michal.sz@gmail.com", "+48723987654", "ul. Głogowska 28", "Poznań", "60-736"),
            CustomerData("Joanna", "Woźniak", "j.wozniak@interia.pl", "+48601987654", "ul. Świdnicka 6", "Wrocław", "50-066"),
            CustomerData("Paweł", "Dąbrowski", "pawel.d@gmail.com", "+48512654321", "ul. Mokotowska 33", "Warszawa", "00-560"),
            CustomerData("Monika", "Kozłowska", "m.kozlowska@wp.pl", "+48698321456", "ul. Karmelicka 8", "Kraków", "31-128"),
            CustomerData("Krzysztof", "Jankowski", "k.jankowski@gmail.com", "+48723654321", "ul. Grunwaldzka 56", "Gdańsk", "80-241"),
            CustomerData("Ewa", "Mazur", "ewa.mazur@onet.pl", "+48601654321", "ul. Dąbrowskiego 14", "Poznań", "60-838"),
            CustomerData("Andrzej", "Krawczyk", "a.krawczyk@interia.pl", "+48512321654", "ul. Piłsudskiego 9", "Wrocław", "50-048"),
            CustomerData("Barbara", "Piotrowska", "b.piotrowska@gmail.com", "+48698654789", "ul. Puławska 120", "Warszawa", "02-620"),
            CustomerData("Łukasz", "Grabowski", "l.grabowski@wp.pl", "+48723321654", "ul. Rynek Główny 2", "Kraków", "31-008"),
            CustomerData("Natalia", "Pawlak", "natalia.p@gmail.com", "+48601321654", "ul. Heweliusza 11", "Gdańsk", "80-890"),
            CustomerData("Rafał", "Michalski", "r.michalski@onet.pl", "+48512789654", "ul. Ratajczaka 22", "Poznań", "61-726"),
            CustomerData("Sylwia", "Nowakowska", "s.nowakowska@interia.pl", "+48698789654", "ul. Śleźańska 3", "Wrocław", "54-118"),
            CustomerData(null, null, "biuro@autocars.pl", "+48225001234", companyName = "AutoCars Premium Sp. z o.o.", companyNip = "7272345678"),
            CustomerData(null, null, "transport@transpol.pl", "+48122345678", companyName = "TRANS-POL Usługi Sp.j.", companyNip = "5262987654"),
            CustomerData("Grzegorz", "Adamczyk", "g.adamczyk@gmail.com", "+48723789654", "ul. Kazimierzowska 31", "Warszawa", "02-572"),
            CustomerData("Karolina", "Dudek", "k.dudek@wp.pl", "+48601789654", "ul. Basztowa 17", "Kraków", "31-143"),
            CustomerData("Damian", "Zając", "d.zajac@onet.pl", "+48512456789", "ul. Podmłyńska 7", "Gdańsk", "80-885")
        )

        return customerData.map { d ->
            CustomerEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                firstName = d.firstName,
                lastName = d.lastName,
                email = d.email,
                phone = d.phone,
                homeAddressStreet = d.street,
                homeAddressCity = d.city,
                homeAddressPostalCode = d.postal,
                homeAddressCountry = if (d.street != null) "Polska" else null,
                companyName = d.companyName,
                companyNip = d.companyNip,
                companyRegon = null,
                companyAddressStreet = null,
                companyAddressCity = null,
                companyAddressPostalCode = null,
                companyAddressCountry = null,
                isActive = true,
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now
            )
        }.also { customerRepository.saveAll(it) }
    }

    private fun createCustomerNotes(studioId: UUID, userId: UUID, customers: List<CustomerEntity>) {
        val now = Instant.now()
        val notes = listOf(
            0 to "Klient preferuje kontakt telefoniczny. Lubi auta utrzymywane w idealnym stanie.",
            0 to "Prosi o powiadomienie SMS gdy auto gotowe do odbioru.",
            2 to "Posiada dwa auta – Porsche 911 i BMW 5. Przy każdym aucie inna specyfika pracy.",
            2 to "Bardzo wymagający – zwraca uwagę na każdy detal. Polecił już 3 klientów.",
            6 to "Stały klient od 2 lat. Zawsze wykupuje pakiet full-detailing.",
            10 to "Klient biznesowy – faktura VAT obowiązkowa. Preferuje odbiór po 17:00.",
            20 to "Flota 6 aut – wymaga stałej ceny i faktury zbiorczej co miesiąc.",
            16 to "Interesuje się powłokami ceramicznymi. Warto pokazać portfolio.",
            4 to "Prosi o zniżkę przy kolejnej wizycie – przyznano -5% na korekty."
        )
        val noteEntities = notes.map { (idx, content) ->
            val customer = customers[idx]
            CustomerNoteEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                customerId = customer.id,
                content = content,
                createdBy = userId,
                createdByName = "Jan Detailer",
                createdAt = now.minus(30, ChronoUnit.DAYS),
                updatedAt = now.minus(30, ChronoUnit.DAYS)
            )
        }
        customerNoteRepository.saveAll(noteEntities)
    }

    private fun createVehicles(studioId: UUID, userId: UUID, customers: List<CustomerEntity>): List<VehicleEntity> {
        val now = Instant.now()
        data class VehicleData(
            val customerIdx: Int,
            val brand: String,
            val model: String,
            val year: Int,
            val plate: String,
            val color: String,
            val paint: String,
            val mileage: Int
        )

        val vehicleData = listOf(
            VehicleData(0, "BMW", "X5 xDrive40i", 2021, "PO12345", "Czarny Metalik", "Lakier Metaliczny", 45000),
            VehicleData(1, "Audi", "A6 Avant 3.0 TDI", 2020, "WA23456", "Biały Perłowy", "Lakier Perłowy", 62000),
            VehicleData(2, "Mercedes-Benz", "C220d AMG Line", 2022, "GD34567", "Srebrny Metalik", "Lakier Metaliczny", 28000),
            VehicleData(2, "Porsche", "911 Carrera 4S", 2023, "GD99999", "Czerwony Karmin", "Lakier Błyszczący", 8000),
            VehicleData(3, "Volkswagen", "Golf 8 GTI", 2019, "KR45678", "Szary Nardo", "Lakier Matowy", 78000),
            VehicleData(4, "BMW", "5 Series 530i", 2021, "WR56789", "Granatowy Metalik", "Lakier Metaliczny", 38000),
            VehicleData(5, "Toyota", "Camry 2.5 Hybrid", 2020, "PO67890", "Biały Perłowy", "Lakier Perłowy", 55000),
            VehicleData(6, "Porsche", "Cayenne S", 2022, "WA78901", "Czarny Połysk", "Lakier Błyszczący", 19000),
            VehicleData(7, "Audi", "Q5 45 TFSI", 2021, "GD89012", "Srebrny Metalik", "Lakier Metaliczny", 41000),
            VehicleData(8, "Mercedes-Benz", "S 500 4MATIC", 2023, "KR90123", "Czarny Obsidian", "Lakier Błyszczący", 12000),
            VehicleData(9, "BMW", "3 Series 320d", 2020, "WR01234", "Biały Alpejski", "Lakier Błyszczący", 66000),
            VehicleData(10, "Ford", "Mustang GT 5.0", 2019, "PO13579", "Czerwony Wyścigowy", "Lakier Błyszczący", 34000),
            VehicleData(11, "Volvo", "XC60 T6 Recharge", 2022, "WA24680", "Srebrny Metalik", "Lakier Metaliczny", 23000),
            VehicleData(12, "Volkswagen", "Passat Elegance 2.0", 2021, "GD35791", "Szary Platynowy", "Lakier Metaliczny", 48000),
            VehicleData(13, "Honda", "CR-V e:HEV", 2020, "KR46802", "Biały Platynowy", "Lakier Perłowy", 52000),
            VehicleData(14, "Mazda", "6 Skyactiv-G", 2019, "WR57913", "Czerwony Dusza", "Lakier Błyszczący", 71000),
            VehicleData(15, "Audi", "A4 Allroad 2.0 TFSI", 2022, "PO68024", "Czarny Metalik", "Lakier Metaliczny", 16000),
            VehicleData(16, "BMW", "M135i xDrive", 2021, "WA79135", "Biały Alpejski", "Lakier Błyszczący", 29000),
            VehicleData(17, "Mercedes-Benz", "A250 4MATIC AMG", 2020, "GD80246", "Niebieski Denim", "Lakier Błyszczący", 44000),
            VehicleData(18, "Kia", "Stinger GT 3.3 T-GDI", 2022, "KR91357", "Srebrny Moonscape", "Lakier Metaliczny", 21000),
            VehicleData(19, "Lexus", "RX 450h F SPORT", 2021, "WR02468", "Czarny Obsidian", "Lakier Połysk", 33000),
            VehicleData(20, "Toyota", "Land Cruiser 300 GR Sport", 2020, "WA11111", "Czarny Metalik", "Lakier Metaliczny", 58000),
            VehicleData(21, "Volkswagen", "Transporter T6.1", 2021, "WA22222", "Biały", "Lakier Błyszczący", 87000),
            VehicleData(22, "Subaru", "Outback 2.5i Adventure", 2022, "PO33333", "Zielony Wilderness", "Lakier Metaliczny", 26000),
            VehicleData(23, "Nissan", "Qashqai e-POWER", 2021, "GD44444", "Srebrny Magnetyczny", "Lakier Metaliczny", 37000),
            VehicleData(24, "Skoda", "Octavia RS 2.0 TSI", 2020, "KR55555", "Szary Metalik", "Lakier Metaliczny", 59000)
        )

        val vehicles = vehicleData.map { d ->
            VehicleEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                licensePlate = d.plate,
                brand = d.brand,
                model = d.model,
                yearOfProduction = d.year,
                color = d.color,
                paintType = d.paint,
                currentMileage = d.mileage,
                status = VehicleStatus.ACTIVE,
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now
            )
        }
        vehicleRepository.saveAll(vehicles)

        val ownerEntities = vehicleData.mapIndexed { i, d ->
            VehicleOwnerEntity(
                id = VehicleOwnerKey(
                    vehicleId = vehicles[i].id,
                    customerId = customers[d.customerIdx].id
                ),
                ownershipRole = OwnershipRole.PRIMARY,
                assignedAt = now
            )
        }
        vehicleOwnerRepository.saveAll(ownerEntities)

        return vehicles
    }

    private fun createHistoricalVisits(
        studioId: UUID,
        userId: UUID,
        customers: List<CustomerEntity>,
        vehicles: List<VehicleEntity>,
        services: List<ServiceEntity>,
        colors: List<AppointmentColorEntity>
    ): List<VisitEntity> {
        val now = Instant.now()
        val createdVisits = mutableListOf<VisitEntity>()

        data class VisitSpec(
            val customerIdx: Int,
            val vehicleIdx: Int,
            val daysAgo: Long,
            val durationHours: Long,
            val serviceIndices: List<Int>,
            val colorIdx: Int,
            val title: String?,
            val notes: String?
        )

        val specs = listOf(
            VisitSpec(0, 0, 175, 6, listOf(3, 5), 1, "Korekta + ceramika BMW X5", "Klient bardzo zadowolony z efektów"),
            VisitSpec(1, 1, 168, 5, listOf(1, 2), 0, "Detailing wnętrza Audi A6", null),
            VisitSpec(2, 2, 162, 4, listOf(0, 3), 2, "Mycie + korekta Mercedes", "Drobne rysy na drzwiach"),
            VisitSpec(2, 3, 155, 8, listOf(4, 5, 7), 1, "Full detail Porsche 911", "Klient bardzo wymagający – efekt idealny"),
            VisitSpec(3, 4, 148, 3, listOf(0, 1), 0, null, null),
            VisitSpec(4, 5, 141, 7, listOf(3, 5), 1, "Korekta + ceramika BMW 5", null),
            VisitSpec(5, 6, 135, 4, listOf(0, 2), 2, "Pranie tapicerki Toyota", "Tapicerka skórzana – dodatkowe preparaty"),
            VisitSpec(6, 7, 128, 6, listOf(4, 5), 1, "2-etapowa korekta Porsche Cayenne", null),
            VisitSpec(7, 8, 121, 3, listOf(0, 7), 0, "Mycie + szyby Audi Q5", null),
            VisitSpec(8, 9, 115, 8, listOf(3, 4, 5), 1, "Full detailing Mercedes S-Klasa", "Lakier w świetnym stanie wejściowym"),
            VisitSpec(9, 10, 108, 4, listOf(0, 1), 0, null, null),
            VisitSpec(10, 11, 102, 5, listOf(2, 3), 3, "Pranie + korekta Ford Mustang", "Wnętrze bardzo zaniedbane"),
            VisitSpec(11, 12, 95, 4, listOf(0, 6), 0, "Ozonowanie Volvo XC60", null),
            VisitSpec(12, 13, 89, 6, listOf(1, 3, 7), 2, "Detailing + korekta VW Passat", null),
            VisitSpec(13, 14, 82, 3, listOf(0), 0, null, null),
            VisitSpec(14, 15, 76, 5, listOf(3, 5), 1, "Korekta + ceramika Mazda 6", "Lakier czerwony wymaga szczególnej uwagi"),
            VisitSpec(15, 16, 69, 7, listOf(4, 5), 1, "Full korekta Audi A4 Allroad", null),
            VisitSpec(16, 17, 63, 4, listOf(0, 1), 2, "Detailing wnętrza BMW M135i", null),
            VisitSpec(17, 18, 56, 3, listOf(0, 7), 0, "Mycie + szyby Mercedes A-Klasa", null),
            VisitSpec(18, 19, 49, 6, listOf(3, 5), 1, "Korekta + ceramika Kia Stinger", "Nowy klient – referral od Pana Kowalskiego"),
            VisitSpec(19, 20, 43, 5, listOf(0, 2, 6), 2, "Pranie + ozon Lexus RX", null),
            VisitSpec(20, 21, 36, 4, listOf(0, 1), 4, "Flota - Toyota Land Cruiser", "Faktura VAT – AutoCars Premium"),
            VisitSpec(21, 22, 30, 3, listOf(0), 4, "Flota - VW Transporter #1", "Faktura VAT – TRANS-POL"),
            VisitSpec(0, 0, 28, 4, listOf(0, 7), 0, "Serwis okresowy BMW X5", "Klient regularny – co 3 miesiące"),
            VisitSpec(22, 23, 21, 5, listOf(1, 3), 2, "Detailing + korekta Subaru", null),
            VisitSpec(23, 24, 14, 3, listOf(0, 2), 0, "Mycie + pranie Nissan Qashqai", null),
            VisitSpec(2, 3, 12, 8, listOf(4, 5, 7), 1, "2-etap + ceramika Porsche 911", "Druga wizyta tego klienta z Porsche"),
            VisitSpec(4, 5, 10, 6, listOf(3, 5), 1, "Korekta + ceramika BMW 5", "Odświeżenie ceramiki"),
            VisitSpec(6, 7, 9, 4, listOf(0, 6), 0, "Mycie + ozon Porsche Cayenne", null),
            VisitSpec(8, 9, 7, 3, listOf(0, 1), 2, "Detailing Mercedes S-Klasa", null),
            VisitSpec(24, 25, 5, 5, listOf(1, 2, 3), 0, "Pełny detailing Skoda Octavia", "Nowy klient"),
            VisitSpec(10, 11, 4, 4, listOf(0, 7), 0, "Mycie + szyby Ford Mustang", null),
            VisitSpec(12, 13, 3, 6, listOf(3, 4), 2, "Korekta VW Passat", null),
            VisitSpec(14, 15, 2, 3, listOf(0), 0, "Mycie Mazda 6", null),
            VisitSpec(16, 17, 1, 5, listOf(1, 3), 1, "Detailing + korekta BMW M135i", null)
        )

        specs.forEachIndexed { idx, spec ->
            val visitNumber = "VIS-2025-${(idx + 1).toString().padStart(5, '0')}"
            val scheduledDate = now.minus(spec.daysAgo, ChronoUnit.DAYS)
            val completionDate = scheduledDate.plus(spec.durationHours, ChronoUnit.HOURS)
            val pickupDate = completionDate.plus(1, ChronoUnit.HOURS)
            val customer = customers[spec.customerIdx]
            val vehicle = vehicles[spec.vehicleIdx]
            val color = colors[spec.colorIdx]

            val appointmentId = UUID.randomUUID()
            val appointment = AppointmentEntity(
                id = appointmentId,
                studioId = studioId,
                customerId = customer.id,
                vehicleId = vehicle.id,
                appointmentTitle = spec.title,
                appointmentColorId = color.id,
                isAllDay = false,
                startDateTime = scheduledDate,
                endDateTime = completionDate,
                status = AppointmentStatus.CONVERTED,
                note = spec.notes,
                sendReminderSms = false,
                createdBy = userId,
                updatedBy = userId,
                createdAt = scheduledDate.minus(7, ChronoUnit.DAYS),
                updatedAt = scheduledDate
            )

            val lineItems = spec.serviceIndices.map { sIdx ->
                val svc = services[sIdx]
                val finalNet = svc.basePriceNet
                val finalGross = finalNet + (finalNet * svc.vatRate / 100)
                AppointmentLineItemEntity(
                    id = null,
                    appointment = appointment,
                    serviceId = svc.id,
                    serviceName = svc.name,
                    basePriceNet = svc.basePriceNet,
                    vatRate = svc.vatRate,
                    adjustmentType = AdjustmentType.PERCENT,
                    adjustmentValue = 0L,
                    finalPriceNet = finalNet,
                    finalPriceGross = finalGross,
                    customNote = null
                )
            }
            appointment.lineItems = lineItems.toMutableList()
            appointmentRepository.save(appointment)

            val visitEntity = VisitEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                visitNumber = visitNumber,
                customerId = customer.id,
                vehicleId = vehicle.id,
                appointmentId = appointmentId,
                appointmentColorId = color.id,
                title = spec.title,
                brandSnapshot = vehicle.brand,
                modelSnapshot = vehicle.model,
                licensePlateSnapshot = vehicle.licensePlate,
                vinSnapshot = null,
                yearOfProductionSnapshot = vehicle.yearOfProduction,
                colorSnapshot = vehicle.color,
                status = VisitStatus.COMPLETED,
                scheduledDate = scheduledDate,
                estimatedCompletionDate = completionDate,
                actualCompletionDate = completionDate,
                pickupDate = pickupDate,
                mileageAtArrival = vehicle.currentMileage - (spec.daysAgo / 10),
                keysHandedOver = true,
                documentsHandedOver = true,
                inspectionNotes = spec.notes,
                technicalNotes = null,
                isHandedOffByOtherPerson = false,
                contactPersonFirstName = null,
                contactPersonLastName = null,
                contactPersonPhone = null,
                contactPersonEmail = null,
                damageMapFileId = null,
                smsReminderSuppressed = false,
                createdBy = userId,
                updatedBy = userId,
                createdAt = scheduledDate.minus(7, ChronoUnit.DAYS),
                updatedAt = pickupDate
            )

            val visitServiceItems = spec.serviceIndices.map { sIdx ->
                val svc = services[sIdx]
                val finalNet = svc.basePriceNet
                val finalGross = finalNet + (finalNet * svc.vatRate / 100)
                VisitServiceItemEntity(
                    id = UUID.randomUUID(),
                    visit = visitEntity,
                    serviceId = svc.id,
                    serviceName = svc.name,
                    basePriceNet = svc.basePriceNet,
                    vatRate = svc.vatRate,
                    adjustmentType = AdjustmentType.PERCENT,
                    adjustmentValue = 0L,
                    finalPriceNet = finalNet,
                    finalPriceGross = finalGross,
                    status = VisitServiceStatus.CONFIRMED,
                    pendingOperation = null,
                    confirmedSnapshot = null,
                    customNote = null,
                    createdAt = scheduledDate,
                    confirmedAt = scheduledDate,
                    pendingAt = null
                )
            }
            visitEntity.serviceItems = visitServiceItems.toMutableList()
            visitRepository.save(visitEntity)
            createdVisits.add(visitEntity)
        }
        return createdVisits
    }

    private fun createInProgressVisits(
        studioId: UUID,
        userId: UUID,
        customers: List<CustomerEntity>,
        vehicles: List<VehicleEntity>,
        services: List<ServiceEntity>,
        colors: List<AppointmentColorEntity>
    ): List<VisitEntity> {
        val now = Instant.now()
        val createdVisits = mutableListOf<VisitEntity>()
        val startOfToday = now.truncatedTo(ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS)

        data class InProgressSpec(
            val customerIdx: Int,
            val vehicleIdx: Int,
            val startHoursAgo: Long,
            val serviceIndices: List<Int>,
            val colorIdx: Int,
            val title: String,
            val notes: String?
        )

        val specs = listOf(
            InProgressSpec(5, 6, 3, listOf(4, 5), 1, "2-etap + ceramika Porsche Cayenne", "Korekta lakieru w toku"),
            InProgressSpec(8, 9, 5, listOf(3, 5), 1, "Korekta lakieru Mercedes S-Klasa", "Klient chce efekt showroom"),
            InProgressSpec(15, 16, 1, listOf(1, 2, 7), 2, "Detailing wnętrza + pranie Audi A4", null)
        )

        specs.forEachIndexed { idx, spec ->
            val visitNumber = "VIS-2025-${(36 + idx).toString().padStart(5, '0')}"
            val scheduledDate = startOfToday.minus(spec.startHoursAgo, ChronoUnit.HOURS)
            val estimatedCompletion = now.plus(4 - idx.toLong(), ChronoUnit.HOURS)
            val customer = customers[spec.customerIdx]
            val vehicle = vehicles[spec.vehicleIdx]
            val color = colors[spec.colorIdx]

            val appointmentId = UUID.randomUUID()
            val appointment = AppointmentEntity(
                id = appointmentId,
                studioId = studioId,
                customerId = customer.id,
                vehicleId = vehicle.id,
                appointmentTitle = spec.title,
                appointmentColorId = color.id,
                isAllDay = false,
                startDateTime = scheduledDate,
                endDateTime = estimatedCompletion,
                status = AppointmentStatus.CONVERTED,
                note = spec.notes,
                sendReminderSms = false,
                createdBy = userId,
                updatedBy = userId,
                createdAt = scheduledDate.minus(3, ChronoUnit.DAYS),
                updatedAt = scheduledDate
            )

            val lineItems = spec.serviceIndices.map { sIdx ->
                val svc = services[sIdx]
                val finalNet = svc.basePriceNet
                val finalGross = finalNet + (finalNet * svc.vatRate / 100)
                AppointmentLineItemEntity(
                    id = null,
                    appointment = appointment,
                    serviceId = svc.id,
                    serviceName = svc.name,
                    basePriceNet = svc.basePriceNet,
                    vatRate = svc.vatRate,
                    adjustmentType = AdjustmentType.PERCENT,
                    adjustmentValue = 0L,
                    finalPriceNet = finalNet,
                    finalPriceGross = finalGross,
                    customNote = null
                )
            }
            appointment.lineItems = lineItems.toMutableList()
            appointmentRepository.save(appointment)

            val visitEntity = VisitEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                visitNumber = visitNumber,
                customerId = customer.id,
                vehicleId = vehicle.id,
                appointmentId = appointmentId,
                appointmentColorId = color.id,
                title = spec.title,
                brandSnapshot = vehicle.brand,
                modelSnapshot = vehicle.model,
                licensePlateSnapshot = vehicle.licensePlate,
                vinSnapshot = null,
                yearOfProductionSnapshot = vehicle.yearOfProduction,
                colorSnapshot = vehicle.color,
                status = VisitStatus.IN_PROGRESS,
                scheduledDate = scheduledDate,
                estimatedCompletionDate = estimatedCompletion,
                actualCompletionDate = null,
                pickupDate = null,
                mileageAtArrival = vehicle.currentMileage.toLong(),
                keysHandedOver = true,
                documentsHandedOver = true,
                inspectionNotes = spec.notes,
                technicalNotes = "Praca w trakcie",
                isHandedOffByOtherPerson = false,
                contactPersonFirstName = null,
                contactPersonLastName = null,
                contactPersonPhone = null,
                contactPersonEmail = null,
                damageMapFileId = null,
                smsReminderSuppressed = false,
                createdBy = userId,
                updatedBy = userId,
                createdAt = scheduledDate.minus(3, ChronoUnit.DAYS),
                updatedAt = scheduledDate
            )

            val visitServiceItems = spec.serviceIndices.map { sIdx ->
                val svc = services[sIdx]
                val finalNet = svc.basePriceNet
                val finalGross = finalNet + (finalNet * svc.vatRate / 100)
                VisitServiceItemEntity(
                    id = UUID.randomUUID(),
                    visit = visitEntity,
                    serviceId = svc.id,
                    serviceName = svc.name,
                    basePriceNet = svc.basePriceNet,
                    vatRate = svc.vatRate,
                    adjustmentType = AdjustmentType.PERCENT,
                    adjustmentValue = 0L,
                    finalPriceNet = finalNet,
                    finalPriceGross = finalGross,
                    status = VisitServiceStatus.CONFIRMED,
                    pendingOperation = null,
                    confirmedSnapshot = null,
                    customNote = null,
                    createdAt = scheduledDate,
                    confirmedAt = scheduledDate,
                    pendingAt = null
                )
            }
            visitEntity.serviceItems = visitServiceItems.toMutableList()
            visitRepository.save(visitEntity)
            createdVisits.add(visitEntity)
        }
        return createdVisits
    }

    private fun createFutureAppointments(
        studioId: UUID,
        userId: UUID,
        customers: List<CustomerEntity>,
        vehicles: List<VehicleEntity>,
        services: List<ServiceEntity>,
        colors: List<AppointmentColorEntity>
    ) {
        val now = Instant.now()
        val startOfTomorrow = now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS)

        data class FutureSpec(
            val customerIdx: Int,
            val vehicleIdx: Int,
            val daysFromNow: Long,
            val startHour: Long,
            val durationHours: Long,
            val serviceIndices: List<Int>,
            val colorIdx: Int,
            val title: String?,
            val note: String?
        )

        val specs = listOf(
            FutureSpec(0, 0, 1, 9, 6, listOf(3, 5), 1, "Korekta 1-etap + ceramika BMW X5", "Klient regularny"),
            FutureSpec(2, 3, 2, 10, 8, listOf(4, 5, 7), 1, "Full detail Porsche 911", "Trzecia wizyta z Porsche"),
            FutureSpec(7, 8, 3, 8, 4, listOf(0, 1), 0, "Mycie + detailing Audi Q5", null),
            FutureSpec(11, 12, 5, 9, 5, listOf(3, 7), 2, "Korekta + szyby Volvo XC60", null),
            FutureSpec(20, 21, 7, 8, 3, listOf(0), 4, "Flota Toyota - miesięczny serwis", "Faktura VAT"),
            FutureSpec(21, 22, 7, 11, 3, listOf(0), 4, "Flota VW - miesięczny serwis", "Faktura VAT"),
            FutureSpec(17, 18, 10, 9, 6, listOf(1, 3, 5), 1, "Detailing + korekta + ceramika Kia", "Nowy pakiet VIP"),
            FutureSpec(3, 4, 14, 10, 4, listOf(0, 2), 2, "Pranie tapicerki VW Golf", "Klient z polecenia")
        )

        specs.forEach { spec ->
            val startDateTime = startOfTomorrow
                .plus(spec.daysFromNow - 1, ChronoUnit.DAYS)
                .plus(spec.startHour - 9, ChronoUnit.HOURS)
            val endDateTime = startDateTime.plus(spec.durationHours, ChronoUnit.HOURS)
            val customer = customers[spec.customerIdx]
            val vehicle = vehicles[spec.vehicleIdx]
            val color = colors[spec.colorIdx]

            val appointment = AppointmentEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                customerId = customer.id,
                vehicleId = vehicle.id,
                appointmentTitle = spec.title,
                appointmentColorId = color.id,
                isAllDay = false,
                startDateTime = startDateTime,
                endDateTime = endDateTime,
                status = AppointmentStatus.CREATED,
                note = spec.note,
                sendReminderSms = true,
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now
            )

            val lineItems = spec.serviceIndices.map { sIdx ->
                val svc = services[sIdx]
                val finalNet = svc.basePriceNet
                val finalGross = finalNet + (finalNet * svc.vatRate / 100)
                AppointmentLineItemEntity(
                    id = null,
                    appointment = appointment,
                    serviceId = svc.id,
                    serviceName = svc.name,
                    basePriceNet = svc.basePriceNet,
                    vatRate = svc.vatRate,
                    adjustmentType = AdjustmentType.PERCENT,
                    adjustmentValue = 0L,
                    finalPriceNet = finalNet,
                    finalPriceGross = finalGross,
                    customNote = null
                )
            }
            appointment.lineItems = lineItems.toMutableList()
            appointmentRepository.save(appointment)
        }
    }

    private fun createInstagramProfiles(studioId: UUID, userId: UUID) {
        val now = Instant.now()

        data class ProfileData(
            val username: String,
            val followers: Int,
            val following: Int,
            val mediaCount: Int,
            val bio: String,
            val isVerified: Boolean,
            val isBusiness: Boolean,
            val reelsCount: Int
        )

        val profiles = listOf(
            ProfileData(
                username = "autopodrobku",
                followers = 48200,
                following = 312,
                mediaCount = 847,
                bio = "🚗 Detailing | PPF | Folie\n📍 Warszawa\n✉️ kontakt@autopodrobku.pl",
                isVerified = false,
                isBusiness = true,
                reelsCount = 203
            ),
            ProfileData(
                username = "detailingmasterspl",
                followers = 31500,
                following = 198,
                mediaCount = 612,
                bio = "💎 Premium Car Detailing\n🛡️ Ceramic | PPF | Correction\n📍 Kraków • Warszawa",
                isVerified = false,
                isBusiness = true,
                reelsCount = 145
            ),
            ProfileData(
                username = "carspa_gdansk",
                followers = 12800,
                following = 445,
                mediaCount = 389,
                bio = "🔵 Car Spa Gdańsk\n✨ Detailing • Pranie • Korekty\n📞 Umów wizytę w bio",
                isVerified = false,
                isBusiness = true,
                reelsCount = 87
            )
        )

        profiles.forEach { p ->
            val existingProfile = instagramProfileRepository.findByUsername(p.username)
            val profileEntity = existingProfile ?: InstagramProfileEntity(
                id = UUID.randomUUID(),
                username = p.username,
                instagramUserId = null,
                apiError = false,
                followerCount = p.followers,
                followingCount = p.following,
                mediaCount = p.mediaCount,
                biography = p.bio,
                externalUrl = null,
                hasContactData = true,
                isVerified = p.isVerified,
                isBusiness = p.isBusiness,
                accountType = 3,
                category = "Automotive Service",
                hasHighlightReels = true,
                totalClipsCount = p.reelsCount,
                isPrivate = false,
                detailsLastSyncedAt = now.minus(1, ChronoUnit.DAYS),
                createdAt = now,
                updatedAt = now
            ).also { instagramProfileRepository.save(it) }

            if (!studioInstagramProfileRepository.existsByStudioIdAndProfileId(studioId, profileEntity.id)) {
                studioInstagramProfileRepository.save(
                    StudioInstagramProfileEntity(
                        id = UUID.randomUUID(),
                        studioId = studioId,
                        profileId = profileEntity.id,
                        status = InstagramProfileStatus.ACTIVE,
                        addedByUserId = userId,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
    }

    private fun createVisitComments(studioId: UUID, userId: UUID, visits: List<VisitEntity>) {
        val now = Instant.now()
        val authorName = "Jan Detailer"

        data class CommentSpec(val visitIdx: Int, val type: CommentType, val content: String, val daysAgoOffset: Long)

        val specs = listOf(
            CommentSpec(0, CommentType.INTERNAL, "Korekta lakieru zakończona – efekt ★★★★★. Klient dopytywał o ceramikę na kolejną wizytę.", 0),
            CommentSpec(0, CommentType.FOR_CUSTOMER, "Dziękujemy za wizytę! Ceramika aktywuje się przez 24h – proszę nie myć auta przez ten czas.", 0),
            CommentSpec(1, CommentType.INTERNAL, "Tapicerka skórzana wymagała dodatkowego odtłuszczacza. Użyto Gyeon Leather Cleaner.", 0),
            CommentSpec(2, CommentType.INTERNAL, "Drobna rysa na drzwiach lewych – poinformowano klienta, usunięto w trakcie korekty.", 0),
            CommentSpec(3, CommentType.FOR_CUSTOMER, "Porsche wyjechało w idealnym stanie. Zapraszamy na serwis ceramiki za 12 miesięcy.", 0),
            CommentSpec(3, CommentType.INTERNAL, "Lakier w stanie wzorowym – scratch resistance 9/10 po korekcie. Zdjęcia przed/po w galerii.", 0),
            CommentSpec(5, CommentType.INTERNAL, "BMW 5 – ceramika nałożona w 3 warstwach. Kąt kontaktowy wody >115°. Odbiór bez zastrzeżeń.", 0),
            CommentSpec(7, CommentType.INTERNAL, "Cayenne – korekta 2-etapowa. Defekty usuniętę w 95%. Klient zarezerwował już kolejną wizytę.", 0),
            CommentSpec(9, CommentType.FOR_CUSTOMER, "Mercedes S-Klasa gotowy do odbioru. Efekt showroom osiągnięty. Ceramika aktywna.", 0),
            CommentSpec(9, CommentType.INTERNAL, "Lakier S-Klasy w bardzo dobrym stanie wejściowym. Korekta 1-etapowa + 4 warstwy ceramiki.", 0),
            CommentSpec(11, CommentType.INTERNAL, "Wnętrze Mustanga było silnie zaniedbane – plamy z kawy na fotelach. Wymagało 2x pranie.", 0),
            CommentSpec(14, CommentType.INTERNAL, "Mazda 6 – lakier czerwony wymagał szczególnej uwagi przy polerze. Użyto Rupes Bigfoot Nano.", 0),
            CommentSpec(19, CommentType.INTERNAL, "Kia Stinger – nowy klient z polecenia od Pana Kowalskiego. Bardzo zadowolony z efektu.", 0),
            CommentSpec(19, CommentType.FOR_CUSTOMER, "Ceramika na Kii Stinger aplikowana pomyślnie. Pełna hydrofobowość od jutra. Dziękujemy!", 0),
            CommentSpec(23, CommentType.INTERNAL, "BMW X5 – serwis pogwarancyjny ceramiki. Uzupełniono warstwę topcoat. Stan: idealny.", 0),
            CommentSpec(27, CommentType.INTERNAL, "BMW 5 – odświeżenie ceramiki. Klient zgłosił mikro rysy po myjni automatycznej. Usunięto.", 0),
            CommentSpec(30, CommentType.INTERNAL, "Skoda RS – nowy klient. Wnętrze zabudowane foliami sportowymi, wymagało specjalnych preparatów.", 0),
            CommentSpec(34, CommentType.INTERNAL, "BMW M135i – lakier biały. Korekta 1-etapowa przyniosła efekt 88% usunięcia defektów.", 0),
            CommentSpec(35, CommentType.INTERNAL, "Korekta lakieru Porsche Cayenne w toku. Etap 1 ukończony – przechodzę do etapu 2.", 0),
            CommentSpec(35, CommentType.FOR_CUSTOMER, "Pana Cayenne jest u nas w opracowaniu. Przewidywany odbiór dziś ok. 18:00.", 0),
            CommentSpec(36, CommentType.INTERNAL, "Mercedes S-Klasa – klient poprosił o dodatkową warstwę topcoat. Aplikuję.", 0),
            CommentSpec(37, CommentType.INTERNAL, "Audi A4 – tapicerka w bardzo dobrym stanie. Pranie uzupełniające wystarczyło.", 0)
        )

        val entities = specs.mapNotNull { spec ->
            val visit = visits.getOrNull(spec.visitIdx) ?: return@mapNotNull null
            val createdAt = visit.scheduledDate.plus(spec.daysAgoOffset, ChronoUnit.HOURS)
            VisitCommentEntity(
                id = UUID.randomUUID(),
                visitId = visit.id,
                type = spec.type,
                content = spec.content,
                isDeleted = false,
                createdBy = userId,
                createdByName = authorName,
                createdAt = createdAt,
                updatedBy = null,
                updatedByName = null,
                updatedAt = null,
                deletedBy = null,
                deletedByName = null,
                deletedAt = null
            )
        }
        visitCommentRepository.saveAll(entities)
    }

    private fun createVehicleNotes(studioId: UUID, userId: UUID, vehicles: List<VehicleEntity>) {
        val now = Instant.now()
        val authorName = "Jan Detailer"

        data class VehicleNoteSpec(val vehicleIdx: Int, val content: String, val daysAgo: Long)

        val specs = listOf(
            VehicleNoteSpec(0, "BMW X5 – powłoka ceramiczna IGL Eclipse aplikowana 15.11.2024. Następna inspekcja: listopad 2025.", 180),
            VehicleNoteSpec(0, "Lakier w doskonałym stanie. Brak zarysowań głębszych niż clear coat.", 28),
            VehicleNoteSpec(1, "Audi A6 – tapicerka jasna beżowa, wymaga szczególnej ostrożności przy praniu.", 168),
            VehicleNoteSpec(3, "Porsche 911 – lakier Karmin bardzo wrażliwy na zarysowania. Używać tylko najdelikatniejszych pad.", 155),
            VehicleNoteSpec(3, "Ceramika 2-warstwowa aplikowana 10.01.2025. Klient pyta o topcoat przy kolejnej wizycie.", 12),
            VehicleNoteSpec(5, "BMW 5 – ceramika aplikowana dwukrotnie, warstwa bazowa + topcoat. Efekt perfekcyjny.", 141),
            VehicleNoteSpec(7, "Porsche Cayenne – klient przyjeżdża zawsze z własną glinką do mycia. Przechowywana w schowku.", 128),
            VehicleNoteSpec(9, "Mercedes S-Klasa – właściciel wymaga protokołu odbioru z każdą wizytą. Obligatoryjnie.", 115),
            VehicleNoteSpec(11, "Ford Mustang – felgi GT500 wymagają specjalnego detergentu, nie używać standardowego od felg.", 102),
            VehicleNoteSpec(16, "BMW M135i – zawieszenie obniżone. Zachować ostrożność przy najazdach na rampy.", 63),
            VehicleNoteSpec(19, "Lexus RX – biała perłowa tapicerka alcantara. Pranie tylko suchą parą, bez mokrej metody.", 43),
            VehicleNoteSpec(21, "VW Transporter (TRANS-POL) – nie myć po 18:00, auto musi być w bazie firmy na 19:00.", 30),
            VehicleNoteSpec(25, "Skoda Octavia RS – felgi 19\" kute. Klient prosi o naklejenie etykiet korekcji lakieru po każdej wizycie.", 5)
        )

        val entities = specs.mapNotNull { spec ->
            val vehicle = vehicles.getOrNull(spec.vehicleIdx) ?: return@mapNotNull null
            VehicleNoteEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                vehicleId = vehicle.id,
                content = spec.content,
                createdBy = userId,
                createdByName = authorName,
                createdAt = now.minus(spec.daysAgo, ChronoUnit.DAYS),
                updatedAt = now.minus(spec.daysAgo, ChronoUnit.DAYS)
            )
        }
        vehicleNoteRepository.saveAll(entities)
    }

    private fun createLeads(studioId: UUID, customers: List<CustomerEntity>, vehicles: List<VehicleEntity>) {
        val now = Instant.now()

        data class LeadSpec(
            val phone: String,
            val name: String?,
            val message: String,
            val brand: String?,
            val model: String?,
            val status: LeadStatus,
            val source: LeadSource,
            val estimatedValue: Long,
            val daysAgo: Long,
            val customerIdx: Int?
        )

        val specs = listOf(
            LeadSpec("+48732100200", "Krzysztof Baran", "Witam, chciałbym zapytać o cenę korekty lakieru dla mojego BMW M3. Macie wolny termin w przyszłym tygodniu?", "BMW", "M3", LeadStatus.COMPLETED, LeadSource.PHONE, 89900L, 90, null),
            LeadSpec("+48601200300", "Marta Witek", "Dzień dobry! Interesuje mnie powłoka ceramiczna dla Audi A5 Sportback. Proszę o wycenę.", "Audi", "A5", LeadStatus.COMPLETED, LeadSource.EMAIL, 179900L, 75, null),
            LeadSpec("+48512300400", "Dominik Przybysz", "Chciałbym umówić pranie tapicerki w moim Range Rover Velar. Jakie macie terminy?", "Land Rover", "Range Rover Velar", LeadStatus.COMPLETED, LeadSource.PHONE, 49900L, 60, null),
            LeadSpec("+48698400500", "Aleksandra Kubiak", "Mam Porsche Macan z drobnym zarysowaniem na drzwiach. Czy jesteście w stanie to usunąć?", "Porsche", "Macan", LeadStatus.COMPLETED, LeadSource.EMAIL, 69900L, 45, null),
            LeadSpec("+48723500600", "Tomasz Wróbel", "Pytam o zabezpieczenie folią PPF przedniej części BMW 5. Cena i dostępność?", "BMW", "5 Series", LeadStatus.CONFIRMED, LeadSource.PHONE, 299900L, 14, null),
            LeadSpec("+48601600700", "Natalia Kowalska", "Zainteresowana kompleksowym detailingiem wnętrza Mercedes CLA. Proszę o kontakt.", "Mercedes-Benz", "CLA", LeadStatus.IN_PROGRESS, LeadSource.EMAIL, 34900L, 7, null),
            LeadSpec("+48512700800", "Paweł Nowicki", "Mam nowe Audi RS6. Szukam studia do ceramiki. Zobaczyłem was na Instagramie.", "Audi", "RS6 Avant", LeadStatus.IN_PROGRESS, LeadSource.PHONE, 199900L, 3, null),
            LeadSpec("+48698800900", null, "Dobry wieczór. Chciałem zapytać o mycie detailingowe dla VW Golfa.", "Volkswagen", "Golf", LeadStatus.NEW, LeadSource.PHONE, 13900L, 1, null),
            LeadSpec("+48723900100", "Katarzyna Malinowska", "Proszę o informację nt. powłoki ceramicznej dla Tesla Model 3. Słyszałem, że specjalizujecie się w elektryk.", "Tesla", "Model 3", LeadStatus.NEW, LeadSource.EMAIL, 179900L, 0, null),
            LeadSpec("+48601100200", null, "Mam Lamborghini Huracán i szukam studia premium do korekty lakieru + ceramika. Proszę o kontakt.", "Lamborghini", "Huracán", LeadStatus.LOST, LeadSource.PHONE, 299900L, 30, null),
            LeadSpec("+48512200300", "Robert Janiak", "Pytanie o cenę ozonowania + pranie, bo pies naśmiecił w samochodzie... Kia Sportage.", "Kia", "Sportage", LeadStatus.LOST, LeadSource.EMAIL, 31800L, 20, null),
            LeadSpec("+48698300400", "Jolanta Sekula", "Chciałam umówić korekcie lakieru dla mojego Jaguara F-Pace, ale niestety muszę odwołać.", "Jaguar", "F-Pace", LeadStatus.NO_SHOW, LeadSource.PHONE, 69900L, 15, null)
        )

        val entities = specs.map { spec ->
            LeadEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                source = spec.source,
                status = spec.status,
                contactIdentifier = spec.phone,
                customerName = spec.name,
                initialMessage = spec.message,
                estimatedValue = spec.estimatedValue,
                requiresVerification = false,
                vehicleBrand = spec.brand,
                vehicleModel = spec.model,
                customerId = spec.customerIdx?.let { customers.getOrNull(it)?.id },
                appointmentId = null,
                visitId = null,
                createdAt = now.minus(spec.daysAgo, ChronoUnit.DAYS),
                updatedAt = now.minus(spec.daysAgo / 2, ChronoUnit.DAYS),
                assignedUserId = null,
                assignedUserName = null,
                lostReason = null,
                stagnantAlertSentAt = null,
            )
        }
        leadRepository.saveAll(entities)
    }

    private fun createCommunicationLogs(
        studioId: UUID,
        customers: List<CustomerEntity>,
        visits: List<VisitEntity>
    ) {
        val now = Instant.now()
        val logs = mutableListOf<CommunicationLogEntity>()

        data class CommSpec(
            val visitIdx: Int,
            val customerIdx: Int,
            val channel: CommunicationChannel,
            val type: CommunicationMessageType,
            val recipient: String,
            val subject: String?,
            val body: String,
            val hoursAfterStart: Long
        )

        val specs = listOf(
            CommSpec(0, 0, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_WELCOME_EMAIL, "jan.kowalski@gmail.com", "Potwierdzenie przyjęcia pojazdu – BMW X5 [VIS-2025-00001]", "Dzień dobry, Panie Janie! Przyjęliśmy Pana pojazd BMW X5 (PO12345) do realizacji. Numer wizyty: VIS-2025-00001. Szacowany czas realizacji: 6 godzin.", 0),
            CommSpec(0, 0, CommunicationChannel.SMS, CommunicationMessageType.VISIT_READY_FOR_PICKUP_SMS, "+48512345678", null, "Dzień dobry! Pana BMW X5 jest gotowe do odbioru. Zapraszamy serdecznie. Detailing Studio DEMO", 7),
            CommSpec(1, 1, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_WELCOME_EMAIL, "anna.nowak@wp.pl", "Potwierdzenie przyjęcia pojazdu – Audi A6 [VIS-2025-00002]", "Dzień dobry, Pani Anno! Przyjęliśmy Pani pojazd Audi A6 (WA23456) do realizacji. Numer wizyty: VIS-2025-00002.", 0),
            CommSpec(1, 1, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_READY_FOR_PICKUP_EMAIL, "anna.nowak@wp.pl", "Pojazd gotowy do odbioru – Audi A6 [VIS-2025-00002]", "Pani Anno, Pani Audi A6 jest gotowe do odbioru! Zapraszamy w godzinach 8-18. Z poważaniem, Detailing Studio DEMO", 6),
            CommSpec(2, 2, CommunicationChannel.SMS, CommunicationMessageType.VISIT_CONFIRMED_SMS, "+48723456789", null, "Dzień dobry! Potwierdzamy przyjęcie Pana Mercedes C220 do realizacji. Nr wizyty: VIS-2025-00003.", 0),
            CommSpec(3, 2, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_WELCOME_EMAIL, "piotr.wisniewski@onet.pl", "Potwierdzenie przyjęcia pojazdu – Porsche 911 [VIS-2025-00004]", "Witamy! Przyjęliśmy Porsche 911 Carrera 4S (GD99999) do pełnego detailingu. Numer wizyty: VIS-2025-00004.", 0),
            CommSpec(3, 2, CommunicationChannel.SMS, CommunicationMessageType.VISIT_READY_FOR_PICKUP_SMS, "+48723456789", null, "Pana Porsche 911 jest gotowe do odbioru. Wynik korekty: ★★★★★. Zapraszamy!", 9),
            CommSpec(5, 4, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_WELCOME_EMAIL, "marek.kowalczyk@gmail.com", "Potwierdzenie przyjęcia pojazdu – BMW 5 [VIS-2025-00006]", "Dzień dobry, Panie Marku! Przyjęliśmy BMW 5 Series 530i (WR56789) do korekty lakieru + ceramiki.", 0),
            CommSpec(5, 4, CommunicationChannel.SMS, CommunicationMessageType.VISIT_READY_FOR_PICKUP_SMS, "+48512987654", null, "Dzień dobry! BMW 5 z powłoką ceramiczną gotowe do odbioru. Ceramika aktywuje się przez 24h – proszę nie myć auta. Dziękujemy!", 8),
            CommSpec(8, 8, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_WELCOME_EMAIL, "michal.sz@gmail.com", "Potwierdzenie przyjęcia – Mercedes S 500 [VIS-2025-00009]", "Panie Michale, witamy! Mercedes S 500 4MATIC (KR90123) przyjęty do full detailingu.", 0),
            CommSpec(8, 8, CommunicationChannel.SMS, CommunicationMessageType.VISIT_CONFIRMED_SMS, "+48723987654", null, "Dokumenty podpisane. Przystępujemy do realizacji Pana Mercedes S-Klasy. Efekt showroom gwarantowany!", 1),
            CommSpec(8, 8, CommunicationChannel.EMAIL, CommunicationMessageType.VISIT_READY_FOR_PICKUP_EMAIL, "michal.sz@gmail.com", "Pojazd gotowy do odbioru – Mercedes S 500 [VIS-2025-00009]", "Panie Michale, Mercedes S-Klasa jest gotowy do odbioru w efekcie showroom. Zapraszamy!", 9),
            CommSpec(18, 18, CommunicationChannel.SMS, CommunicationMessageType.VISIT_CONFIRMED_SMS, "+48723654321", null, "Pana Kia Stinger przyjęty. Korekta lakieru + ceramika w realizacji. Szacowany czas: 6h.", 0),
            CommSpec(18, 18, CommunicationChannel.SMS, CommunicationMessageType.SMS_AUTOMATION_POST_VISIT, "+48723654321", null, "Dziękujemy za wizytę! Ceramika na Kii Stinger aktywna. Dbaj o lakier – unikaj myjni automatycznych. Zapraszamy ponownie!", 24),
            CommSpec(23, 0, CommunicationChannel.SMS, CommunicationMessageType.VISIT_CONFIRMED_SMS, "+48512345678", null, "BMW X5 – serwis ceramiki. Uzupełnienie topcoat w realizacji. Pan Jan – ok. 4 godziny.", 0),
            CommSpec(35, 5, CommunicationChannel.SMS, CommunicationMessageType.VISIT_CONFIRMED_SMS, "+48798654321", null, "Dzień dobry! Przyjęliśmy Pani Porsche Cayenne do korekty 2-etapowej + ceramiki. Nr: VIS-2025-00036.", 0),
            CommSpec(36, 8, CommunicationChannel.SMS, CommunicationMessageType.VISIT_CONFIRMED_SMS, "+48723987654", null, "Pana Mercedes S-Klasy – przyjęty do korekty lakieru. Szacowany czas: 7 godzin. Nr: VIS-2025-00037.", 0)
        )

        specs.forEach { spec ->
            val visit = visits.getOrNull(spec.visitIdx) ?: return@forEach
            val customer = customers.getOrNull(spec.customerIdx) ?: return@forEach
            val sentAt = visit.scheduledDate.plus(spec.hoursAfterStart, ChronoUnit.HOURS)

            logs.add(
                CommunicationLogEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId,
                    customerId = customer.id,
                    visitId = visit.id,
                    appointmentId = visit.appointmentId,
                    channel = spec.channel,
                    messageType = spec.type,
                    recipientAddress = spec.recipient,
                    subject = spec.subject,
                    bodyContent = spec.body,
                    status = CommunicationStatus.SENT,
                    errorMessage = null,
                    sentAt = sentAt
                )
            )
        }

        // SMS automation pre-visit reminders for future appointments (based on recent visits' customers)
        val reminderSpecs = listOf(
            Triple(0, "+48512345678", "Przypomnienie: jutro o 09:00 wizyta Pana BMW X5 w naszym studio. Zapraszamy!"),
            Triple(2, "+48723456789", "Przypomnienie: pojutrze o 10:00 pełny detailing Porsche 911. Proszę o punktualność."),
            Triple(7, "+48698234567", "Przypomnienie: za 3 dni wizyta Pani Audi Q5. Mycie + detailing 08:00-12:00.")
        )
        reminderSpecs.forEach { (customerIdx, phone, msg) ->
            val customer = customers.getOrNull(customerIdx) ?: return@forEach
            logs.add(
                CommunicationLogEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId,
                    customerId = customer.id,
                    visitId = null,
                    appointmentId = null,
                    channel = CommunicationChannel.SMS,
                    messageType = CommunicationMessageType.SMS_AUTOMATION_PRE_VISIT,
                    recipientAddress = phone,
                    subject = null,
                    bodyContent = msg,
                    status = CommunicationStatus.SENT,
                    errorMessage = null,
                    sentAt = now.minus(1, ChronoUnit.DAYS)
                )
            )
        }

        communicationLogRepository.saveAll(logs)
    }

    private fun createServiceCategories(studioId: UUID, userId: UUID, services: List<ServiceEntity>) {
        val now = Instant.now()

        data class CategorySpec(val name: String, val description: String, val color: String, val serviceIndices: List<Int>)

        val specs = listOf(
            CategorySpec(
                "Pielęgnacja lakieru",
                "Korekty lakieru, powłoki ceramiczne i wszystkie usługi związane z zewnętrzną ochroną pojazdu.",
                "#2563EB",
                listOf(3, 4, 5)  // korekta 1-etap, korekta 2-etap, ceramika
            ),
            CategorySpec(
                "Detailing wnętrza",
                "Czyszczenie, pranie tapicerki i kompleksowy detailing kabiny pasażerskiej.",
                "#16A34A",
                listOf(1, 2)  // detailing wnętrza, pranie tapicerki
            ),
            CategorySpec(
                "Usługi podstawowe",
                "Mycie detailingowe, ozonowanie i impregnacja szyb – regularna pielęgnacja pojazdu.",
                "#F97316",
                listOf(0, 6, 7)  // mycie, ozonowanie, szyby
            )
        )

        specs.forEach { spec ->
            val categoryId = UUID.randomUUID()
            val category = ServiceCategoryEntity(
                id = categoryId,
                studioId = studioId,
                name = spec.name,
                description = spec.description,
                color = spec.color,
                isActive = true,
                createdBy = userId,
                createdAt = now,
                updatedAt = now
            )
            serviceCategoryRepository.save(category)

            val assignments = spec.serviceIndices.mapNotNull { sIdx ->
                val svc = services.getOrNull(sIdx) ?: return@mapNotNull null
                CategoryServiceAssignmentEntity(
                    id = UUID.randomUUID(),
                    categoryId = categoryId,
                    serviceId = svc.id,
                    studioId = studioId,
                    assignedAt = now
                )
            }
            categoryServiceAssignmentRepository.saveAll(assignments)
        }
    }
}
