package pl.detailing.crm.finance.document

import org.springframework.stereotype.Component
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import java.time.Instant
import java.util.UUID

/**
 * Wpis [CashOperationType.DOCUMENT_CORRECTION]: zmiana salda kasy wywołana zmianą
 * dokumentu (usunięcie, przywrócenie, edycja kwoty lub formy płatności).
 *
 * Kasa jest dziennikiem dopisywanym — wpłata z dokumentu zostaje, obok staje korekta.
 * Saldo zmienia się pod blokadą wiersza kasy, jak przy każdym innym ruchu.
 *
 * Bez zdarzenia metryk na żywo: liczniki tylko rosną, więc storno policzone jako
 * „wypłata" zawyżyłoby obrót kasy zamiast go zmniejszyć.
 */
@Component
class DocumentCashCorrections(
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository
) {
    /** Wymaga aktywnej transakcji wołającego. [amount] ze znakiem: + do kasy, − z kasy. */
    fun record(studioId: UUID, userId: UUID, documentId: UUID, amount: Long, comment: String) {
        if (amount == 0L) return
        val register = cashRegisterRepository.findByStudioIdForUpdate(studioId)
            ?: cashRegisterRepository.save(CashRegisterEntity(studioId = studioId, balance = 0L))

        val balanceBefore = register.balance
        register.balance = balanceBefore + amount
        register.updatedAt = Instant.now()
        cashRegisterRepository.save(register)

        cashOperationRepository.save(
            CashOperationEntity(
                id                  = UUID.randomUUID(),
                studioId            = studioId,
                cashRegisterId      = register.id,
                amount              = amount,
                balanceBefore       = balanceBefore,
                balanceAfter        = register.balance,
                operationType       = CashOperationType.DOCUMENT_CORRECTION,
                comment             = comment,
                financialDocumentId = documentId,
                createdBy           = userId
            )
        )
    }
}
