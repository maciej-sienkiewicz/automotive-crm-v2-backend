package pl.detailing.crm.finance.external

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

/**
 * Cykl życia zgłoszeń dla księgowości.
 *
 * Zgłoszenie, które przestaje obowiązywać (poprawka rozliczenia), nie znika:
 *  - czekające (PENDING) jest wycofywane — księgowość nie ma czego wystawiać,
 *  - wystawione (ISSUED) rodzi zgłoszenie korekty — faktura istnieje w KSeF
 *    i tylko księgowość może ją skorygować.
 */
@Service
class ExternalInvoiceRequestService(
    private val repository: ExternalInvoiceRequestRepository,
    private val auditService: AuditService
) {

    /** Nowe zgłoszenie do dokumentu — kwoty dokumentu w chwili zgłoszenia. */
    fun open(
        document: FinancialDocument,
        kind: ExternalInvoiceKind,
        buyer: ExternalInvoiceBuyer,
        userId: UUID,
        settlementCorrectionId: UUID? = null,
        now: Instant = Instant.now()
    ): ExternalInvoiceRequestEntity = repository.save(
        ExternalInvoiceRequestEntity(
            studioId = document.studioId.value,
            visitId = document.visitId?.value,
            financialDocumentId = document.id.value,
            kind = kind,
            buyerNip = buyer.normalizedNip,
            buyerName = buyer.name?.trim()?.ifBlank { null }?.take(255),
            buyerAddressLine1 = buyer.addressLine1?.trim()?.ifBlank { null }?.take(255),
            buyerAddressLine2 = buyer.addressLine2?.trim()?.ifBlank { null }?.take(255),
            buyerEmail = buyer.email?.trim()?.ifBlank { null }?.take(255),
            totalNet = document.totalNet,
            totalVat = document.totalVat,
            totalGross = document.totalGross,
            settlementCorrectionId = settlementCorrectionId,
            createdBy = userId,
            createdAt = now,
            updatedAt = now
        )
    )

    /**
     * Zgłoszenie przestaje obowiązywać, bo jego dokument zastąpiono w poprawce rozliczenia.
     *
     * @param stornoDocumentId storno zastąpionego dokumentu — do niego przypina się korekta
     * @return zgłoszenie korekty albo null, gdy wystarczyło wycofanie
     */
    fun retire(
        request: ExternalInvoiceRequestEntity,
        stornoDocumentId: UUID,
        userId: UUID,
        settlementCorrectionId: UUID,
        now: Instant = Instant.now()
    ): ExternalInvoiceRequestEntity? {
        return when (request.status) {
            ExternalInvoiceStatus.WITHDRAWN -> null
            ExternalInvoiceStatus.PENDING -> {
                request.status = ExternalInvoiceStatus.WITHDRAWN
                request.withdrawnAt = now
                request.settlementCorrectionId = settlementCorrectionId
                request.updatedAt = now
                repository.save(request)
                null
            }
            ExternalInvoiceStatus.ISSUED -> repository.save(
                ExternalInvoiceRequestEntity(
                    studioId = request.studioId,
                    visitId = request.visitId,
                    financialDocumentId = stornoDocumentId,
                    kind = ExternalInvoiceKind.CORRECTION,
                    correctsRequestId = request.id,
                    buyerNip = request.buyerNip,
                    buyerName = request.buyerName,
                    buyerAddressLine1 = request.buyerAddressLine1,
                    buyerAddressLine2 = request.buyerAddressLine2,
                    buyerEmail = request.buyerEmail,
                    totalNet = -request.totalNet,
                    totalVat = -request.totalVat,
                    totalGross = -request.totalGross,
                    settlementCorrectionId = settlementCorrectionId,
                    createdBy = userId,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    /** Zmiana samej formy płatności: dokument jest nowy, faktura księgowości ta sama. */
    fun moveTo(request: ExternalInvoiceRequestEntity, documentId: UUID, settlementCorrectionId: UUID, now: Instant = Instant.now()) {
        request.financialDocumentId = documentId
        request.settlementCorrectionId = settlementCorrectionId
        request.updatedAt = now
        repository.save(request)
    }

    /**
     * Usunięcie dokumentu w Finansach: czekające zgłoszenie jest wycofywane. Zgłoszenia
     * z fakturą już wystawioną blokują usunięcie — faktura księgowości istnieje w KSeF
     * i skasowanie zapisu w CRM niczego by nie skorygowało.
     */
    fun withdrawForDeletedDocument(studioId: UUID, documentId: UUID, now: Instant = Instant.now()) {
        val requests = repository.findActiveByDocuments(studioId, listOf(documentId))
        if (requests.any { it.status == ExternalInvoiceStatus.ISSUED }) {
            throw ConflictException(
                "Do tej sprzedaży księgowość wystawiła już fakturę. Zmień rozliczenie przez „Popraw rozliczenie” w wizycie — " +
                    "wtedy na liście „Do zafakturowania” pojawi się zgłoszenie korekty."
            )
        }
        requests.forEach {
            it.status = ExternalInvoiceStatus.WITHDRAWN
            it.withdrawnAt = now
            it.updatedAt = now
            repository.save(it)
        }
    }

    /** Przywrócenie dokumentu przywraca zgłoszenia wycofane jego usunięciem (nie poprawką rozliczenia). */
    fun reopenForRestoredDocument(studioId: UUID, documentId: UUID) {
        repository.findWithdrawnByDocument(studioId, documentId)
            .filter { it.settlementCorrectionId == null && it.kind != ExternalInvoiceKind.CORRECTION }
            .forEach {
                it.status = ExternalInvoiceStatus.PENDING
                it.withdrawnAt = null
                it.updatedAt = Instant.now()
                repository.save(it)
            }
    }

    /** „Faktura wystawiona" — odhacza człowiek; numer jest opcjonalny. */
    @Transactional
    fun markIssued(
        studioId: StudioId,
        requestId: UUID,
        invoiceNumber: String?,
        userId: UserId,
        userName: String
    ): ExternalInvoiceRequestEntity {
        val request = repository.findByIdAndStudioId(requestId, studioId.value)
            ?: throw NotFoundException("Zgłoszenie nie zostało znalezione")
        if (request.status == ExternalInvoiceStatus.WITHDRAWN) {
            throw ConflictException("Tej sprzedaży nie trzeba już fakturować: rozliczenie wizyty zostało poprawione.")
        }
        val number = invoiceNumber?.trim()?.ifBlank { null }
        if (number != null && number.length > 100) throw ValidationException("Numer faktury może mieć najwyżej 100 znaków")

        val before = request.status
        val numberBefore = request.externalInvoiceNumber
        val now = Instant.now()
        request.status = ExternalInvoiceStatus.ISSUED
        request.externalInvoiceNumber = number
        request.issuedAt = request.issuedAt.takeIf { before == ExternalInvoiceStatus.ISSUED } ?: now
        request.issuedBy = userId.value
        request.issuedByName = userName.ifBlank { null }?.take(255)
        request.updatedAt = now
        repository.save(request)

        audit(request, userId, userName, AuditAction.EXTERNAL_INVOICE_MARKED, buildList {
            if (before != request.status) add(FieldChange("status", before.displayName, request.status.displayName))
            if (numberBefore != number) add(FieldChange("externalInvoiceNumber", numberBefore, number))
        })
        return request
    }

    /** Cofnięcie odhaczenia — pomyłka albo księgowość jednak jeszcze nie wystawiła. */
    @Transactional
    fun unmarkIssued(studioId: StudioId, requestId: UUID, userId: UserId, userName: String): ExternalInvoiceRequestEntity {
        val request = repository.findByIdAndStudioId(requestId, studioId.value)
            ?: throw NotFoundException("Zgłoszenie nie zostało znalezione")
        if (request.status != ExternalInvoiceStatus.ISSUED) return request
        if (request.id in correctedIds(request)) {
            throw ConflictException("Do tej faktury zgłoszono już korektę, więc nie da się cofnąć jej wystawienia.")
        }
        val numberBefore = request.externalInvoiceNumber
        request.status = ExternalInvoiceStatus.PENDING
        request.issuedAt = null
        request.issuedBy = null
        request.issuedByName = null
        request.externalInvoiceNumber = null
        request.updatedAt = Instant.now()
        repository.save(request)

        audit(request, userId, userName, AuditAction.EXTERNAL_INVOICE_UNMARKED, listOfNotNull(
            FieldChange("status", ExternalInvoiceStatus.ISSUED.displayName, ExternalInvoiceStatus.PENDING.displayName),
            numberBefore?.let { FieldChange("externalInvoiceNumber", it, null) }
        ))
        return request
    }

    private fun correctedIds(request: ExternalInvoiceRequestEntity): Set<UUID> =
        request.visitId
            ?.let { repository.findByStudioIdAndVisitIdOrderByCreatedAtAsc(request.studioId, it) }
            .orEmpty()
            .mapNotNull { it.correctsRequestId }
            .toSet()

    private fun audit(
        request: ExternalInvoiceRequestEntity,
        userId: UserId,
        userName: String,
        action: AuditAction,
        changes: List<FieldChange>
    ) {
        auditService.logSync(
            LogAuditCommand(
                studioId = StudioId(request.studioId),
                userId = userId,
                userDisplayName = userName,
                module = AuditModule.FINANCE,
                entityId = request.financialDocumentId.toString(),
                entityDisplayName = listOfNotNull(request.kind.displayName, request.buyerName).joinToString(": "),
                action = action,
                changes = changes,
                amount = AuditAmount.ofGrosz(request.totalGross),
                context = request.visitId?.let { AuditContext(visitId = VisitId(it)) } ?: AuditContext.EMPTY,
                metadata = mapOf("externalInvoiceRequestId" to request.id.toString(), "kind" to request.kind.name)
            )
        )
    }
}
