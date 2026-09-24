package pl.detailing.crm.comms.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Jedna wiadomość wielkiego wątku — tyle, ile trzeba, żeby ją przypisać do rozmowy. */
data class UntangleMessage(
    val id: UUID,
    val messageIdHdr: String,
    val direction: CommDirection,
    val fromEmail: String,
    val fromName: String?,
    val replyToEmail: String?,
    val replyToName: String?,
    val toEmails: List<String>,
    val inReplyTo: String?,
    val references: List<String>,
    val sentAt: Instant,
    /**
     * Klient ustalony poza nagłówkami zapisanymi w bazie: z serwera IMAP, z leada albo
     * z pola „E-mail:" w treści. Dla wiadomości sprzed V157 `Reply-To` nie ma w bazie.
     */
    val recoveredClientEmail: String? = null
)

/** Jedno zgłoszenie z formularza i cała korespondencja, która z niego wyrosła. */
data class SubmissionGroup(
    val clientEmail: String,
    val clientName: String?,
    val relayEmail: String,
    /** Wiadomości-zgłoszenia (zwykle jedna; więcej, gdy klient wysłał formularz ponownie). */
    val submissionIds: List<UUID>,
    /** Wszystkie wiadomości rozmowy, zgłoszenia włącznie, chronologicznie. */
    val messageIds: List<UUID>
)

data class UntanglePlan(
    val groups: List<SubmissionGroup>,
    /** Zwroty serwera pocztowego — do wątku systemowego skrzynki. */
    val systemMessageIds: List<UUID>,
    /** Wiadomości, których nie da się przypisać do żadnego zgłoszenia — zostają w starym wątku. */
    val leftoverIds: List<UUID>
) {
    val changesAnything: Boolean get() = groups.isNotEmpty() || systemMessageIds.isNotEmpty()
}

/**
 * Rozplata wielki wątek formularza na rozmowy — po jednej na zgłoszenie.
 *
 * Wątki sprzed V157 sklejały wszystkie zgłoszenia z formularza (wspólny temat, wspólny
 * nadawca-robot), a do nich nasze odpowiedzi, odpisy klientów i zwroty serwera. Import
 * robi to dziś dobrze od pierwszej wiadomości; ten planer odtwarza ten sam podział dla
 * tego, co już leży w bazie. Jest czystą funkcją: nic nie zapisuje i nie zgaduje.
 *
 * Idziemy chronologicznie, a każda wiadomość trafia tam, gdzie wskazuje najmocniejszy
 * dostępny dowód:
 *  1. zwrot serwera pocztowego → wątek systemowy;
 *  2. zgłoszenie (nadawca po naszej stronie + klient w Reply-To albo odzyskany) → nowa
 *     rozmowa, chyba że ta sama osoba wysłała formularz chwilę wcześniej i nikt jeszcze
 *     nie odpisał (duplikat — ta sama reguła co przy imporcie);
 *  3. przodek z `In-Reply-To` / `References` → rozmowa przodka (tak wraca nasza
 *     odpowiedź i odpis klienta — to jest dowód, nie heurystyka);
 *  4. bez przodka: adres klienta — nasza wiadomość do niego albo jego wiadomość do nas
 *     trafia do jego najnowszego zgłoszenia sprzed tej wiadomości;
 *  5. reszta zostaje w starym wątku. Lepiej zostawić wiadomość tam, gdzie była, niż
 *     wpiąć ją w cudzą rozmowę.
 */
object FormThreadUntanglePlanner {

    private val DUPLICATE_WINDOW: Duration = Duration.ofHours(48)

    private sealed interface Target {
        data class Group(val index: Int) : Target
        data object System : Target
        data object Leftover : Target
    }

    private class MutableGroup(
        val clientEmail: String,
        var clientName: String?,
        val relayEmail: String,
        var lastSubmissionAt: Instant,
        val firstSubmissionAt: Instant
    ) {
        val submissionIds = mutableListOf<UUID>()
        val messageIds = mutableListOf<UUID>()
        var hasOutbound = false
    }

    /**
     * Przychodzący mail od nadawcy po naszej stronie — skrzynki studia albo robota
     * formularza. Tylko taki może być zgłoszeniem; czy nim jest, rozstrzyga dopiero klient.
     */
    fun fromOurSide(direction: CommDirection, fromEmail: String, book: MailAddressBook): Boolean {
        if (direction != CommDirection.INBOUND) return false
        val from = MailAddressBook.normalize(fromEmail)
        return book.isOwn(from) || book.isFormSender(from) || FormMailerSignature.matches(from, emptyMap())
    }

