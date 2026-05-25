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
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.customer.notes.CustomerNoteEntity
import pl.detailing.crm.customer.notes.CustomerNoteRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.*
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
    private val instagramProfileRepository: InstagramProfileRepository,
    private val studioInstagramProfileRepository: StudioInstagramProfileRepository
) {

    @Transactional
    fun seed(studioId: UUID, userId: UUID) {
        val colors = createColors(studioId, userId)
        val services = createServices(studioId, userId)
        val customers = createCustomers(studioId, userId)
        createCustomerNotes(studioId, userId, customers)
        val vehicles = createVehicles(studioId, userId, customers)
        createHistoricalVisits(studioId, userId, customers, vehicles, services, colors)
        createInProgressVisits(studioId, userId, customers, vehicles, services, colors)
        createFutureAppointments(studioId, userId, customers, vehicles, services, colors)
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
    ) {
        val now = Instant.now()

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
                mileageAtArrival = vehicle.currentMileage - (spec.daysAgo / 10).toInt(),
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
        }
    }

    private fun createInProgressVisits(
        studioId: UUID,
        userId: UUID,
        customers: List<CustomerEntity>,
        vehicles: List<VehicleEntity>,
        services: List<ServiceEntity>,
        colors: List<AppointmentColorEntity>
    ) {
        val now = Instant.now()
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
                mileageAtArrival = vehicle.currentMileage,
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
        }
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
}
