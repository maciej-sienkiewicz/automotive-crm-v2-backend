package pl.detailing.crm.checkin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.protocol.visitprotocol.GenerateVisitProtocolsCommand
import pl.detailing.crm.protocol.visitprotocol.GenerateVisitProtocolsHandler
import pl.detailing.crm.signing.SignatureRequestLifecycleService

/**
 * „Wróć do formularza" → poprawione usługi → „Utwórz wizytę": ten sam szkic z nowymi
 * usługami i nowymi dokumentami przyjęcia.
 *
 * Dokumenty muszą powstać od nowa, bo protokół przyjęcia wypisuje usługi i ceny -
 * podpis pod starym byłby podpisem pod czymś innym niż uzgodniono. Dlatego stare
 * protokoły (także już podpisane) znikają razem z plikami, a ich żądania podpisu
 * schodzą z tabletu, zanim powstaną nowe - inaczej tablet podałby klientowi dokument
 * ze starą listą usług.
 */
@Service
class ReviseDraftVisitHandler(
    private val createVisitFromReservationHandler: CreateVisitFromReservationHandler,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val s3ProtocolStorageService: S3ProtocolStorageService,
    private val signatureRequestLifecycleService: SignatureRequestLifecycleService,
    private val generateVisitProtocolsHandler: GenerateVisitProtocolsHandler
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: ReviseDraftServicesCommand): List<VisitProtocol> {
        // Waliduje szkic (DRAFT, ta sama pracownia) i podmienia usługi.
        createVisitFromReservationHandler.reviseDraftServices(command)

        val oldProtocols = withContext(Dispatchers.IO) {
            visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value, command.studioId.value, ProtocolStage.CHECK_IN
            ).also { visitProtocolRepository.deleteAll(it) }
        }

        try {
            signatureRequestLifecycleService.cancelActiveForVisit(
                command.studioId, command.visitId.value, command.userName ?: "System"
            )
        } catch (e: Exception) {
            // Kolejka tabletu i tak odrzuca żądania protokołów, których już nie ma.
            logger.error("Failed to cancel signature requests of revised draft ${command.visitId}: ${e.message}", e)
        }

        oldProtocols
            .flatMap { listOfNotNull(it.filledPdfS3Key, it.signedPdfS3Key, it.signatureImageS3Key) }
            .forEach { key ->
                try {
                    s3ProtocolStorageService.deleteFile(key)
                } catch (e: Exception) {
                    logger.error("Failed to delete old protocol file $key: ${e.message}", e)
                }
            }

        return generateVisitProtocolsHandler.handle(
            GenerateVisitProtocolsCommand(
                visitId = command.visitId,
                studioId = command.studioId,
                stage = ProtocolStage.CHECK_IN
            )
        ).protocols
    }
}
