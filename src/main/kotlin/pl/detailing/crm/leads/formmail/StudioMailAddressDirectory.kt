package pl.detailing.crm.leads.formmail

import org.springframework.stereotype.Component
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Książka adresowa studia: skrzynki podłączone do CRM-a i roboty formularzy.
 *
 * Czyta ją import przy KAŻDEJ wiadomości — pierwszy import skrzynki to setki maili
 * w minutę — więc wynik żyje chwilę w pamięci. Obie listy zmieniają się rzadko
 * (podłączenie skrzynki, oznaczenie robota), a oznaczenie robota czyści wpis od razu
 * ([invalidate]); podłączenie skrzynki dociera najpóźniej po [TTL].
 */
@Component
class StudioMailAddressDirectory(
    private val accountRepository: MailAccountRepository,
    private val sourceRepository: FormMailSourceRepository
) : MailAddressDirectory {

    private data class Cached(val book: MailAddressBook, val loadedAt: Instant)

    private val cache = ConcurrentHashMap<UUID, Cached>()

    override fun addressBook(studioId: UUID): MailAddressBook {
        val now = Instant.now()
        cache[studioId]?.takeIf { Duration.between(it.loadedAt, now) < TTL }?.let { return it.book }

        val book = MailAddressBook(
            ownAddresses = accountRepository.findByStudioId(studioId)
                .map { MailAddressBook.normalize(it.emailAddress) }
                .toSet(),
            formSenders = sourceRepository.findByStudioIdOrderByCreatedAtDesc(studioId)
                .filter { it.active }
                .map { MailAddressBook.normalize(it.senderEmail) }
                .toSet()
        )
        cache[studioId] = Cached(book, now)
        return book
    }

    override fun invalidate(studioId: UUID) {
        cache.remove(studioId)
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(30)
    }
}
