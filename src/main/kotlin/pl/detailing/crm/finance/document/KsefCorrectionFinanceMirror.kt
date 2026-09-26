package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.LocalDate

/**
 * Faktura korygująca KSeF wystawiona ręcznie (moduł KSeF) dostaje odbicie w dokumentach
 * finansowych: korektę z tymi samymi kwotami ze znakiem, formą płatności i statusem
 * dokumentu faktury pierwotnej.
 *
 * Raport według formy płatności czyta wyłącznie dokumenty finansowe. Korekta faktury
 * żyła tylko w rejestrze KSeF, więc po korekcie do zera raport dalej pokazywał pełną
 * kwotę faktury w gotówce albo na karcie. Dokument korekty jest powiązany z fakturą
 * korygującą (ksefRevenueInvoiceId), więc lista przychodów i kafle — które liczą faktury
 * z rejestru KSeF — nie liczą go drugi raz.
 */
@Service
class KsefCorrectionFinanceMirror(
    private val documentRepository: FinancialDocumentRepository,
    private val createHandler: CreateFinancialDocumentHandler
) {
    private val log = LoggerFactory.getLogger(KsefCorrectionFinanceMirror::class.java)

    @Transactional
    fun mirror(correction: KsefRevenueInvoiceEntity, userId: UserId, userDisplayName: String) {
        if (correction.invoiceType != RevenueInvoiceType.KOR) return
        val originalId = correction.originalInvoiceId ?: return
        val original = documentRepository.findActiveByKsefInvoice(correction.studioId, originalId).firstOrNull()
            ?: return // faktura spoza wydania wizyty — nie ma dokumentu, który trzeba poprawić

        createHandler.handle(
            CreateFinancialDocumentCommand(
                studioId             = StudioId(correction.studioId),
                userId               = userId,
                userDisplayName      = userDisplayName,
                source               = original.source,
                visitId              = original.visitId?.let { VisitId(it) },
                vehicleBrand         = original.vehicleBrand,
                vehicleModel         = original.vehicleModel,
                customerFirstName    = original.customerFirstName,
                customerLastName     = original.customerLastName,
                documentType         = DocumentType.CORRECTION,
                direction            = DocumentDirection.INCOME,
                paymentMethod        = original.paymentMethod,
                totalNet             = correction.totalNet,
                totalVat             = correction.totalVat,
                totalGross           = correction.totalGross,
                issueDate            = correction.issueDate,
                dueDate              = correction.issueDate,
                description          = "Korekta ${correction.invoiceNumber} do ${original.documentNumber}",
                counterpartyName     = original.counterpartyName,
                counterpartyNip      = original.counterpartyNip,
                correctsDocumentId   = original.id,
                ksefRevenueInvoiceId = correction.id,
                statusOverride       = original.status
            )
        )
        log.info("Korekta KSeF {} odbita w dokumentach finansowych (do {})", correction.invoiceNumber, original.documentNumber)
    }
}
