package pl.detailing.crm.batchorder.report

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderCloseHistoryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
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
    val emailTo: String?
)

data class CloseMonthResult(
    val historyId: String,
    val entryCount: Int,
    val totalNetCents: Long,
    val totalGrossCents: Long,
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
    val closedAt: String
)

@Service
class CloseMonthHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository,
    private val closeHistoryRepository: BatchOrderCloseHistoryRepository,
    private val generateBatchReportHandler: GenerateBatchReportHandler,
    private val getEmailTemplateConfigHandler: GetEmailTemplateConfigHandler,
    private val emailProvider: EmailProvider,
    private val studioSettingsRepository: StudioSettingsRepository
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

        if (entriesToClose.isEmpty()) {
            throw EntityNotFoundException("Brak wpisów do zamknięcia w wybranym okresie")
        }

        val historyId = UUID.randomUUID()
        val totalNet = entriesToClose.sumOf { it.netAmountCents }
        val totalGross = entriesToClose.sumOf { it.grossAmountCents }

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
            emailTo = command.emailTo,
            closedAt = Instant.now()
        )
        closeHistoryRepository.save(historyEntity)

        entriesToClose.forEach { entry ->
            entry.isClosed = true
            entry.closeHistoryId = historyId
            entry.updatedAt = Instant.now()
        }
        entryRepository.saveAll(entriesToClose)

        var emailSent = false
        if (!command.emailTo.isNullOrBlank()) {
            runCatching {
                val pdfBytes = generateBatchReportHandler.buildPdfBytes(
                    contractorName = contractor.name,
                    contractorTaxId = contractor.taxId,
                    from = command.from,
                    to = command.to,
                    entries = entriesToClose,
                    studioId = command.studioId
                )

                val emailConfig = getEmailTemplateConfigHandler.handle(command.studioId)
                val rule = emailConfig.batchOrderClose

                val studioName = studioSettingsRepository.findById(command.studioId.value).orElse(null)?.name
                    ?: "Studio"

                val periodFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                val period = "${command.from.format(periodFmt)} – ${command.to.format(periodFmt)}"
                val grossFormatted = "%.2f zł".format(totalGross / 100.0)

                fun processTemplate(template: String): String = template
                    .replace("{{kontrahent}}", contractor.name)
                    .replace("{{okres}}", period)
                    .replace("{{kwota_brutto}}", grossFormatted)
                    .replace("{{liczba_wpisow}}", entriesToClose.size.toString())
                    .replace("{{studio}}", studioName)

                val subject = processTemplate(rule.subjectTemplate)
                val body = processTemplate(rule.bodyTemplate)

                val fileName = "zestawienie-${contractor.name.replace(Regex("\\s+"), "-")}-${command.from.format(DateTimeFormatter.ofPattern("yyyy-MM"))}.pdf"

                emailProvider.send(
                    to = command.emailTo,
                    subject = subject,
                    bodyText = body,
                    attachments = listOf(EmailAttachment(fileName, pdfBytes, "application/pdf"))
                )
                emailSent = true
            }.onFailure { /* email failure is non-fatal — history is already saved */ }

            if (emailSent) {
                val updated = BatchOrderCloseHistoryEntity(
                    id = historyEntity.id,
                    studioId = historyEntity.studioId,
                    contractorId = historyEntity.contractorId,
                    fromDate = historyEntity.fromDate,
                    toDate = historyEntity.toDate,
                    mode = historyEntity.mode,
                    entryCount = historyEntity.entryCount,
                    totalNetCents = historyEntity.totalNetCents,
                    totalGrossCents = historyEntity.totalGrossCents,
                    emailSent = true,
                    emailTo = historyEntity.emailTo,
                    closedAt = historyEntity.closedAt,
                    createdAt = historyEntity.createdAt
                )
                closeHistoryRepository.save(updated)
            }
        }

        return CloseMonthResult(
            historyId = historyId.toString(),
            entryCount = entriesToClose.size,
            totalNetCents = totalNet,
            totalGrossCents = totalGross,
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
        closedAt = closedAt.toString()
    )
}
