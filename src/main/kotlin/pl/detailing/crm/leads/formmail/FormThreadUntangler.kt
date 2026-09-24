package pl.detailing.crm.leads.formmail

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.comms.domain.CommThreadKind
import pl.detailing.crm.comms.domain.FormThreadUntanglePlanner
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.comms.domain.UntangleMessage
import pl.detailing.crm.comms.domain.UntanglePlan
import pl.detailing.crm.comms.engine.CommsIngestService
import pl.detailing.crm.comms.engine.ImapReplyToFetcher
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class UntangleResult(
    /** Nowe rozmowy — po jednej na zgłoszenie z formularza. */
    val conversations: Int,
    /** Wiadomości przeniesione do nowych rozmów. */
    val movedMessages: Int,
    /** Zwroty serwera poczty przeniesione do archiwum. */
    val systemMessages: Int,
    /** Wiadomości, których nie dało się przypisać — zostały w starym wątku. */
    val leftoverMessages: Int,
    /** Leady z formularza, które dostały swoją rozmowę. */
    val linkedLeads: Int,
    /** Wątek, który został (null, gdy opustoszał i zniknął). */
    val remainingThreadId: UUID?
)

/**
 * Rozplątanie wielkiego wątku formularza sprzed V157 na rozmowy — po jednej na
 * zgłoszenie.
 *
 * Od V157 import sam daje każdemu zgłoszeniu osobny wątek. Ale to, co już leży w bazie,
 * zostało sklejone po staremu: u jednego studia 112 wiadomości — 38 zgłoszeń, 45 zwrotów
 * serwera, odpowiedzi i odpisy kilkudziesięciu klientów. Tego nie naprawi żaden nowy
 * mail; trzeba przepiąć istniejące.
 *
 * Uruchamia to automat po synchronizacji skrzynki ([FormThreadAutoUntangler]) — bez
 * udziału użytkownika: wielki wątek formularza nie jest stanem, który ktokolwiek
 * chciałby zachować. Każdy przejrzany wątek dostaje znacznik `untangledAt`, więc
 * nie wraca do kolejki przy kolejnym przebiegu.
 *
 * Trzy fazy, bo środkowa idzie przez sieć:
 *  1. odczyt wątku (krótka transakcja),
 *  2. odzyskanie klienta każdego zgłoszenia — `Reply-To` z serwera IMAP (starej poczty
 *     baza nie zna), a w zapasie lead z dziennika formularzy i pole „E-mail:" w treści;
 *     bez transakcji, bo rozmowa z serwerem trwa,
 *  3. przepięcie w jednej transakcji — albo wszystko, albo nic.
 *
 * Samo przypisanie wiadomości do rozmów robi [FormThreadUntanglePlanner] (czysta funkcja).
 */
