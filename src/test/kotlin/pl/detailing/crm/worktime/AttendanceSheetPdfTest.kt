package pl.detailing.crm.worktime

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import org.apache.pdfbox.pdmodel.PDDocument
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.worktime.attendance.AttendanceSheetSigner
import pl.detailing.crm.worktime.attendance.GenerateAttendanceSheetCommand
import pl.detailing.crm.worktime.attendance.GenerateAttendanceSheetHandler
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.YearMonth
import java.util.Optional
import javax.imageio.ImageIO
import java.util.UUID

/**
 * Lista obecności.
 *
 * Arkusz jest dokumentem do podpisu, więc liczy się to, czego nie widać w kodzie:
 * czy PDF w ogóle powstaje, czy ma na sobie wszystkich zaznaczonych pracowników i
 * wszystkie dni miesiąca, oraz czy pracownik BEZ modułu Czasu pracy nie wpada do
 * zestawienia tylnymi drzwiami — bramka w interfejsie jest tylko wygodą, decyduje
 * backend.
 */
class AttendanceSheetPdfTest {

    private val employeeRepository = mockk<EmployeeRepository>()
    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()
    private val studioSettingsRepository = mockk<StudioSettingsRepository>()
    private val documentStorageService = mockk<DocumentStorageService>(relaxed = true)
    private val entryRepository = mockk<WorkTimeEntryRepository>()
    private val periodRepository = mockk<WorkTimePeriodRepository>()

    private val handler = GenerateAttendanceSheetHandler(
        employeeRepository, userRepository, roleRepository, studioSettingsRepository,
        documentStorageService, entryRepository, periodRepository
    )

    private val studio = StudioId.random()

    private fun employee(firstName: String, lastName: String, userId: UUID?): EmployeeEntity =
        EmployeeEntity(
            id = UUID.randomUUID(),
            studioId = studio.value,
            userId = userId,
            firstName = firstName,
            lastName = lastName,
            phone = null,
            email = null,
            createdBy = UUID.randomUUID(),
            updatedBy = UUID.randomUUID()
        )

    /**
     * Rejestruje pracownika w mockach razem z rolą, flagą trackWorkTime i kartą czasu
     * pracy: [hoursByDay] to dzień miesiąca → minuty.
     */
    private fun register(
        entity: EmployeeEntity,
        tracksWorkTime: Boolean,
        isOwner: Boolean = false,
        hoursByDay: Map<Int, Int> = emptyMap(),
        status: PeriodStatus? = PeriodStatus.APPROVED
    ) {
        every { employeeRepository.findByIdAndStudioId(entity.id, studio.value) } returns entity

        val userId = entity.userId ?: return
        val roleId = UUID.randomUUID()
        val user = mockk<UserEntity>()
        every { user.isOwner } returns isOwner
        every { user.customRoleId } returns roleId
        every { userRepository.findByIdAndStudioId(userId, studio.value) } returns user

        val role = mockk<RoleEntity>()
        every { role.trackWorkTime } returns tracksWorkTime
        every { roleRepository.findByIdAndStudioId(roleId, studio.value) } returns role

        every {
            entryRepository.findByUserIdAndStudioIdAndDateBetween(userId, studio.value, any(), any())
        } answers {
            val from = arg<java.time.LocalDate>(2)
            hoursByDay.map { (day, minutes) ->
                WorkTimeEntryEntity(
                    userId = userId, studioId = studio.value,
                    date = from.withDayOfMonth(day), minutes = minutes
                )
            }
        }
        val period = status?.let {
            mockk<WorkTimePeriodEntity>().also { entity -> every { entity.status } returns it }
        }
        every {
            periodRepository.findByUserIdAndStudioIdAndPeriod(userId, studio.value, any())
        } returns period
    }

    private fun stubSettings(name: String? = "Studio Blask") {
        val settings = mockk<StudioSettingsEntity>()
        every { settings.name } returns name
        every { settings.logoS3Key } returns null
        every { studioSettingsRepository.findById(studio.value) } returns Optional.of(settings)
    }

