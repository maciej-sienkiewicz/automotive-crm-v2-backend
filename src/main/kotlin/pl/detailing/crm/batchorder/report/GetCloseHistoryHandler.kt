package pl.detailing.crm.batchorder.report

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate

@Service
class GetCloseHistoryHandler(
    private val historyRepository: BatchOrderCloseHistoryRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(contractorId: BatchContractorId, studioId: StudioId): List<CloseHistoryItem> =
        historyRepository
            .findByContractorIdAndStudioIdOrderByClosedAtDesc(contractorId.value, studioId.value)
            .map { e ->
                CloseHistoryItem(
                    id                  = e.id.toString(),
                    closedAt            = e.closedAt,
                    periodFrom          = e.periodFrom,
                    periodTo            = e.periodTo,
                    entryCount          = e.entryCount,
                    totalNetCents       = e.totalNetCents,
                    totalGrossCents     = e.totalGrossCents,
                    mode                = e.mode,
                    financeEntryCreated = e.financeEntryCreated,
                    emailRequested      = e.emailRequested,
                    emailSent           = e.emailSent,
                    emailRecipient      = e.emailRecipient,
                    closedByUserName    = e.closedByUserName
                )
            }
}

data class CloseHistoryItem(
    val id                  : String,
    val closedAt            : Instant,
    val periodFrom          : LocalDate?,
    val periodTo            : LocalDate?,
    val entryCount          : Int,
    val totalNetCents       : Long,
    val totalGrossCents     : Long,
    val mode                : String,
    val financeEntryCreated : Boolean,
    val emailRequested      : Boolean,
    val emailSent           : Boolean,
    val emailRecipient      : String?,
    val closedByUserName    : String?
)