    /** Czy wiadomość jest zgłoszeniem z formularza; zwraca adres klienta albo null. */
    fun submissionClient(message: UntangleMessage, book: MailAddressBook): String? {
        if (!fromOurSide(message.direction, message.fromEmail, book)) return null
        val from = MailAddressBook.normalize(message.fromEmail)
        return listOfNotNull(message.replyToEmail, message.recoveredClientEmail)
            .map(MailAddressBook::normalize)
            .firstOrNull { it.contains('@') && it != from && !book.isNotAClient(it) }
    }

    fun plan(messages: List<UntangleMessage>, book: MailAddressBook): UntanglePlan {
        val groups = mutableListOf<MutableGroup>()
        val system = mutableListOf<UUID>()
        val leftover = mutableListOf<UUID>()
        val targetByHeader = HashMap<String, Target>()

        for (message in messages.sortedBy { it.sentAt }) {
            val target = assign(message, book, groups, targetByHeader)
            targetByHeader[message.messageIdHdr] = target
            when (target) {
                is Target.Group -> {
                    val group = groups[target.index]
                    group.messageIds += message.id
                    if (message.direction == CommDirection.OUTBOUND) group.hasOutbound = true
                    if (group.clientName == null && message.direction == CommDirection.INBOUND &&
                        MailAddressBook.normalize(message.fromEmail) == group.clientEmail
                    ) {
                        group.clientName = message.fromName
                    }
                }
                Target.System -> system += message.id
                Target.Leftover -> leftover += message.id
            }
        }

        return UntanglePlan(
            groups = groups.map {
                SubmissionGroup(
                    clientEmail = it.clientEmail,
                    clientName = it.clientName,
                    relayEmail = it.relayEmail,
                    submissionIds = it.submissionIds.toList(),
                    messageIds = it.messageIds.toList()
                )
            },
            systemMessageIds = system,
            leftoverIds = leftover
        )
    }

    private fun assign(
        message: UntangleMessage,
        book: MailAddressBook,
        groups: MutableList<MutableGroup>,
        targetByHeader: Map<String, Target>
    ): Target {
        if (message.direction == CommDirection.INBOUND && DeliveryReportDetector.isSystemSender(message.fromEmail)) {
            return Target.System
        }

        submissionClient(message, book)?.let { client ->
            val relay = MailAddressBook.normalize(message.fromEmail)
            val duplicateOf = groups.indexOfLast {
                it.clientEmail == client && it.relayEmail == relay && !it.hasOutbound &&
                    Duration.between(it.lastSubmissionAt, message.sentAt) <= DUPLICATE_WINDOW
            }
            if (duplicateOf >= 0) {
                groups[duplicateOf].submissionIds += message.id
                groups[duplicateOf].lastSubmissionAt = message.sentAt
                return Target.Group(duplicateOf)
            }
            groups += MutableGroup(
                clientEmail = client,
                clientName = message.replyToName?.trim()?.takeIf { it.isNotEmpty() },
                relayEmail = relay,
                lastSubmissionAt = message.sentAt,
                firstSubmissionAt = message.sentAt
            ).also { it.submissionIds += message.id }
            return Target.Group(groups.lastIndex)
        }

        // Najbliższy przodek pierwszy: In-Reply-To, potem References od końca.
        val ancestry = listOfNotNull(message.inReplyTo) + message.references.asReversed()
        ancestry.firstNotNullOfOrNull { targetByHeader[it] }?.let { ancestor ->
            // Przekazanie zwrotu dalej nie jest ani zwrotem, ani rozmową z klientem.
            return if (ancestor == Target.System) Target.Leftover else ancestor
        }

        val counterparts = when (message.direction) {
            CommDirection.OUTBOUND -> message.toEmails
            CommDirection.INBOUND -> listOf(message.fromEmail)
        }.map(MailAddressBook::normalize).filterNot { book.isNotAClient(it) }.toSet()
        if (counterparts.isNotEmpty()) {
            val latest = groups.indexOfLast { it.clientEmail in counterparts && !it.firstSubmissionAt.isAfter(message.sentAt) }
            if (latest >= 0) return Target.Group(latest)
        }
        return Target.Leftover
    }
}
