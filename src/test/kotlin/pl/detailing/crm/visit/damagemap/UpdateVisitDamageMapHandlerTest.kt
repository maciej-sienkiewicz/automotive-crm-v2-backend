package pl.detailing.crm.visit.damagemap

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
 * której nie ma w protokole przyjęcia, i nie ma jej gdzie dopisać.
 *
 * Testy pilnują trzech rzeczy, na których ta funkcja stoi lub upada:
 *  1. punkty zapisują się ZAWSZE, także gdy generowanie PDF-a padnie,
 *  2. „nowy plik" nie rusza dokumentu z przyjęcia, a „zaktualizuj istniejący"
 *     nadpisuje TEN plik, na który wizyta wskazuje (nie kanoniczny),
 *  3. klienta powiadamiamy tylko na wyraźne TAK.
 *
 * [VisitDamageMapStore] jest tu PRAWDZIWY, postawiony na atrapie repozytorium.
 * Zamockowany zwracał liczbę rewizji i poprzednie punkty z zaślepki, a wtedy dwie
 * rzeczy naraz szły źle: asercje sprawdzały wartość wpisaną w `every` zamiast
 * działania kodu, a dopasowanie `any()` po parametrach będących klasami
 * `@JvmInline` ([VisitId], [StudioId] — w sygnaturze JVM zwykłe `UUID`) po cichu
 * nie trafiało, więc `relaxed` mock oddawał 0 i null. Prawdziwy store przechodzi
 * przy okazji pełną drogę przez jsonb.
 */
class UpdateVisitDamageMapHandlerTest {

    private val visitRepository: VisitRepository = mockk(relaxed = true)
    private val visitDocumentRepository: VisitDocumentRepository = mockk(relaxed = true)
    private val reportService: DamageMapReportService = mockk()
    private val markingService: DamageMarkingService = mockk()
    private val s3: S3DamageMapStorageService = mockk(relaxed = true)
    private val documentService: DocumentService = mockk()
    private val checkinPhotoService: CheckinPhotoService = mockk(relaxed = true)
    private val customerRepository: CustomerRepository = mockk()
    private val notifier: VisitDamageMapNotifier = mockk()
    private val auditService: AuditService = mockk { coEvery { log(any()) } returns Unit }

    // ── Atrapa magazynu punktów: zwykła mapa w pamięci, klucze to UUID-y, więc
    //    nigdzie nie ma dopasowywania klas `@JvmInline`.
    private val rows = mutableMapOf<UUID, VisitDamageMapEntity>()
    private val damageMapRepository: VisitDamageMapRepository = mockk<VisitDamageMapRepository>().also { repo ->
        every { repo.findByVisitIdAndStudioId(any(), any()) } answers {
            val visit = firstArg<UUID>()
            val studio = secondArg<UUID>()
            rows[visit]?.takeIf { it.studioId == studio }
        }
        every { repo.save(any<VisitDamageMapEntity>()) } answers {
            firstArg<VisitDamageMapEntity>().also { rows[it.visitId] = it }
        }
    }
    private val damageMapStore = VisitDamageMapStore(damageMapRepository)

    private val handler = UpdateVisitDamageMapHandler(
        visitRepository, visitDocumentRepository, damageMapStore, reportService, markingService,
        s3, documentService, checkinPhotoService, customerRepository, notifier, auditService
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val customerId = UUID.randomUUID()

    private val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)

    private val intakePoints = listOf(DamagePoint(1, 10.0, 20.0, "rysa"))

    /** Mapa z przyjęcia: wersja 1, jeden punkt, kanoniczny plik. */
    private fun seedIntakeMap(
        points: List<DamagePoint> = intakePoints,
        documentS3Key: String? = CANONICAL_KEY
    ) {
        damageMapStore.save(
            visitId = visitId,
            studioId = studioId,
            damagePoints = points,
            vehicleType = "sedan",
            documentS3Key = documentS3Key,
            userId = userId,
            userName = "Anna Kowalska",
            bumpRevision = false
        )
    }

