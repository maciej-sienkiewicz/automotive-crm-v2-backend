package pl.detailing.crm.ksef.list

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.domain.KsefInvoice
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId

data class ListKsefInvoicesCommand(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20
)

data class ListKsefInvoicesResult(
    val invoices: List<KsefInvoice>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

@Service
class ListKsefInvoicesHandler(private val invoiceRepository: KsefInvoiceRepository) {

    fun handle(command: ListKsefInvoicesCommand): ListKsefInvoicesResult {
        val pageable = PageRequest.of(
            maxOf(0, command.page - 1),
            command.pageSize.coerceIn(1, 100),
            Sort.by(Sort.Direction.DESC, "invoicingDate")
        )

        val page = invoiceRepository.findAllByStudioId(command.studioId.value, pageable)

        return ListKsefInvoicesResult(
            invoices = page.content.map { it.toDomain() },
            total = page.totalElements,
            page = command.page,
            pageSize = command.pageSize
        )
    }
}
