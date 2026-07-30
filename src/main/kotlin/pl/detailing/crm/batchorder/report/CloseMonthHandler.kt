package pl.detailing.crm.batchorder.report

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class CloseMonthHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository,
    private val closeHistoryRepository: BatchOrderCloseHistoryRepository,
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler,
    private val emailProvider: EmailProvider
) {
    private val log = LoggerFactory.getLogger(CloseMonthHandler::class.java)

    @Transactional
    suspend fun handle(command: CloseMonthCommand): CloseMonthResult {
        val contractor = contractorRepository.findByIdAndStudioId(
            command.contractorId.value, command.studioId.value
        ) ?: throw EntityNotFoundException("Contractor not found")

        val allEntries = if (command.from != null && command.to != null) {
            entryRepository.findByContractorIdAndStudioIdAndDateRange(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value,
                from = command.from,
                to = command.to
            )
        } else {
            entryRepository.findByContractorIdAndStudioId(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value
            )
        }

        val entriesToClose = when (command.mode) {
            "NEW_ONLY" -> allEntries.filter { !it.isClosed }
            else       -> allEntries
        }

        val now = Instant.now()
        entriesToClose.forEach { it.isClosed = true; it.updatedAt = now }
        entryRepository.saveAll(entriesToClose)

        val totalNet   = entriesToClose.sumOf { it.netAmountCents }
        val totalGross = entriesToClose.sumOf { it.grossAmountCents }
        val totalVat   = totalGross - totalNet

        var financeEntryCreated = false
        if (command.addToFinances && entriesToClose.isNotEmpty()) {
            val df = DateTimeFormatter.ofPattern("MM.yyyy")
            val period = command.from?.format(df) ?: LocalDate.now().format(df)
            createFinancialDocumentHandler.handle(
                CreateFinancialDocumentCommand(
                    studioId          = command.studioId,
                    userId            = command.userId,
                    userDisplayName   = command.userDisplayName,
                    source            = DocumentSource.MANUAL,
                    visitId           = null,
                    documentType      = DocumentType.OTHER,
                    direction         = DocumentDirection.INCOME,
                    paymentMethod     = PaymentMethod.TRANSFER,
                    totalNet          = totalNet,
                    totalVat          = totalVat,
                    totalGross        = totalGross,
                    issueDate         = LocalDate.now(),
                    dueDate           = LocalDate.now().plusDays(14),
                    description       = "Zlecenie zbiorcze – ${contractor.name} – $period",
                    counterpartyName  = contractor.name,
                    counterpartyNip   = contractor.taxId
                )
            )
            financeEntryCreated = true
        }

        var emailSent = false
        val targetEmail = command.emailOverride?.takeIf { it.isNotBlank() } ?: contractor.email
        if (command.sendEmail && !targetEmail.isNullOrBlank()) {
            if (contractor.email.isNullOrBlank() && !command.emailOverride.isNullOrBlank()) {
                contractor.email = command.emailOverride
                contractorRepository.save(contractor)
            }
            val df = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val periodText = when {
                command.from != null && command.to != null ->
                    "${command.from.format(df)} – ${command.to.format(df)}"
                command.from != null -> "od ${command.from.format(df)}"
                else                 -> "bieżący miesiąc"
            }
            val totalFormatted = "%.2f zł".format(totalGross / 100.0)
            val subject = "Zestawienie zbiorcze – ${contractor.name} – $periodText"
            val body = """
Szanowni Państwo,

Przesyłamy miesięczne zestawienie zbiorcze za okres: $periodText.

Kontrahent: ${contractor.name}
Liczba wpisów: ${entriesToClose.size}
Łączna kwota brutto: $totalFormatted

Dziękujemy za współpracę.
            """.trimIndent()
            runCatching {
                emailProvider.send(to = targetEmail, subject = subject, bodyText = body)
                emailSent = true
            }.onFailure { ex ->
                log.warn("Failed to send month-close email to {}: {}", targetEmail, ex.message)
            }
        }

        val closedEntryIds = "[" + entriesToClose.joinToString(",") { "\"${it.id}\"" } + "]"
        val historyRecord = BatchOrderCloseHistoryEntity(
            studioId            = command.studioId.value,
            contractorId        = command.contractorId.value,
            closedByUserId      = command.userId.value,
            closedByUserName    = command.userDisplayName,
            closedAt            = now,
            periodFrom          = command.from,
            periodTo            = command.to,
            entryCount          = entriesToClose.size,
            totalNetCents       = totalNet,
            totalGrossCents     = totalGross,
            mode                = command.mode,
            financeEntryCreated = financeEntryCreated,
            emailRequested      = command.sendEmail,
            emailSent           = emailSent,
            emailRecipient      = if (command.sendEmail) targetEmail else null,
            closedEntryIds      = closedEntryIds
        )
        closeHistoryRepository.save(historyRecord)

        return CloseMonthResult(
            closedEntryCount    = entriesToClose.size,
            financeEntryCreated = financeEntryCreated,
            emailSent           = emailSent,
            historyId           = historyRecord.id.toString()
        )
    }
}

data class CloseMonthCommand(
    val studioId         : StudioId,
    val contractorId     : BatchContractorId,
    val userId           : UserId,
    val userDisplayName  : String,
    val from             : LocalDate?,
    val to               : LocalDate?,
    val addToFinances    : Boolean,
    val sendEmail        : Boolean,
    val emailOverride    : String?,
    val mode             : String = "ALL"
)

data class CloseMonthResult(
    val closedEntryCount    : Int,
    val financeEntryCreated : Boolean,
    val emailSent           : Boolean,
    val historyId           : String
)