    private fun visit(
        status: VisitStatus = VisitStatus.IN_PROGRESS,
        damageMapFileId: String? = CANONICAL_KEY
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

    /**
     * Klucze, pod które poszedł zapis PDF-a — po jednym wpisie na wywołanie.
     * Zapis przez `answers`, a nie przez `capture(slot)`: `revisionSuffix` jest
     * typu `String?`, a slot na typie nullowalnym to osobna semantyka mockk-a,
     * której nie ma po co tu wciągać.
     */
    private val uploadedWithSuffix = mutableListOf<String?>()

    private fun stubDocumentPipeline() {
        coEvery { reportService.generateReport(any(), any(), any()) } returns pdf
        coEvery { s3.uploadDamageMap(any(), any(), any(), any()) } answers {
            val suffix = arg<String?>(3)
            uploadedWithSuffix += suffix
            if (suffix == null) CANONICAL_KEY else "$PREFIX/damage-map-$suffix.pdf"
        }
        coEvery { documentService.registerDocument(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            mockk<VisitDocument> { every { id } returns VisitDocumentId(UUID.randomUUID()) }
    }

    private fun stubCustomer(
        customerEmail: String? = "jan@example.com",
        customerPhone: String? = "534920205"
    ) {
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns
            mockk<CustomerEntity>(relaxed = true) {
                every { firstName } returns "Jan"
                every { email } returns customerEmail
                every { phone } returns customerPhone
            }
    }

    @Test
    fun `nowy plik nie nadpisuje mapy z przyjecia`() = runBlocking {
        visit()
        seedIntakeMap()
        stubDocumentPipeline()

        val result = handler.handle(command(mode = DamageMapUpdateMode.NEW_FILE))

        // Mapa z przyjęcia była wersją 1, więc aktualizacja to wersja 2 — i to ona
        // daje sufiks, czyli osobny obiekt w S3. Kanoniczny damage-map.pdf zostaje.
        assertEquals(2, result.revision)
        assertEquals(listOf("r2"), uploadedWithSuffix)
        coVerify(exactly = 0) { s3.uploadDamageMapToKey(any(), any()) }
        // Wpisu z przyjęcia w dokumentach nie ruszamy.
        io.mockk.verify(exactly = 0) { visitDocumentRepository.deleteAll(any()) }
        assertTrue(result.documentGenerated)
        assertEquals("damage-map-r2.pdf", result.fileName)
        /*
         * Wskaźnik „aktualna mapa wizyty" musi wskazać nowy plik — to jego doklejają
         * maile do klienta. Bez tego nowa mapa leżałaby w galerii, a poczta wysyłała
         * nadal wersję z przyjęcia.
         */
        io.mockk.verify {
            visitRepository.updateDamageMapFileId(
                id = visitId.value,
                studioId = studioId.value,
                fileId = "$PREFIX/damage-map-r2.pdf",
                userId = userId.value,
                now = any()
            )
        }
    }

    @Test
    fun `aktualizacja istniejacego nadpisuje plik ktory wizyta faktycznie wskazuje`() = runBlocking {
        // Wizyta wskazuje na WCZEŚNIEJSZĄ rewizję, nie na kanoniczny plik. Gdyby
        // handler szedł po kanonicznym kluczu, skasowałby mapę z przyjęcia.
        val currentKey = "$PREFIX/damage-map-r2.pdf"
        visit(damageMapFileId = currentKey)
        seedIntakeMap(documentS3Key = currentKey)
        stubDocumentPipeline()

        handler.handle(command(mode = DamageMapUpdateMode.REPLACE_EXISTING))

        coVerify { s3.uploadDamageMapToKey(currentKey, pdf) }
        assertTrue(uploadedWithSuffix.isEmpty())
        coVerify(exactly = 0) { s3.uploadDamageMap(any(), any(), any(), any()) }
    }

    @Test
    fun `punkty zapisuja sie takze wtedy gdy generowanie PDF padnie`() = runBlocking {
        visit()
        coEvery { reportService.generateReport(any(), any(), any()) } throws IllegalStateException("brak fontu")

        val result = handler.handle(command())

        assertFalse(result.documentGenerated)
        assertNull(result.documentId)
        assertEquals(1, result.pointsCount)
        // Zapis punktów poszedł, i to PRZED generowaniem pliku: mapy nie było wcale,
        // więc powstała jako wersja 1 i da się ją odczytać.
        assertEquals(1, result.revision)
        val stored = damageMapStore.load(visitId, studioId)!!
        assertEquals(listOf("rysa na masce"), stored.damagePoints.map { it.note })
        assertEquals("sedan", stored.vehicleType)
        // Audyt istnieje mimo braku pliku — ślad „kto dopisał uszkodzenie" jest ważniejszy.
        val audit = slot<LogAuditCommand>()
        coVerify { auditService.log(capture(audit)) }
        assertEquals("false", audit.captured.metadata["damageMapDocumentGenerated"])
    }

    @Test
    fun `bez zgody operatora klient nie dostaje nic`() = runBlocking {
        visit()
        seedIntakeMap()
        stubDocumentPipeline()

        val result = handler.handle(command(notify = false))

        assertNull(result.notification)
        io.mockk.verify(exactly = 0) { notifier.notifyCustomer(any()) }
    }

    @Test
    fun `na TAK idzie powiadomienie ze swiezym PDF-em i liczbami przed i po`() = runBlocking {
        visit()
        seedIntakeMap(points = intakePoints)
        stubDocumentPipeline()
        stubCustomer()
        every { notifier.notifyCustomer(any()) } returns
            DamageMapNotificationResult(emailSent = true, smsSent = false, message = "ok")

        val twoPoints = intakePoints + DamagePoint(2, 60.0, 40.0, "wgniecenie na drzwiach")
        val result = handler.handle(command(points = twoPoints, notify = true))

        assertTrue(result.notification!!.emailSent)
        val request = slot<DamageMapNotificationRequest>()
        io.mockk.verify { notifier.notifyCustomer(capture(request)) }
        // Załącznikiem jest świeży PDF, nie plik z przyjęcia.
        assertArrayEquals(pdf, request.captured.pdfBytes)
        assertEquals(1, request.captured.pointsBefore)
        assertEquals(2, request.captured.pointsAfter)
        assertEquals("jan@example.com", request.captured.recipientEmail)
    }

    @Test
    fun `zamknietej wizyty nie da sie juz domalowac`() {
        visit(status = VisitStatus.COMPLETED)
        seedIntakeMap()

        val error = assertThrows(ValidationException::class.java) {
            runBlocking { handler.handle(command()) }
        }
        assertTrue(error.message!!.contains("wizyta jest zamknięta"))
        // Mapa została taka, jaka była przy przyjęciu.
        assertEquals(1, damageMapStore.load(visitId, studioId)!!.revision)
        assertEquals(listOf("rysa"), damageMapStore.load(visitId, studioId)!!.damagePoints.map { it.note })
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
        assertNull(damageMapStore.load(visitId, studioId))
    }

    @Test
    fun `wyczyszczenie mapy do zera punktow zapisuje sie bez generowania pliku`() = runBlocking {
        visit()
        seedIntakeMap()

        val result = handler.handle(command(points = emptyList()))

        assertEquals(0, result.pointsCount)
        assertFalse(result.documentGenerated)
        assertTrue(damageMapStore.load(visitId, studioId)!!.damagePoints.isEmpty())
        coVerify(exactly = 0) { reportService.generateReport(any(), any(), any()) }
    }

    companion object {
        private const val PREFIX = "studio/visits/v"
        private const val CANONICAL_KEY = "studio/visits/v/damage-map.pdf"
    }
}
