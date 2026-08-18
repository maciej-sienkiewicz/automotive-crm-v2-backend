package pl.detailing.crm.comms.engine

import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import pl.detailing.crm.mailbox.account.MailAccountConnectedEvent

/**
 * A freshly connected mailbox should show mail within a minute or two, not at the next
 * scheduler tick — the user is watching the screen right after typing their password.
 */
@Component
class MailAccountConnectedListener(
    private val syncEngine: ImapSyncEngine,
    private val idleWatcher: ImapIdleWatcher
) {

    @Async
    @EventListener
    fun onAccountConnected(event: MailAccountConnectedEvent) {
        runCatching { syncEngine.syncAccount(event.accountId) }
        idleWatcher.reconcileWatchers()
    }
}
