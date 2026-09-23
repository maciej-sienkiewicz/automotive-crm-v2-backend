package pl.detailing.crm.worktime.attendance

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.time.YearMonth
import java.util.Base64
import java.util.UUID

/**
 * Rozliczenia: lista obecności nie znika do folderu Pobrane, tylko czeka w systemie na
 * zatwierdzenie. Przy kilku administratorach liczy się, kto ją wygenerował, kto zatwierdził
 * i że dwóch naraz nie nadpisze sobie nawzajem stanu.
 */
class AttendanceSheetServiceTest {

    private val studioId = StudioId.random()
    private val adminId = UserId.random()
    private val sheetId = UUID.randomUUID()
    private val originalKey = "${studioId.value}/attendance-sheets/2026-09/$sheetId.pdf"
    private val signature = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

    private val generateHandler = mockk<GenerateAttendanceSheetHandler>()
    private val repository = mockk<AttendanceSheetRepository>()
    private val storage = mockk<DocumentStorageService>()
    private val signer = mockk<AttendanceSheetSigner>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val audited = mutableListOf<AuditEvent>()

    private val service = AttendanceSheetService(generateHandler, repository, storage, signer, auditService)

    init {
        every { auditService.recordSync(capture(audited)) } returns Unit
        coEvery { storage.uploadDocument(any(), any(), any(), any()) } answers { firstArg() }
        coEvery { storage.deleteDocument(any()) } returns Unit
    }

    private fun sheet(
        status: AttendanceSheetStatus = AttendanceSheetStatus.GENERATED,
        signedKey: String? = null,
        approvedByName: String? = null
    ) = AttendanceSheetEntity(
        id = sheetId,
        studioId = studioId.value,
        period = "2026-09",
        employeeIdsJson = """["${UUID.randomUUID()}","${UUID.randomUUID()}"]""",
        fileS3Key = originalKey,
        signedFileS3Key = signedKey,
        createdBy = UUID.randomUUID(),
        createdAt = Instant.parse("2026-09-23T10:00:00Z"),
        status = status,
        createdByName = "Anna Nowak",
        approvedByName = approvedByName
    )

    @Test
    fun `wygenerowana lista czeka na zatwierdzenie i pamieta, kto ja wygenerowal`() {
        coEvery { generateHandler.handle(any()) } returns byteArrayOf(9)
        val saved = slot<AttendanceSheetEntity>()
        every { repository.save(capture(saved)) } answers { firstArg() }

        runBlocking {
            service.generate(studioId, adminId, "Jan Kowalski", YearMonth.of(2026, 9), listOf(EmployeeId.random()))
        }

        assertEquals(AttendanceSheetStatus.GENERATED, saved.captured.status)
        assertEquals("Jan Kowalski", saved.captured.createdByName)
        assertEquals(adminId.value, saved.captured.createdBy)
        assertEquals(AuditAction.ATTENDANCE_SHEET_GENERATED, audited.single().action)
    }

    @Test
    fun `zatwierdzenie bez podpisu zapisuje kto i kiedy, a pliku nie rusza`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returnsMany listOf(
            sheet(),
            sheet(AttendanceSheetStatus.APPROVED, approvedByName = "Jan Kowalski")
        )
        every { repository.markApproved(sheetId, studioId.value, any(), adminId.value, "Jan Kowalski") } returns 1

        val result = runBlocking { service.approve(studioId, adminId, "Jan Kowalski", sheetId, null) }

        assertEquals(AttendanceSheetStatus.APPROVED, result.status)
        verify(exactly = 0) { repository.markApprovedWithSignature(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storage.uploadDocument(any(), any(), any(), any()) }
        assertEquals(AuditAction.ATTENDANCE_SHEET_APPROVED, audited.single().action)
    }

    @Test
    fun `lista zatwierdzona przez innego administratora nie da sie zatwierdzic drugi raz`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returns
            sheet(AttendanceSheetStatus.APPROVED, approvedByName = "Anna Nowak")

        val error = assertThrows<ConflictException> {
            runBlocking { service.approve(studioId, adminId, "Jan Kowalski", sheetId, null) }
        }