    private fun textOf(pdf: ByteArray): String =
        Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    @Test
    fun `arkusz niesie wszystkich zaznaczonych pracownikow i wszystkie dni miesiaca`() = runBlocking {
        stubSettings()
        val anna = employee("Anna", "Kowalska", UUID.randomUUID())
        val piotr = employee("Piotr", "Zieliński", UUID.randomUUID())
        register(anna, tracksWorkTime = true)
        register(piotr, tracksWorkTime = true)

        val pdf = handler.handle(
            GenerateAttendanceSheetCommand(
                studioId = studio,
                period = YearMonth.of(2026, 1),
                employeeIds = listOf(EmployeeId(anna.id), EmployeeId(piotr.id))
            )
        )

        val text = textOf(pdf)
        assertTrue(text.contains("LISTA OBECNOŚCI"), "Tytuł dokumentu")
        assertTrue(text.contains("Kowalska") && text.contains("Zieliński"), "Nazwiska w nagłówkach kolumn")
        assertTrue(text.contains("Styczeń 2026"), "Miesiąc w metryce: $text")
        assertTrue(text.contains("31 sb"), "Ostatni dzień stycznia jako wiersz")
        assertTrue(text.contains("Studio Blask"), "Nazwa studia w polu USŁUGODAWCA")
    }

    @Test
    fun `godziny z kart czasu pracy trafiaja do komorek razem z suma miesiaca`() = runBlocking {
        stubSettings()
        val anna = employee("Anna", "Kowalska", UUID.randomUUID())
        // 2 i 3 marca po 8h, 4 marca 7h30 — razem 23:30.
        register(anna, tracksWorkTime = true, hoursByDay = mapOf(2 to 480, 3 to 480, 4 to 450))

        val text = textOf(
            handler.handle(
                GenerateAttendanceSheetCommand(studio, YearMonth.of(2026, 3), listOf(EmployeeId(anna.id)))
            )
        )

        assertTrue(text.contains("8:00"), "Godziny z karty czasu pracy: $text")
        assertTrue(text.contains("7:30"), "Niepełna godzina zapisana jak w module Czasu pracy")
        assertTrue(text.contains("RAZEM"), "Wiersz sumy")
        assertTrue(text.contains("23:30"), "Suma miesiąca")
        assertTrue(text.contains("zatwierdzone"), "Stan kart czasu pracy w stopce")
    }

    @Test
    fun `dzien bez wpisu zostaje pusty, a nie zerowy`() = runBlocking {
        stubSettings()
        val anna = employee("Anna", "Kowalska", UUID.randomUUID())
        register(anna, tracksWorkTime = true, hoursByDay = mapOf(2 to 480))

        val text = textOf(
            handler.handle(
                GenerateAttendanceSheetCommand(studio, YearMonth.of(2026, 3), listOf(EmployeeId(anna.id)))
            )
        )

        assertTrue(!text.contains("0:00"), "Nieobecność to puste pole, nie wpisane zero")
    }

    @Test
    fun `niezatwierdzone karty sa opisane w stopce`() = runBlocking {
        stubSettings()
        val anna = employee("Anna", "Kowalska", UUID.randomUUID())
        register(anna, tracksWorkTime = true, hoursByDay = mapOf(2 to 480), status = PeriodStatus.SUBMITTED)

        val text = textOf(
            handler.handle(
                GenerateAttendanceSheetCommand(studio, YearMonth.of(2026, 3), listOf(EmployeeId(anna.id)))
            )
        )

        assertTrue(text.contains("niezatwierdzona"), "Arkusz musi mówić, że godziny nie są jeszcze zatwierdzone: $text")
    }

    @Test
    fun `luty w roku przestepnym ma 29 wierszy, a nie 28 czy 31`() = runBlocking {
        stubSettings()
        val anna = employee("Anna", "Kowalska", UUID.randomUUID())
        register(anna, tracksWorkTime = true)

        val text = textOf(
            handler.handle(
                GenerateAttendanceSheetCommand(studio, YearMonth.of(2024, 2), listOf(EmployeeId(anna.id)))
            )
        )

        assertTrue(text.contains("29 cz"), "29 lutego 2024 to czwartek")
        assertTrue(!text.contains("30 "), "Luty nie ma trzydziestego dnia")
    }

