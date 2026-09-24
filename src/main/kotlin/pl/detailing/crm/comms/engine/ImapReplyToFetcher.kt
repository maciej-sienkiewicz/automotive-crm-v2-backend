package pl.detailing.crm.comms.engine

import jakarta.mail.FetchProfile
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import java.util.UUID

/**
 * Dociąga z serwera IMAP nagłówek `Reply-To` wiadomości zaimportowanych, zanim go
 * zapisywaliśmy (przed V157).
 *
 * Potrzebne raz — do rozplątania starych wątków formularzy: klient zgłoszenia stoi
 * właśnie w `Reply-To`, a baza tego nagłówka dla starej poczty nie zna. Serwer jest
 * najpewniejszym źródłem (ten sam nagłówek, który przeczytałby program pocztowy),
 * ale nie jedynym — wołający ma zapas w treści maila.
 *
 * Nigdy nie rzuca: brak połączenia, zmienione UIDVALIDITY czy wiadomość skasowana
 * na serwerze kończą się pustym wynikiem dla tej wiadomości, a nie błędem całej operacji.
 */
@Service
class ImapReplyToFetcher(private val imapSessions: ImapSessions) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class ReplyTo(val email: String, val name: String?)

    fun fetch(account: MailAccountEntity, messages: Collection<CommMessageEntity>): Map<UUID, ReplyTo> {
        val candidates = messages.filter {
            it.folderKind == CommFolderKind.INBOX && it.imapUid != null && it.imapUidValidity != null
        }
        if (candidates.isEmpty()) return emptyMap()

        var store: Store? = null
        return try {
            store = imapSessions.openStore(account)
            val inbox = imapSessions.openInbox(store)
            try {
                // UID znaczy coś tylko w obrębie jednej wartości UIDVALIDITY — po jej zmianie
                // ten sam numer wskazuje inną wiadomość i dociągnęlibyśmy cudzy nagłówek.
                val byUid = candidates
                    .filter { it.imapUidValidity == inbox.uidValidity }
                    .associateBy { it.imapUid!! }
                if (byUid.isEmpty()) return emptyMap()

                val fetched = inbox.getMessagesByUID(byUid.keys.toLongArray()).filterNotNull().toTypedArray()
                inbox.fetch(fetched, FetchProfile().apply { add("Reply-To") })

                fetched.mapNotNull { message ->
                    val entity = byUid[inbox.getUID(message)] ?: return@mapNotNull null
                    val raw = runCatching { message.getHeader("Reply-To")?.firstOrNull() }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val address = runCatching { InternetAddress.parseHeader(raw, false).firstOrNull() }.getOrNull()
                        ?.takeIf { !it.address.isNullOrBlank() && it.address.contains('@') }
                        ?: return@mapNotNull null
                    entity.id to ReplyTo(
                        email = address.address.trim().lowercase(),
                        name = address.personal?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }.toMap()
            } finally {
                runCatching { inbox.close(false) }
            }
        } catch (e: Exception) {
            log.warn("[COMMS] Nie udało się dociągnąć Reply-To z serwera dla {}: {}", account.emailAddress, e.message)
            emptyMap()
        } finally {
            runCatching { store?.close() }
        }
    }
}
