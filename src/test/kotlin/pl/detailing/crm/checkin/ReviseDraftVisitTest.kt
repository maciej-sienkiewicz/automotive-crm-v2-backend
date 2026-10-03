package pl.detailing.crm.checkin

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolEntity
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.protocol.visitprotocol.GenerateVisitProtocolsHandler
import pl.detailing.crm.protocol.visitprotocol.GenerateVisitProtocolsResult
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.signing.SignatureRequestLifecycleService
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Zgłoszenie biznesu z 03.10: przy podpisie klient mówi „dorzućmy jeszcze renowację
 * kierownicy", a z okna dokumentów nie dało się wrócić do formularza. Teraz „Wróć do
 * formularza" zostawia szkic (ze zdjęciami), podmienia jego usługi i generuje dokumenty
 * przyjęcia od nowa.
 */
class ReviseDraftVisitTest {

    private val studioId = StudioId.random()
    private val visitId = VisitId.random()
    private val userId = UserId.random()

    private val visitRepository = mockk<VisitRepository>(relaxed = true)
    private val appointmentRepository = mockk<AppointmentRepository>(relaxed = true)

    private val checkin = CreateVisitFromReservationHandler(
        visitNumberGenerator = mockk(relaxed = true),
        visitRepository = visitRepository,
        appointmentRepository = appointmentRepository,
        customerRepository = mockk(relaxed = true),
        vehicleRepository = mockk(relaxed = true),
        vehicleOwnerRepository = mockk(relaxed = true),
        damageMapReportService = mockk(relaxed = true),
        visitDamageMapStore = mockk(relaxed = true),
        damageMarkingService = mockk(relaxed = true),
        s3DamageMapStorageService = mockk(relaxed = true),
        documentService = mockk(relaxed = true),
        serviceRepository = mockk(relaxed = true),
        photoSessionService = mockk(relaxed = true),
        checkinPhotoService = mockk(relaxed = true),
        checkinDamagePointsService = mockk(relaxed = true),
        uploadContextTokenService = mockk(relaxed = true),
        auditService = mockk(relaxed = true),
        businessEventPublisher = mockk(relaxed = true),
        appointmentCommunicationLinker = mockk(relaxed = true),
        doorToDoorRepository = mockk(relaxed = true),
        appointmentVehicleResolver = mockk(relaxed = true),
        openDraftVisitService = mockk(relaxed = true),
        appointmentColorRepository = mockk(relaxed = true),
    )

    private fun draftEntity(status: VisitStatus = VisitStatus.DRAFT): VisitEntity {
        val domain = VisitFixtures.visit(studioId = studioId, status = status)
        return mockk(relaxed = true) {
            every { this@mockk.status } returns status
            every { appointmentId } returns UUID.randomUUID()
            every { serviceItems } returns mutableListOf()
            every { toDomain(any()) } returns domain
        }
    }

    private fun line(net: Long, gross: Long?, name: String) = ServiceLineItemRequest(
        id = name, serviceId = null, serviceName = name, basePriceNet = net, basePriceGross = gross,
        vatRate = 23, adjustment = AdjustmentRequest(type = "PERCENT", value = 0.0), note = null,
    )

    private fun command(services: List<ServiceLineItemRequest>) = ReviseDraftServicesCommand(
        visitId = visitId, studioId = studioId, userId = userId, userName = "Anna", services = services,
    )

    @Test
    fun `szkic dostaje nowe uslugi, a brutto wpisane przez czlowieka nie plywa`() {
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns draftEntity()
        every { appointmentRepository.findByIdAndStudioId(any(), any()) } returns null
        val saved = slot<VisitEntity>()
        every { visitRepository.save(capture(saved)) } answers { firstArg() }

        runBlocking {
            checkin.reviseDraftServices(command(listOf(
                line(154_472, 190_000, "Powłoka ceramiczna"),
                line(40_650, 50_000, "Renowacja kierownicy"),
            )))
        }

        val items = saved.captured.toDomain().serviceItems
        assertEquals(listOf("Powłoka ceramiczna", "Renowacja kierownicy"), items.map { it.serviceName })
        // 1900,00 zł zostaje 1900,00 zł, a nie 1900,01 zł (CLAUDE.md §1).
        assertEquals(listOf(190_000L, 50_000L), items.map { it.finalPriceGross.amountInCents })
    }

    @Test
    fun `wizyty juz rozpoczetej nie poprawia sie z formularza przyjecia`() {
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns draftEntity(VisitStatus.IN_PROGRESS)

        assertThrows<ValidationException> {
            runBlocking { checkin.reviseDraftServices(command(listOf(line(10_000, 12_300, "Mycie")))) }
        }
        verify(exactly = 0) { visitRepository.save(any<VisitEntity>()) }
    }

    @Test
    fun `bez uslug nie ma przyjecia`() {
        assertThrows<ValidationException> { runBlocking { checkin.reviseDraftServices(command(emptyList())) } }
    }

    @Test
    fun `dokumenty przyjecia powstaja od nowa, a stare schodza z tabletu`() {
        val revise = mockk<CreateVisitFromReservationHandler>()
        val protocols = mockk<VisitProtocolRepository>(relaxed = true)
        val storage = mockk<S3ProtocolStorageService>(relaxed = true)
        val signatures = mockk<SignatureRequestLifecycleService>(relaxed = true)
        val generate = mockk<GenerateVisitProtocolsHandler>()
        val old = mockk<VisitProtocolEntity>(relaxed = true) {
            every { filledPdfS3Key } returns "old/filled.pdf"
            every { signedPdfS3Key } returns "old/signed.pdf"
            every { signatureImageS3Key } returns null
        }
        val fresh = mockk<VisitProtocol>()
        coEvery { revise.reviseDraftServices(any()) } returns Unit
        every { protocols.findAllByVisitIdAndStudioIdAndStage(visitId.value, studioId.value, ProtocolStage.CHECK_IN) } returns listOf(old)
        coEvery { generate.handle(any()) } returns GenerateVisitProtocolsResult(listOf(fresh))

        val result = runBlocking {
            ReviseDraftVisitHandler(revise, protocols, storage, signatures, generate)
                .handle(command(listOf(line(10_000, 12_300, "Mycie"))))
        }

        assertEquals(listOf(fresh), result)
        coVerifyOrder {
            revise.reviseDraftServices(any())
            protocols.deleteAll(listOf(old))
            signatures.cancelActiveForVisit(studioId, visitId.value, "Anna")
            generate.handle(match { it.visitId == visitId && it.stage == ProtocolStage.CHECK_IN })
        }
        coVerify { storage.deleteFile("old/filled.pdf") }
        coVerify { storage.deleteFile("old/signed.pdf") }
    }
}
