package pl.detailing.crm.comms.engine

import jakarta.annotation.PreDestroy
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Store
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageChangedEvent
import jakarta.mail.event.MessageChangedListener
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The "real-time" half of the sync architecture: one dedicated IMAP connection per
 * active account, parked in IDLE on INBOX. IDLE events carry no data — they are only a
 * nudge to run the regular delta sync, which is the single code path for persistence.
 *
 * IDLE is re-armed every ~10 minutes because NATs and firewalls silently kill quiet
 * connections without a FIN; a watcher that dies for any reason backs off and
 * reconnects, and the scheduled sync remains the correctness safety net throughout.
 */
@Service
class ImapIdleWatcher(
    private val accountRepository: MailAccountRepository,
    private val imapSessions: ImapSessions,
    private val syncEngine: ImapSyncEngine,
    @Value("\${comms.idle.enabled:true}") private val idleEnabled: Boolean,
    @Value("\${comms.idle.max-watchers:100}") private val maxWatchers: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "imap-idle-${THREAD_SEQ.incrementAndGet()}").apply { isDaemon = true }
    }
    private val watchers = ConcurrentHashMap<UUID, Watcher>()

    /** Reconciles the watcher fleet with the current set of active accounts. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    fun reconcileWatchers() {
        if (!idleEnabled) return
        val active = accountRepository
            .findByStatusAndProviderType(MailAccountStatus.ACTIVE, MailProviderType.IMAP_SMTP)
            .associateBy { it.id }

        // Stop watchers for accounts that were disabled or broke.
        watchers.keys.filterNot { it in active }.forEach { stopWatcher(it) }

        // Start missing watchers, up to the per-instance budget.
        active.values
            .filterNot { watchers.containsKey(it.id) }
            .take((maxWatchers - watchers.size).coerceAtLeast(0))
            .forEach { account ->
                val watcher = Watcher(account.id, account.emailAddress)
                if (watchers.putIfAbsent(account.id, watcher) == null) {
                    executor.execute(watcher)
                }
            }
    }

    fun stopWatcher(accountId: UUID) {
        watchers.remove(accountId)?.stop()
    }

    @PreDestroy
    fun shutdown() {
        watchers.values.forEach { it.stop() }
        watchers.clear()
        executor.shutdownNow()
    }

    private inner class Watcher(
        private val accountId: UUID,
        private val email: String
    ) : Runnable {

        @Volatile
        private var running = true

        @Volatile
        private var store: Store? = null

        fun stop() {
            running = false
            // Closing the store from another thread aborts a blocked idle() immediately.
            runCatching { store?.close() }
        }

        override fun run() {
            var backoffMs = INITIAL_BACKOFF_MS
            while (running && watchers[accountId] === this) {
                try {
                    watch()
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (ex: AuthenticationFailedException) {
                    // Sync engine owns the status flip; the watcher just leaves quietly.
                    log.warn("[IDLE] Uwierzytelnienie odrzucone dla {} — kończę nasłuch", email)
                    break
                } catch (ex: Exception) {
                    if (!running) break
                    log.debug("[IDLE] Połączenie {} przerwane ({}) — ponawiam za {} ms",
                        email, ex.message, backoffMs)
                    sleepQuietly(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            watchers.remove(accountId, this)
        }

        private fun watch() {
            val account = accountRepository.findById(accountId).orElse(null) ?: run {
                running = false
                return
            }
            if (account.status != MailAccountStatus.ACTIVE) {
                running = false
                return
            }

            val openedStore = imapSessions.openStore(account, forIdle = true)
            store = openedStore
            try {
                val inbox = imapSessions.openInbox(openedStore)

                val nudge = {
                    // The event itself carries nothing we trust; delta sync is the one code path.
                    runCatching { syncEngine.syncAccount(accountId) }
                        .onFailure { log.debug("[IDLE] Sync po zdarzeniu nieudany dla {}: {}", email, it.message) }
                    Unit
                }
                inbox.addMessageCountListener(object : MessageCountAdapter() {
                    override fun messagesAdded(event: MessageCountEvent) = nudge()
                    override fun messagesRemoved(event: MessageCountEvent) = Unit
                })
                // Flag updates (someone read the mail on their phone) also arrive here.
                inbox.addMessageChangedListener(MessageChangedListener { _: MessageChangedEvent -> nudge() })

                log.info("[IDLE] Nasłuch INBOX aktywny dla {}", email)
                while (running && watchers[accountId] === this) {
                    // idle() returns on every server event; re-arming in a loop keeps the
                    // session alive and doubles as the keep-alive against silent NAT kills.
                    inbox.idle(true)
                }
            } finally {
                store = null
                runCatching { openedStore.close() }
            }
        }

        private fun sleepQuietly(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                running = false
            }
        }
    }

    companion object {
        private val THREAD_SEQ = AtomicInteger()
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
    }
}
