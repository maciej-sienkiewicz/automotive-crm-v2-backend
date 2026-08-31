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
import java.time.Instant

data class GetCashRegisterQuery(val studioId: StudioId)

/** Która strona kasy interesuje pytającego. */
enum class CashDirection { IN, OUT }

data class CashHistoryQuery(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 30,

    /** Włączające. */
    val from: Instant? = null,

    /**
     * **Wyłączające** — start doby po ostatnim dniu okresu. Kontroler wylicza je
     * przez [pl.detailing.crm.shared.DateRangeFilter], żeby „do 31 sierpnia"
     * obejmowało cały 31 sierpnia czasu polskiego, a nie do 02:00 tego dnia.
     */
    val to: Instant? = null,

    /** `null` = obie strony. */
    val direction: CashDirection? = null
)

data class CashHistoryResult(
    val operations: List<CashOperation>,
    val total: Long,
    val page: Int,
    val pageSize: Int,

    /**
     * Sumy za CAŁY okres, w groszach i zawsze dodatnie — nie za bieżącą stronę
     * i nie za wybrany kierunek. Filtr kierunku zawęża listę operacji; wiersz
     * podsumowania ma dalej pokazywać obie strony, bo po to się go czyta.
     */
    val totalIn: Long,
    val totalOut: Long
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

        val page = cashOperationRepository.findFiltered(
            studioId = query.studioId.value,
            from     = query.from,
            to       = query.to,
            onlyIn   = query.direction == CashDirection.IN,
            onlyOut  = query.direction == CashDirection.OUT,
            pageable = pageable
        )

        // SUM po pustym zbiorze to null, nie zero — okres bez wpłat jest normalny.
        val inflow  = cashOperationRepository.sumInflow(query.studioId.value, query.from, query.to) ?: 0L
        // Wypłaty siedzą w bazie ze znakiem minus; na zewnątrz idzie moduł, bo pole
        // nazywa się już „totalOut" i podwójne zaprzeczenie w UI niczego nie wnosi.
        val outflow = cashOperationRepository.sumOutflow(query.studioId.value, query.from, query.to) ?: 0L

        return CashHistoryResult(
            operations = page.content.map { it.toDomain() },
            total      = page.totalElements,
            page       = query.page,
            pageSize   = query.pageSize,
            totalIn    = inflow,
            totalOut   = -outflow
        )
    }

    private fun emptyCashRegister(studioId: StudioId): CashRegister =
        CashRegisterEntity(studioId = studioId.value, balance = 0L).toDomain()
}
