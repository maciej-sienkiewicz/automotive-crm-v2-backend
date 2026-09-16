package pl.detailing.crm.visit.damagemap

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitDocumentId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.domain.DamagePoint
import pl.detailing.crm.visit.domain.VisitDocument
import pl.detailing.crm.visit.infrastructure.DamageMapReportService
import pl.detailing.crm.visit.infrastructure.DamageMarkingService
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitDocumentRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * „Zaktualizuj uszkodzenia" — zgłoszenie z produkcji: w trakcie wizyty wychodzi rysa,
 * której nie było w protokole przyjęcia, i nie ma jej gdzie dopisać.
 *
 * Testy pilnują trzech rzeczy, na których ta funkcja stoi lub upada:
 *  1. punkty zapisują się ZAWSZE, także gdy generowanie PDF-a padnie,
 *  2. „nowy plik" nie rusza dokumentu z przyjęcia, a „zaktualizuj istniejący"
 *     nadpisuje TEN plik, na który wizyta wskazuje (nie kanoniczny),
 *  3. klienta powiadamiamy tylko na wyraźne TAK.
 */
class UpdateVisitDamageMapHandlerTest {

    private val visitRepository: VisitRepository = mockk(relaxed = true)
    private val visitDocumentRepository: VisitDocumentRepository = mockk(relaxed = true)
    private val damageMapStore: VisitDamageMapStore = mockk(relaxed = true)
    private val reportService: DamageMapReportService = mockk()
    private val markingService: DamageMarkingService = mockk()
    private val s3: S3DamageMapStorageService = mockk(relaxed = true)
    private val documentService: DocumentService = mockk()
    private val checkinPhotoService: CheckinPhotoService = mockk(relaxed = true)
    private val customerRepository: CustomerRepository = mockk()
    private val notifier: VisitDamageMapNotifier = mockk()
    private val auditService: AuditService = mockk { coEvery { log(any()) } returns Unit }

