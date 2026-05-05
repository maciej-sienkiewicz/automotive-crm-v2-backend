package pl.detailing.crm.smscredits.domain

import pl.detailing.crm.shared.StudioId

interface SmsCreditRepository {
    fun findBalanceByStudioId(studioId: StudioId): SmsCreditBalance?
    fun findBalanceByStudioIdForUpdate(studioId: StudioId): SmsCreditBalance?
    fun saveBalance(balance: SmsCreditBalance): SmsCreditBalance
    fun saveTransaction(transaction: SmsCreditTransaction): SmsCreditTransaction
    fun findTransactionsByStudioId(studioId: StudioId, page: Int, size: Int): List<SmsCreditTransaction>
    fun countTransactionsByStudioId(studioId: StudioId): Long
}
