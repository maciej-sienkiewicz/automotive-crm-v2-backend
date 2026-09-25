package pl.detailing.crm.batchorder.report

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderCloseHistoryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class CloseMode { ALL, NEW_ONLY }

data class CloseMonthCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val from: LocalDate,
    val to: LocalDate,
    val mode: CloseMode,
    val sendEmail: Boolean = false,
    val emailOverride: String? = null,
    /** Kto rozlicza — imię i nazwisko zapisywane w historii tekstem, jak przy zdjęciach. */
    val closedByUserName: String? = null
)

data class CloseMonthResult(
    val historyId: String,
    val entryCount: Int,
    val totalNetCents: Long,
    val totalGrossCents: Long,
    /** Użytkownik prosił o wysyłkę — false przy [emailSent] znaczy, że mail nie wyszedł. */
    val emailRequested: Boolean,
    val emailSent: Boolean
)

data class CloseHistoryItem(
    val id: String,
    val contractorId: String,
    val fromDate: String,
    val toDate: String,
    val mode: String,
    val entryCount: Int,
    val totalNetCents: Long,
    val totalGrossCents: Long,
    val emailSent: Boolean,
    val emailTo: String?,
    val closedAt: String,
    val closedByUserName: String?
)

@Service
class CloseMonthHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository,
    private val closeHistoryRepository: BatchOrderCloseHistoryRepository,
    private val generateBatchReportHandler: GenerateBatchReportHandler,
    private val getEmailTemplateConfigHandler: GetEmailTemplateConfigHandler,
    private val gateway: OutboundCommunicationGateway,
    private val renderer: MessageTemplateRenderer,
    private val transactionTemplate: TransactionTemplate
) {
    @Transactional
    suspend fun handle(command: CloseMonthCommand): CloseMonthResult {
        val contractor = contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        val entriesToClose = when (command.mode) {
            CloseMode.ALL -> entryRepository.findByContractorIdAndStudioIdAndDateRange(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value,
                from = command.from,
                to = command.to
            )
            CloseMode.NEW_ONLY -> entryRepository.findOpenByContractorIdAndStudioIdAndDateRange(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value,
                from = command.from,
                to = command.to
            )
        }

        // Pusty wybór to błąd żądania (zły okres, wszystko już rozliczone), nie brak zasobu —
        // 404 wyglądało w interfejsie jak zniknięty kontrahent.
        if (entriesToClose.isEmpty()) {
            throw ValidationException("Brak wpisów do rozliczenia w wybranym okresie.")
        }

        val historyId = UUID.randomUUID()
        val totalNet = entriesToClose.sumOf { it.netAmountCents }
        val totalGross = entriesToClose.sumOf { it.grossAmountCents }

        val resolvedEmailTo = when {
            !command.sendEmail -> null
            !command.emailOverride.isNullOrBlank() -> command.emailOverride
            !contractor.email.isNullOrBlank() -> contractor.email
            else -> null
        }

        // Okno rozliczenia obiecuje „Adres zostanie zapisany do karty kontrahenta". Tylko gdy
        // karta nie ma adresu: jednorazowa wysyłka pod inny adres nie nadpisuje istniejącego.
        val emailToRemember = command.emailOverride?.trim()
            ?.takeIf { command.sendEmail && it.isNotEmpty() && contractor.email.isNullOrBlank() }

        val now = Instant.now()
        // Z tego samego snapshotu powstaje PDF w mailu i później PDF z historii — kontrahent
        // i pracownia patrzą na ten sam dokument.
        val snapshot = SettlementSnapshot.of(entriesToClose)
        val historyEntity = BatchOrderCloseHistoryEntity(
            id = historyId,
            studioId = command.studioId.value,
            contractorId = command.contractorId.value,
            fromDate = command.from,
            toDate = command.to,
            mode = command.mode.name,
            entryCount = entriesToClose.size,
            totalNetCents = totalNet,
            totalGrossCents = totalGross,
            emailSent = false,
            emailTo = resolvedEmailTo,
            closedAt = now,
            snapshotJson = snapshot.toJson(),
            closedByUserName = command.closedByUserName?.trim()?.takeIf { it.isNotEmpty() }
        )
        // The close record and the entries it closes must land together. Split across
        // two commits — which is what a `@Transactional suspend` function gives you,
        // see AuditLogWriter — a failure here could record a closed month whose entries
        // stayed open, and the same work would be billed again next time.
        transactionTemplate.execute {
            closeHistoryRepository.save(historyEntity)

            entriesToClose.forEach { it.markSettled(historyId, now) }
            entryRepository.saveAll(entriesToClose)

            if (emailToRemember != null) {
                contractor.email = emailToRemember
                contractor.updatedAt = now
                contractorRepository.save(contractor)
            }
        }

        var emailSent = false
        if (resolvedEmailTo != null) {
            runCatching {
                val pdfBytes = generateBatchReportHandler.buildPdfBytes(
                    contractorName = contractor.name,
                    contractorTaxId = contractor.taxId,
                    from = command.from,
                    to = command.to,
                    rows = snapshot.entries,
                    studioId = command.studioId
                )

                val rule = getEmailTemplateConfigHandler.handle(command.studioId).batchOrderClose
                check(rule.sendable) { "Szablon zestawienia zbiorczego nie jest skonfigurowany" }

                val periodFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                val values = mapOf(
                    "kontrahent" to contractor.name,
                    "okres" to "${command.from.format(periodFmt)} – ${command.to.format(periodFmt)}",
                    "kwota_brutto" to "%.2f zł".format(totalGross / 100.0),
                    "liczba_wpisow" to entriesToClose.size.toString()
                )

                val subject = renderer.render(rule.subjectTemplate, values)
                val body = renderer.render(rule.bodyTemplate, values)

                val fileName = "zestawienie-${contractor.name.replace(Regex("\\s+"), "-")}-${command.from.format(DateTimeFormatter.ofPattern("yyyy-MM"))}.pdf"

                gateway.sendTransactionalEmail(
                    studioId = command.studioId.value,
                    to = resolvedEmailTo,
                    subject = subject,
                    bodyText = body,
                    attachments = listOf(EmailAttachment(fileName, pdfBytes, "application/pdf"))
                )
                emailSent = true
            }.onFailure { /* email failure is non-fatal — history record is already saved */ }

            if (emailSent) {
                // Ten sam rekord, nie przepisana kopia: kopia pole po polu gubiła każdą
                // kolumnę dodaną później (snapshot, rozliczający) przy pierwszym udanym mailu.
                historyEntity.emailSent = true
                closeHistoryRepository.save(historyEntity)
            }
        }

        return CloseMonthResult(
            historyId = historyId.toString(),
            entryCount = entriesToClose.size,
            totalNetCents = totalNet,
            totalGrossCents = totalGross,
            emailRequested = command.sendEmail,
            emailSent = emailSent
        )
    }

    @Transactional(readOnly = true)
    fun listHistory(contractorId: BatchContractorId, studioId: StudioId): List<CloseHistoryItem> {
        return closeHistoryRepository.findByContractorIdAndStudioId(contractorId.value, studioId.value)
            .map { it.toItem() }
    }

    private fun BatchOrderCloseHistoryEntity.toItem() = CloseHistoryItem(
        id = id.toString(),
        contractorId = contractorId.toString(),
        fromDate = fromDate.toString(),
        toDate = toDate.toString(),
        mode = mode,
        entryCount = entryCount,
        totalNetCents = totalNetCents,
        totalGrossCents = totalGrossCents,
        emailSent = emailSent,
        emailTo = emailTo,
        closedAt = closedAt.toString(),
        closedByUserName = closedByUserName
    )
}
