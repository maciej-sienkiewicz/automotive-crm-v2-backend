package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.shared.CrmDataKey
import pl.detailing.crm.protocol.infrastructure.CrmDataResolver
import pl.detailing.crm.shared.DocumentType
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.LocalDate

/**
 * Kiedy protokół staje się dokumentem wizyty — i pod jaką nazwą.
 *
 * Zgłoszenie z warsztatu: pracownik klikał „Wydaj pojazd", potem „Pomiń podpis
 * i przejdź do płatności", zamykał okno — i w dokumentach wizyty zostawał
 * `08-09-2026_Mercedes-benz_Klasa-S_Lux_wydanie`. Nikt tego pliku nie zamówił: powstał
 * dlatego, że ekran wydania został otwarty. Niepodpisany protokół wydania to pusty
 * formularz, a nie dokument sprawy.
 *
 * Reguła jest więc taka sama, jaką zgody mają od dawna (patrz `SubmitSignatureHandler`,
 * „klient, który zgody nie podpisał, nie zostawia po sobie pustego formularza"):
 *
 *  - **protokół przyjęcia** (CHECK_IN) trafia do dokumentów od razu po wygenerowaniu —
 *    opisuje stan auta w chwili przyjęcia i ma wartość także bez podpisu (jedzie
 *    w załączniku e-maila powitalnego, zanim ktokolwiek go podpisze);
 *  - **protokół wydania** (CHECK_OUT) i **zgoda** trafiają tam DOPIERO po podpisaniu.
 *    Ich jedyną treścią jest potwierdzenie klienta, więc bez podpisu nie ma czego
 *    dokumentować.
 */
@Service
class VisitProtocolDocumentRegistrar(
    private val visitRepository: VisitRepository,
    private val crmDataResolver: CrmDataResolver,
    private val documentService: DocumentService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Rejestruje protokół jako dokument wizyty. Awaria zapisu nie może wywrócić operacji,
     * w trakcie której powstał: podpis klienta jest faktem, a brakujący wiersz w liście
     * dokumentów da się odtworzyć.
     */
    suspend fun register(
        protocol: VisitProtocol,
        s3Key: String,
        fileExtension: String
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val visitEntity = visitRepository.findById(protocol.visitId.value).orElse(null)
            if (visitEntity == null) {
                logger.warn("Could not register protocol as document — visit not found: ${protocol.visitId}")
                return@withContext
            }
            val crmData = crmDataResolver.resolveVisitData(protocol.visitId, protocol.studioId)
            val documentName = documentName(protocol, visitEntity.brandSnapshot, visitEntity.modelSnapshot, crmData, visitEntity.visitNumber)

            documentService.registerDocument(
                visitId = protocol.visitId.value,
                customerId = visitEntity.customerId,
                documentType = DocumentType.PROTOCOL,
                name = documentName,
                s3Key = s3Key,
                fileName = "$documentName.$fileExtension",
                createdBy = visitEntity.createdBy,
                createdByName = "System",
                category = "protocol"
            )
        } catch (e: Exception) {
            logger.error("Failed to register protocol as document: ${e.message}", e)
        }
    }

    /** Nazwa dokumentu w wizycie — patrz [ProtocolDocumentNaming]. */
    fun documentName(
        protocol: VisitProtocol,
        brand: String?,
        model: String?,
        crmData: Map<CrmDataKey, String>,
        visitNumber: String
    ): String {
        val stageLabel = stageLabel(protocol.stage)
        val name = ProtocolDocumentNaming.build(
            LocalDate.now(),
            brand,
            model,
            ProtocolDocumentNaming.surnameOf(crmData[CrmDataKey.CUSTOMER_FULL_NAME]),
            stageLabel
        )

        // Kolejne wersje tego samego protokołu muszą się różnić, inaczej w liście
        // dokumentów stoją dwa identyczne wiersze.
        val suffix = if (protocol.version > 1) "_v${protocol.version}" else ""
        return name.ifBlank { "protokol_${ProtocolDocumentNaming.slug(visitNumber)}_$stageLabel" } + suffix
    }

    companion object {
        fun stageLabel(stage: ProtocolStage): String = when (stage) {
            ProtocolStage.CHECK_IN -> "przyjecie"
            ProtocolStage.CHECK_OUT -> "wydanie"
        }

        /**
         * Czy protokół tego etapu staje się dokumentem wizyty już w chwili wygenerowania.
         *
         * Tylko przyjęcie. Wydanie czeka na podpis — inaczej samo otwarcie ekranu
         * „Wydaj pojazd" zostawiało w wizycie plik, którego nikt nie zamawiał.
         */
        fun becomesDocumentOnGeneration(stage: ProtocolStage): Boolean = stage == ProtocolStage.CHECK_IN
    }
}
