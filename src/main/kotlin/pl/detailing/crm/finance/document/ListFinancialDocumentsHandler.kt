package pl.detailing.crm.finance.document

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import java.time.LocalDate

data class ListFinancialDocumentsCommand(
    val studioId: StudioId,

    // Optional filters
    val documentType: DocumentType? = null,
    val direction: DocumentDirection? = null,
    val status: DocumentStatus? = null,
    val visitId: VisitId? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,

    // Pagination – 1-based page index
    val page: Int = 1,
    val pageSize: Int = 20
)

data class ListFinancialDocumentsResult(
    val documents: List<FinancialDocument>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

@Service
class ListFinancialDocumentsHandler(
    private val documentRepository: FinancialDocumentRepository
) {
    fun handle(command: ListFinancialDocumentsCommand): ListFinancialDocumentsResult {
        val pageable = PageRequest.of(
            maxOf(0, command.page - 1),
            command.pageSize.coerceIn(1, 100),
            Sort.by(Sort.Direction.DESC, "issueDate", "createdAt")
        )

        val page = documentRepository.findWithFilters(
            studioId     = command.studioId.value,
            documentType = command.documentType,
            direction    = command.direction,
            status       = command.status,
            visitId      = command.visitId?.value,
            dateFrom     = command.dateFrom,
            dateTo       = command.dateTo,
            pageable     = pageable
        )

        return ListFinancialDocumentsResult(
            documents = page.content.map { it.toDomain() },
            total     = page.totalElements,
            page      = command.page,
            pageSize  = command.pageSize
        )
    }
}