    private val handler = UpdateVisitDamageMapHandler(
        visitRepository, visitDocumentRepository, damageMapStore, reportService, markingService,
        s3, documentService, checkinPhotoService, customerRepository, notifier, auditService
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val customerId = UUID.randomUUID()

    private val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    private fun visit(
        status: VisitStatus = VisitStatus.IN_PROGRESS,
        damageMapFileId: String? = "studio/visits/v/damage-map.pdf"
    ): VisitEntity = mockk<VisitEntity>(relaxed = true).also {
        every { it.id } returns visitId.value
        every { it.status } returns status
        every { it.visitNumber } returns "WIZ/2026/09/001"
        every { it.customerId } returns customerId
        every { it.damageMapFileId } returns damageMapFileId
        every { it.photos } returns mutableListOf()
        every { it.brandSnapshot } returns "Porsche"
        every { it.modelSnapshot } returns "911"
        every { it.licensePlateSnapshot } returns "WA12345"
        every { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns it
    }

    private fun command(
        points: List<DamagePoint> = listOf(DamagePoint(1, 10.0, 20.0, "rysa na masce")),
        mode: DamageMapUpdateMode = DamageMapUpdateMode.NEW_FILE,
        notify: Boolean = false,
        notifyMessage: String? = null
    ) = UpdateVisitDamageMapCommand(
        visitId = visitId,
        studioId = studioId,
        userId = userId,
        userName = "Anna Kowalska",
        damagePoints = points,
        vehicleType = "sedan",
        mode = mode,
        notifyCustomer = notify,
        notifyMessage = notifyMessage
    )

    private fun stubHappyPath(revision: Int = 2, uploadedKey: String = "studio/visits/v/damage-map-r2.pdf") {
        every { damageMapStore.load(visitId, studioId) } returns StoredDamageMap(
            damagePoints = listOf(DamagePoint(1, 10.0, 20.0, "rysa")),
            vehicleType = "sedan",
            documentS3Key = "studio/visits/v/damage-map.pdf",
            revision = revision - 1,
            updatedAt = java.time.Instant.now(),
            updatedByName = "Anna Kowalska"
        )
        every { damageMapStore.save(any(), any(), any(), any(), any(), any(), any(), any()) } returns revision
        coEvery { reportService.generateReport(any(), any(), any()) } returns pdf
        coEvery { s3.uploadDamageMap(any(), any(), any(), any()) } returns uploadedKey
        coEvery { documentService.registerDocument(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            mockk<VisitDocument> { every { id } returns VisitDocumentId(UUID.randomUUID()) }
    }

    @Test
    fun `nowy plik nie nadpisuje mapy z przyjecia`() = runBlocking {
        visit()
        stubHappyPath()

        val result = handler.handle(command(mode = DamageMapUpdateMode.NEW_FILE))

        // Sufiks rewizji = osobny obiekt w S3; kanoniczny damage-map.pdf zostaje.
        val suffix = slot<String>()
        coVerify { s3.uploadDamageMap(studioId.value, visitId.value, pdf, capture(suffix)) }
        assertEquals("r2", suffix.captured)
        coVerify(exactly = 0) { s3.uploadDamageMapToKey(any(), any()) }
        // Wpisu z przyjęcia nie ruszamy.
        io.mockk.verify(exactly = 0) { visitDocumentRepository.deleteAll(any()) }
        assertTrue(result.documentGenerated)
        assertEquals(2, result.revision)
        /*
         * Wskaźnik „aktualna mapa wizyty" musi wskazać nowy plik — to jego doklejają
         * maile do klienta. Bez tego nowa mapa leżałaby w galerii, a poczta wysyłała
         * nadal wersję z przyjęcia.
         */
        io.mockk.verify {
            visitRepository.updateDamageMapFileId(
                id = visitId.value,
                studioId = studioId.value,
                fileId = "studio/visits/v/damage-map-r2.pdf",
                userId = userId.value,
                now = any()
            )
        }
    }

    @Test
    fun `aktualizacja istniejacego nadpisuje plik ktory wizyta faktycznie wskazuje`() = runBlocking {
        // Wizyta wskazuje na WCZEŚNIEJSZĄ rewizję, nie na kanoniczny plik. Gdyby
        // handler szedł po kanonicznym kluczu, skasowałby mapę z przyjęcia.
        visit(damageMapFileId = "studio/visits/v/damage-map-r2.pdf")
        stubHappyPath(revision = 3)

        handler.handle(command(mode = DamageMapUpdateMode.REPLACE_EXISTING))

        coVerify { s3.uploadDamageMapToKey("studio/visits/v/damage-map-r2.pdf", pdf) }
        coVerify(exactly = 0) { s3.uploadDamageMap(any(), any(), any(), any()) }
    }

    @Test
    fun `punkty zapisuja sie takze wtedy gdy generowanie PDF padnie`() = runBlocking {
        visit()
        every { damageMapStore.load(visitId, studioId) } returns null
        every { damageMapStore.save(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { reportService.generateReport(any(), any(), any()) } throws IllegalStateException("brak fontu")

        val result = handler.handle(command())

        assertFalse(result.documentGenerated)
        assertNull(result.documentId)
        assertEquals(1, result.pointsCount)
        // Zapis punktów poszedł, i to PRZED generowaniem pliku.
        io.mockk.verify {
            damageMapStore.save(visitId, studioId, any(), "sedan", null, userId, "Anna Kowalska", true)
        }
        // Audyt istnieje mimo braku pliku — ślad „kto dopisał uszkodzenie" jest ważniejszy.
        val audit = slot<LogAuditCommand>()
        coVerify { auditService.log(capture(audit)) }
        assertEquals("false", audit.captured.metadata["damageMapDocumentGenerated"])
    }

    @Test
    fun `klient dostaje powiadomienie tylko na wyrazne TAK`() = runBlocking {
        visit()
        stubHappyPath()
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns
            mockk<CustomerEntity>(relaxed = true) {
                every { firstName } returns "Jan"
                every { email } returns "jan@example.com"
                every { phone } returns "534920205"
            }
        every { notifier.notifyCustomer(any()) } returns
            DamageMapNotificationResult(emailSent = true, smsSent = false, message = "ok")

        val withoutNotify = handler.handle(command(notify = false))
        assertNull(withoutNotify.notification)
        io.mockk.verify(exactly = 0) { notifier.notifyCustomer(any()) }

        val withNotify = handler.handle(command(notify = true))
        assertTrue(withNotify.notification!!.emailSent)
        val request = slot<DamageMapNotificationRequest>()
        io.mockk.verify { notifier.notifyCustomer(capture(request)) }
        // Załącznikiem jest świeży PDF, nie plik z przyjęcia.
        assertEquals(pdf, request.captured.pdfBytes)
        assertEquals(1, request.captured.pointsBefore)
        assertEquals(1, request.captured.pointsAfter)
    }

    @Test
    fun `zamknietej wizyty nie da sie juz domalowac`() {
        visit(status = VisitStatus.COMPLETED)

        val error = assertThrows(ValidationException::class.java) {
            runBlocking { handler.handle(command()) }
        }
        assertTrue(error.message!!.contains("wizyta jest zamknięta"))
        io.mockk.verify(exactly = 0) {
            damageMapStore.save(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `powtorzone numery punktow sa odrzucane zanim cokolwiek zapiszemy`() {
        val duplicated = listOf(
            DamagePoint(1, 10.0, 20.0, "rysa"),
            DamagePoint(1, 30.0, 40.0, "wgniecenie")
        )

        val error = assertThrows(ValidationException::class.java) {
            runBlocking { handler.handle(command(points = duplicated)) }
        }
        assertTrue(error.message!!.contains("powtórzone numery"))
        io.mockk.verify(exactly = 0) {
            damageMapStore.save(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `wyczyszczenie mapy do zera punktow zapisuje sie bez generowania pliku`() = runBlocking {
        visit()
        every { damageMapStore.load(visitId, studioId) } returns null
        every { damageMapStore.save(any(), any(), any(), any(), any(), any(), any(), any()) } returns 2

        val result = handler.handle(command(points = emptyList()))

        assertEquals(0, result.pointsCount)
        assertFalse(result.documentGenerated)
        coVerify(exactly = 0) { reportService.generateReport(any(), any(), any()) }
    }
}