    @Test
    fun `pracownik bez modulu Czasu pracy nie trafia do arkusza`() = runBlocking {
        stubSettings()
        val tracked = employee("Anna", "Kowalska", UUID.randomUUID())
        val untracked = employee("Marek", "Nowak", UUID.randomUUID())
        register(tracked, tracksWorkTime = true)
        register(untracked, tracksWorkTime = false)

        val text = textOf(
            handler.handle(
                GenerateAttendanceSheetCommand(
                    studio, YearMonth.of(2026, 3),
                    listOf(EmployeeId(tracked.id), EmployeeId(untracked.id))
                )
            )
        )

        assertTrue(text.contains("Kowalska"))
        assertTrue(!text.contains("Nowak"), "Rola bez trackWorkTime nie prowadzi karty — kolumna byłaby pusta")
        assertTrue(text.contains("LICZBA PRACOWNIKÓW"))
    }

    @Test
    fun `zaznaczenie samych pracownikow bez modulu konczy sie czytelnym bledem`() {
        stubSettings()
        val untracked = employee("Marek", "Nowak", UUID.randomUUID())
        register(untracked, tracksWorkTime = false)

        val error = assertThrows(ValidationException::class.java) {
            runBlocking {
                handler.handle(
                    GenerateAttendanceSheetCommand(studio, YearMonth.of(2026, 3), listOf(EmployeeId(untracked.id)))
                )
            }
        }
        assertTrue(error.message!!.contains("Czasu pracy"))
    }

    @Test
    fun `powyzej siedmiu pracownikow arkusz przechodzi na kolejna strone`() = runBlocking {
        stubSettings()
        val employees = (1..9).map { index ->
            employee("Imie$index", "Nazwisko$index", UUID.randomUUID()).also { register(it, tracksWorkTime = true) }
        }

        val pdf = handler.handle(
            GenerateAttendanceSheetCommand(
                studio, YearMonth.of(2026, 4), employees.map { EmployeeId(it.id) }
            )
        )

        val pages = Loader.loadPDF(pdf).use { it.numberOfPages }
        assertEquals(2, pages, "Ośmiu i więcej pracowników nie mieści się na jednej stronie")
        assertTrue(textOf(pdf).contains("Strona 1 z 2"))
    }

    @Test
    fun `podpis laduje na ostatniej stronie arkusza`() = runBlocking {
        stubSettings()
        val employees = (1..9).map { index ->
            employee("Imie$index", "Nazwisko$index", UUID.randomUUID()).also { register(it, tracksWorkTime = true) }
        }
        val pdf = handler.handle(
            GenerateAttendanceSheetCommand(studio, YearMonth.of(2026, 4), employees.map { EmployeeId(it.id) })
        )

        val signer = AttendanceSheetSigner(SignatureImageProcessor())
        val signed = signer.sign(pdf, inkPng(), "Mikołaj Właściciel", Instant.parse("2026-04-30T09:15:00Z"))

        val text = Loader.loadPDF(signed).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Mikołaj Właściciel"), "Podpisujący i data pod linią podpisu")
        assertTrue(text.contains("30.04.2026"), "Data złożenia podpisu: $text")

        // Podpis rysuje się na ostatniej stronie — pod tabelą, która się kończy.
        val lastPageText = Loader.loadPDF(signed).use { document ->
            PDFTextStripper().apply { startPage = document.numberOfPages; endPage = document.numberOfPages }
                .getText(document)
        }
        assertTrue(lastPageText.contains("Mikołaj Właściciel"))
    }

    @Test
    fun `pusty podpis jest odrzucany`() {
        val signer = AttendanceSheetSigner(SignatureImageProcessor())
        val blank = BufferedImage(120, 60, BufferedImage.TYPE_INT_ARGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(blank, "PNG", it) }.toByteArray()

        assertThrows(ValidationException::class.java) {
            signer.sign(minimalPdf(), bytes, "Ktoś", Instant.now())
        }
    }

    /** Kwadrat „atramentu" — tyle wystarczy, żeby procesor obrazu uznał podpis za niepusty. */
    private fun inkPng(): ByteArray {
        val image = BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.BLACK
        graphics.fillRect(20, 20, 120, 30)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
    }

    private fun minimalPdf(): ByteArray = PDDocument().use { document ->
        document.addPage(org.apache.pdfbox.pdmodel.PDPage())
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }
}
