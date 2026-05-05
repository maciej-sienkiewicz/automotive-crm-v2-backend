package pl.detailing.crm.smscredits.infrastructure

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscredits.domain.SmsCreditBalance
import pl.detailing.crm.smscredits.domain.SmsCreditRepository
import pl.detailing.crm.smscredits.domain.SmsCreditTransaction

@Component
class SmsCreditRepositoryAdapter(
    private val balanceJpa: SmsCreditBalanceJpaRepository,
    private val transactionJpa: SmsCreditTransactionJpaRepository
) : SmsCreditRepository {

    override fun findBalanceByStudioId(studioId: StudioId): SmsCreditBalance? =
        balanceJpa.findByStudioId(studioId.value)?.toDomain()

    override fun findBalanceByStudioIdForUpdate(studioId: StudioId): SmsCreditBalance? =
        balanceJpa.findByStudioIdForUpdate(studioId.value)?.toDomain()

    override fun saveBalance(balance: SmsCreditBalance): SmsCreditBalance {
        val existing = balanceJpa.findByStudioId(balance.studioId.value)
        return if (existing != null) {
            existing.applyDomain(balance)
            balanceJpa.save(existing).toDomain()
        } else {
            balanceJpa.save(SmsCreditBalanceEntity.fromDomain(balance)).toDomain()
        }
    }

    override fun saveTransaction(transaction: SmsCreditTransaction): SmsCreditTransaction =
        transactionJpa.save(SmsCreditTransactionEntity.fromDomain(transaction)).toDomain()

    override fun findTransactionsByStudioId(studioId: StudioId, page: Int, size: Int): List<SmsCreditTransaction> =
        transactionJpa.findByStudioIdOrderByCreatedAtDesc(
            studioId.value,
            PageRequest.of(page, size)
        ).map { it.toDomain() }

    override fun countTransactionsByStudioId(studioId: StudioId): Long =
        transactionJpa.countByStudioId(studioId.value)
}