@Service
class FormThreadUntangler(
    private val threadRepository: CommThreadRepository,
    private val messageRepository: CommMessageRepository,
    private val accountRepository: MailAccountRepository,
    private val extractionRepository: FormMailExtractionRepository,
    private val leadRepository: LeadRepository,
    private val addressDirectory: MailAddressDirectory,
    private val replyToFetcher: ImapReplyToFetcher,
    private val transactionTemplate: TransactionTemplate,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Rozplata wątek, o ile jest czego — null, gdy nie było (wątek i tak zostaje
     * oznaczony jako przejrzany). Rzuca tylko przy awarii zapisu: wtedy znacznika nie
     * ma i następny przebieg spróbuje ponownie.
     */
    fun untangle(studioId: StudioId, threadId: UUID): UntangleResult? {
        val (thread, messages) = transactionTemplate.execute {
            val thread = threadRepository.findByIdAndStudioId(threadId, studioId.value)
                ?: throw NotFoundException("Nie znaleziono wątku")
            thread to messageRepository.findByThreadIdOrderBySentAtAsc(thread.id)
        }!!
        if (thread.kind != CommThreadKind.DIRECT) return null
        val book = addressDirectory.addressBook(studioId.value)

        // Zgłoszeniem może być tylko przychodzący mail od nadawcy po naszej stronie
        // (skrzynka studia, robot). Wątek z jednym takim mailem nie ma czego dzielić —
        // i tu kończy się tania ścieżka, bez rozmowy z serwerem IMAP.
        if (messages.count { isFromOurSide(it, book) } < MIN_SUBMISSIONS) {
            markReviewed(thread.id)
            return null
        }
        val account = accountRepository.findByIdAndStudioId(thread.accountId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono skrzynki")

        // Faza 2 — klient każdego zgłoszenia. Nagłówek z serwera wygrywa z treścią.
        val needsRecovery = messages.filter {
            it.direction == CommDirection.INBOUND && it.replyToEmail == null && isFromOurSide(it, book)
        }
        val fromServer = replyToFetcher.fetch(account, needsRecovery)
        val fromLeads = leadContacts(needsRecovery)
        val untangleMessages = messages.map { message ->
            val recovered = fromServer[message.id]?.email
                ?: fromLeads[message.id]
                ?: if (message in needsRecovery) emailFromBody(message, book) else null
            message.toUntangle(recovered)
        }

        val plan = FormThreadUntanglePlanner.plan(untangleMessages, book)
        if (!plan.changesAnything) {
            markReviewed(thread.id)
            return null
        }

        val result = transactionTemplate.execute { apply(thread.id, plan, fromServer, studioId) }!!
        log.info(
            "[FORM_MAIL] Wątek {} rozplątany: {} rozmów, {} przeniesionych, {} zwrotów, {} zostało, {} leadów",
            thread.id, result.conversations, result.movedMessages, result.systemMessages,
            result.leftoverMessages, result.linkedLeads
        )
        return result
    }

    private fun apply(
        threadId: UUID,
        plan: UntanglePlan,
        fromServer: Map<UUID, ImapReplyToFetcher.ReplyTo>,
        studioId: StudioId
    ): UntangleResult {
        // Świeży odczyt w transakcji zapisu — między fazami mógł dojść nowy mail.
        val original = threadRepository.findByIdAndStudioId(threadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku")
        val byId = messageRepository.findByThreadIdOrderBySentAtAsc(original.id).associateBy { it.id }

        // Nagłówek dociągnięty z serwera zostaje w bazie — tak, jakby import znał go od początku.
        fromServer.forEach { (id, replyTo) ->
            byId[id]?.let {
                it.replyToEmail = replyTo.email
                it.replyToName = replyTo.name?.take(255)
            }
        }

        val touched = linkedSetOf(original)
        var moved = 0
        var linkedLeads = 0
        val extractions = extractionRepository.findByMessageIdIn(byId.keys)
            .filter { it.status == FormMailLeadProcessor.STATUS_CREATED && it.leadId != null }
            .associateBy { it.messageId }

        plan.groups.forEach { group ->
            val present = group.messageIds.mapNotNull { byId[it] }
            val firstSubmission = group.submissionIds.firstNotNullOfOrNull { byId[it] } ?: return@forEach
            val conversation = threadRepository.save(
                CommThreadEntity(
                    id = UUID.randomUUID(),
                    studioId = original.studioId,
                    accountId = original.accountId,
                    subjectNorm = CommsIngestService.normalizeSubject(firstSubmission.subject),
                    subject = firstSubmission.subject,
                    participantEmail = group.clientEmail,
                    participantName = group.clientName?.take(255),
                    lastMessageAt = firstSubmission.sentAt,
                    lastDirection = CommDirection.INBOUND,
                    lastSnippet = null,
                    leadId = null,
                    labelId = null,
                    archived = original.archived,
                    kind = CommThreadKind.FORM,
                    relayEmail = group.relayEmail
                )
            )
            present.forEach { it.threadId = conversation.id }
            moved += present.size
            touched += conversation

            // Lead założony kiedyś z tego zgłoszenia nie miał wątku, bo jedyny wątek
            // należał do robota. Teraz ma swój — i „Odpisz klientowi" pisze do klienta.
            group.submissionIds.firstNotNullOfOrNull { extractions[it]?.leadId }
                ?.let(leadRepository::findById)?.orElse(null)
                ?.takeIf { it.threadId == null }
                ?.let { lead ->
                    lead.threadId = conversation.id
                    leadRepository.save(lead)
                    conversation.leadId = lead.id
                    if (conversation.participantName == null) conversation.participantName = lead.customerName?.take(255)
                    linkedLeads++
                }
        }

        val bounces = plan.systemMessageIds.mapNotNull { byId[it] }
        if (bounces.isNotEmpty()) {
            val system = systemThread(original, bounces.first().fromEmail)
            bounces.forEach { it.threadId = system.id }
            touched += system
        }

        messageRepository.saveAll(byId.values)
        touched.forEach { recomputeAggregates(it) }

        val remaining = if (original.messageCount == 0 && original.leadId == null &&
            leadRepository.findByThreadId(original.id) == null
        ) {
            threadRepository.delete(original)
            null
        } else {
            original.untangledAt = Instant.now()
            threadRepository.save(original)
            original.id
        }
        touched.filter { it.id != original.id || remaining != null }.forEach {
            eventPublisher.publishEvent(CommThreadChangedEvent(it.studioId, it.id, newMessage = false))
        }

        return UntangleResult(
            conversations = plan.groups.size,
            movedMessages = moved,
            systemMessages = plan.systemMessageIds.size,
            leftoverMessages = plan.leftoverIds.size,
            linkedLeads = linkedLeads,
            remainingThreadId = remaining
        )
    }

    private fun markReviewed(threadId: UUID) {
        transactionTemplate.execute {
            threadRepository.findById(threadId).ifPresent {
                it.untangledAt = Instant.now()
                threadRepository.save(it)
            }
        }
    }

    /** Ten sam wątek zwrotów, do którego import wkłada dziś nowe zwroty tej skrzynki. */
    private fun systemThread(original: CommThreadEntity, serverAddress: String): CommThreadEntity =
        threadRepository.findFirstByAccountIdAndKindOrderByCreatedAtAsc(original.accountId, CommThreadKind.SYSTEM)
            ?: threadRepository.save(
                CommThreadEntity(
                    id = UUID.randomUUID(),
                    studioId = original.studioId,
                    accountId = original.accountId,
                    subjectNorm = null,
                    subject = CommsIngestService.SYSTEM_THREAD_SUBJECT,
                    participantEmail = MailAddressBook.normalize(serverAddress),
                    participantName = CommsIngestService.SYSTEM_THREAD_PARTICIPANT,
                    lastMessageAt = original.lastMessageAt,
                    lastDirection = CommDirection.INBOUND,
                    lastSnippet = null,
                    leadId = null,
                    labelId = null,
                    archived = true,
                    kind = CommThreadKind.SYSTEM
                )
            )

    /** Liczniki, głowa i skrót wątku policzone od nowa z jego wiadomości. */
    private fun recomputeAggregates(thread: CommThreadEntity) {
        val messages = messageRepository.findByThreadIdOrderBySentAtAsc(thread.id)
        thread.messageCount = messages.size
        thread.inboundCount = messages.count { it.direction == CommDirection.INBOUND }
        thread.outboundCount = messages.count { it.direction == CommDirection.OUTBOUND }
        thread.unreadCount = messages.count { !it.isRead }
        thread.hasAttachments = messages.any { it.hasAttachments }
        messages.lastOrNull()?.let { last ->
            thread.lastMessageAt = last.sentAt
            thread.lastDirection = last.direction
            thread.lastSnippet = last.bodyTextClean?.replace(Regex("\\s+"), " ")?.take(200)
        }
        threadRepository.save(thread)
    }

    /** Kontakt z leada, który kiedyś powstał z tego zgłoszenia — o ile jest adresem e-mail. */
    private fun leadContacts(messages: List<CommMessageEntity>): Map<UUID, String> {
        if (messages.isEmpty()) return emptyMap()
        val leadByMessage = extractionRepository.findByMessageIdIn(messages.map { it.id })
            .mapNotNull { extraction -> extraction.leadId?.let { extraction.messageId to it } }
            .toMap()
        if (leadByMessage.isEmpty()) return emptyMap()
        val contacts = leadRepository.findAllById(leadByMessage.values.distinct())
            .associate { it.id to it.contactIdentifier.trim().lowercase() }
        return leadByMessage.mapNotNull { (messageId, leadId) ->
            contacts[leadId]?.takeIf { it.contains('@') }?.let { messageId to it }
        }.toMap()
    }

    /**
     * Adres klienta z treści powiadomienia: najpierw pole z etykietą („E-mail: …"),
     * potem jedyny adres w treści spoza studia. Dwa różne adresy bez etykiety to
     * zgadywanie — wtedy zgłoszenie zostaje w starym wątku.
     */
    private fun emailFromBody(message: CommMessageEntity, book: MailAddressBook): String? {
        val text = message.bodyText?.takeIf { it.isNotBlank() }
            ?: message.bodyHtmlSafe?.let { Jsoup.parse(it).wholeText() }
            ?: message.bodyTextClean
            ?: return null
        LABELLED_EMAIL.find(text)?.groupValues?.get(1)
            ?.let(MailAddressBook::normalize)
            ?.takeIf { !book.isNotAClient(it) }
            ?.let { return it }
        return ANY_EMAIL.findAll(text)
            .map { MailAddressBook.normalize(it.value) }
            .filterNot { book.isNotAClient(it) }
            .toSet()
            .singleOrNull()
    }

    private fun isFromOurSide(message: CommMessageEntity, book: MailAddressBook): Boolean =
        FormThreadUntanglePlanner.fromOurSide(message.direction, message.fromEmail, book)

    private fun CommMessageEntity.toUntangle(recovered: String?) = UntangleMessage(
        id = id,
        messageIdHdr = messageIdHdr,
        direction = direction,
        fromEmail = fromEmail,
        fromName = fromName,
        replyToEmail = replyToEmail,
        replyToName = replyToName,
        toEmails = toEmails?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        inReplyTo = inReplyTo,
        references = referencesIds?.split(' ')?.filter { it.isNotBlank() } ?: emptyList(),
        sentAt = sentAt,
        recoveredClientEmail = recovered
    )

    private companion object {
        /** Jedno zgłoszenie w wątku nie ma czego dzielić. */
        const val MIN_SUBMISSIONS = 2

        val LABELLED_EMAIL = Regex(
            """(?im)^\s*[>\s]*(?:e-?mail|adres\s+e-?mail|mail|email\s+address)\s*[:\-]\s*<?([A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,})>?"""
        )
        val ANY_EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    }
}
