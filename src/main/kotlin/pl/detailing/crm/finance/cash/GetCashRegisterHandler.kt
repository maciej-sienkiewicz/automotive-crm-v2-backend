package pl.detailing.crm.finance.cash

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.detailing.crm.finance.domain.CashOperation
import pl.detailing.crm.finance.domain.CashRegister
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId

data class GetCashRegisterQuery(val studioId: StudioId)

data class CashHistoryQuery(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 30
)

data class CashHistoryResult(
    val operations: List<CashOperation>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

/**
 * Returns the current cash-register state for a studio.
 *
 * If the studio has never performed a cash operation the register is considered
 * empty ([CashRegister.balance] == 0) and a row is NOT created in the database
 * until the first actual cash movement.
 */
@Service
class GetCashRegisterHandler(
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository
) {
    fun getCashRegister(query: GetCashRegisterQuery): CashRegister {
        return cashRegisterRepository.findByStudioId(query.studioId.value)
            ?.toDomain()
            ?: emptyCashRegister(query.studioId)
    }

    fun getCashHistory(query: CashHistoryQuery): CashHistoryResult {
        val pageable = PageRequest.of(
            maxOf(0, query.page - 1),
            query.pageSize.coerceIn(1, 100),
            Sort.by(Sort.Direction.DESC, "createdAt")
        )

        val page = cashOperationRepository.findByStudioId(query.studioId.value, pageable)

        return CashHistoryResult(
            operations = page.content.map { it.toDomain() },
            total      = page.totalElements,
            page       = query.page,
            pageSize   = query.pageSize
        )
    }

    private fun emptyCashRegister(studioId: StudioId): CashRegister =
        CashRegisterEntity(studioId = studioId.value, balance = 0L).toDomain()
}
