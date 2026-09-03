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
import pl.detailing.crm.worktime.attendance.GenerateAttendanceSheetCommand
import pl.detailing.crm.worktime.attendance.GenerateAttendanceSheetHandler
import java.time.YearMonth
import java.util.Optional
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

    private val handler = GenerateAttendanceSheetHandler(
        employeeRepository, userRepository, roleRepository, studioSettingsRepository, documentStorageService
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

    /** Rejestruje pracownika w mockach razem z rolą i jej flagą trackWorkTime. */
    private fun register(entity: EmployeeEntity, tracksWorkTime: Boolean, isOwner: Boolean = false) {
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
}