        assertTrue("Anna Nowak" in error.message!!, error.message)
        verify(exactly = 0) { repository.markApproved(any(), any(), any(), any(), any()) }
        assertTrue(audited.isEmpty())
    }

    @Test
    fun `dwoch administratorow naraz - przegrany dostaje konflikt zamiast nadpisac zatwierdzenie`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returnsMany listOf(
            sheet(),
            sheet(AttendanceSheetStatus.APPROVED, approvedByName = "Anna Nowak")
        )
        every { repository.markApproved(any(), any(), any(), any(), any()) } returns 0

        assertThrows<ConflictException> {
            runBlocking { service.approve(studioId, adminId, "Jan Kowalski", sheetId, null) }
        }
        assertTrue(audited.isEmpty())
    }

    @Test
    fun `zatwierdzenie z podpisem wtapia go w osobny plik i zatwierdza w jednym kroku`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returns sheet()
        every { storage.downloadBytes(originalKey) } returns byteArrayOf(1)
        every { signer.sign(any(), any(), "Jan Kowalski", any()) } returns byteArrayOf(2)
        val signedKey = slot<String>()
        every {
            repository.markApprovedWithSignature(sheetId, studioId.value, any(), adminId.value, "Jan Kowalski", capture(signedKey))
        } returns 1

        runBlocking { service.approve(studioId, adminId, "Jan Kowalski", sheetId, signature) }

        assertTrue(signedKey.captured.startsWith(originalKey.removeSuffix(".pdf") + "-signed-"), signedKey.captured)
        assertTrue(signedKey.captured != originalKey)
        coVerify(exactly = 1) { storage.uploadDocument(signedKey.captured, byteArrayOf(2), "application/pdf", any()) }
        verify(exactly = 0) { repository.markApproved(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `przegrany wyscig z podpisem kasuje tylko swoj plik`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returnsMany listOf(
            sheet(),
            sheet(AttendanceSheetStatus.APPROVED, approvedByName = "Anna Nowak")
        )
        every { storage.downloadBytes(originalKey) } returns byteArrayOf(1)
        every { signer.sign(any(), any(), any(), any()) } returns byteArrayOf(2)
        val signedKey = slot<String>()
        every { repository.markApprovedWithSignature(any(), any(), any(), any(), any(), capture(signedKey)) } returns 0

        assertThrows<ConflictException> {
            runBlocking { service.approve(studioId, adminId, "Jan Kowalski", sheetId, signature) }
        }

        coVerify(exactly = 1) { storage.deleteDocument(signedKey.captured) }
        coVerify(exactly = 0) { storage.deleteDocument(originalKey) }
    }

    @Test
    fun `podpis w innym formacie niz PNG jest odrzucany zanim cokolwiek trafi do S3`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returns sheet()

        assertThrows<ValidationException> {
            runBlocking { service.approve(studioId, adminId, "Jan Kowalski", sheetId, "data:image/jpeg;base64,AAAA") }
        }
        coVerify(exactly = 0) { storage.uploadDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `stara sciezka podpisu tez zatwierdza liste`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returns sheet()
        every { storage.downloadBytes(originalKey) } returns byteArrayOf(1)
        every { signer.sign(any(), any(), any(), any()) } returns byteArrayOf(2)
        every { repository.markApprovedWithSignature(any(), any(), any(), any(), any(), any()) } returns 1

        runBlocking { service.sign(studioId, adminId, "Jan Kowalski", sheetId, signature) }

        verify(exactly = 1) {
            repository.markApprovedWithSignature(sheetId, studioId.value, any(), adminId.value, "Jan Kowalski", any())
        }
    }

    @Test
    fun `usuniecie kasuje wiersz i oba pliki, a blad S3 nie psuje operacji`() {
        val signedKey = originalKey.removeSuffix(".pdf") + "-signed.pdf"
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returns
            sheet(AttendanceSheetStatus.APPROVED, signedKey = signedKey)
        every { repository.deleteByIdAndStudioId(sheetId, studioId.value) } returns 1
        coEvery { storage.deleteDocument(originalKey) } throws IllegalStateException("S3 down")

        runBlocking { service.delete(studioId, adminId, "Jan Kowalski", sheetId) }

        coVerify(exactly = 1) { storage.deleteDocument(originalKey) }
        coVerify(exactly = 1) { storage.deleteDocument(signedKey) }
        assertEquals(AuditAction.ATTENDANCE_SHEET_DELETED, audited.single().action)
    }

    @Test
    fun `listy innego studia nie da sie usunac`() {
        every { repository.findByIdAndStudioId(sheetId, studioId.value) } returns null

        assertThrows<EntityNotFoundException> {
            runBlocking { service.delete(studioId, adminId, "Jan Kowalski", sheetId) }
        }
        verify(exactly = 0) { repository.deleteByIdAndStudioId(any(), any()) }
        coVerify(exactly = 0) { storage.deleteDocument(any()) }
    }
}
