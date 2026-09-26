package pl.detailing.crm.shared

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/**
 * Menedżer transakcji do testów jednostkowych: wykonuje ciało od razu i zapisuje,
 * czy transakcja skończyła się commitem, czy wycofaniem. Pozwala sprawdzić
 * atomowość bez bazy - „błąd w połowie → rollback, nie commit".
 */
class RecordingTransactionManager : PlatformTransactionManager {
    var begun = 0
        private set
    var commits = 0
        private set
    var rollbacks = 0
        private set

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        begun++
        return SimpleTransactionStatus()
    }

    override fun commit(status: TransactionStatus) {
        commits++
    }

    override fun rollback(status: TransactionStatus) {
        rollbacks++
    }

    fun template() = TransactionTemplate(this)
}
