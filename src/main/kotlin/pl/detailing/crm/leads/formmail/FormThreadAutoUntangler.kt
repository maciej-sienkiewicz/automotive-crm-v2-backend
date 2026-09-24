package pl.detailing.crm.leads.formmail

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.comms.engine.AccountSyncFollowUp
import pl.detailing.crm.comms.engine.ImapSyncEngine
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/** Studio oznaczyło (albo włączyło ponownie) robota formularza. */
data class FormMailSourceRegisteredEvent(val studioId: UUID)

/**
 * Wielkie wątki formularzy sprzed V157 rozplatają się same — bez banera i bez klikania.
 *
 * Działa po każdej synchronizacji skrzynki, pod tą samą blokadą konta co import
 * ([AccountSyncFollowUp]), więc w trakcie przepinania nic nie dopisuje się do tych
 * wątków. Pierwsza synchronizacja po wdrożeniu (pół minuty po starcie) rozplata
 * wszystko, co zdążyło się skleić; kolejne mają już tylko jedno tanie zapytanie —
 * przejrzane wątki niosą znacznik `untangledAt` i do kolejki nie wracają.
 *
 * Kandydatem jest zwykły wątek, którego drugą stroną jest adres po naszej stronie:
 * skrzynka studia (WP Mail SMTP wysyła „od studia do studia") albo oznaczony robot
 * formularza. Oznaczenie nowego robota uruchamia synchronizację od razu — jego stary
 * wątek ma się rozplątać teraz, a nie przy następnym przebiegu.
 */
@Component
class FormThreadAutoUntangler(
    private val threadRepository: CommThreadRepository,
    private val accountRepository: MailAccountRepository,
    private val addressDirectory: MailAddressDirectory,
    private val untangler: FormThreadUntangler,
    private val syncEngine: ImapSyncEngine
) : AccountSyncFollowUp {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterSync(account: MailAccountEntity) {
        val book = addressDirectory.addressBook(account.studioId)
        val addresses = book.ownAddresses + book.formSenders
        if (addresses.isEmpty()) return

        threadRepository.findUntangleCandidates(account.id, addresses).forEach { thread ->
            // Jeden wątek, którego nie dało się rozplątać, nie zatrzymuje pozostałych;
            // bez znacznika wróci przy następnej synchronizacji.
            runCatching { untangler.untangle(StudioId(account.studioId), thread.id) }
                .onSuccess { result ->
                    if (result != null) {
                        log.info(
                            "[FORM_MAIL] {}: wątek {} rozdzielony automatycznie na {} rozmów",
                            account.emailAddress, thread.id, result.conversations
                        )
                    }
                }
                .onFailure { log.warn("[FORM_MAIL] Rozplątanie wątku {} nieudane: {}", thread.id, it.message) }
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onSourceRegistered(event: FormMailSourceRegisteredEvent) {
        accountRepository.findByStudioId(event.studioId)
            .filter { it.status == MailAccountStatus.ACTIVE && it.providerType == MailProviderType.IMAP_SMTP }
            .forEach { account ->
                runCatching { syncEngine.syncAccount(account.id) }
                    .onFailure { log.warn("[FORM_MAIL] Synchronizacja {} po oznaczeniu robota nieudana: {}", account.emailAddress, it.message) }
            }
    }
}
