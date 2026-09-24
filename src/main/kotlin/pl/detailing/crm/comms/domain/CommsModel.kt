package pl.detailing.crm.comms.domain

import java.time.Instant
import java.util.UUID

/**
 * Which mailbox folder a message was ingested from. Only these two are synced —
 * everything else the user organises locally with labels, so a message can never
 * "disappear" because somebody re-arranged folders on the server.
 */
enum class CommFolderKind {
    INBOX,
    SENT
}

enum class CommDirection {
    INBOUND,
    OUTBOUND
}

/**
 * Czym jest rozmowa — rozstrzyga import, bo tylko on widzi nagłówki.
 *
 * Rozróżnienie istnieje przez formularze kontaktowe na stronach studiów. Robot
 * formularza (WordPress, WP Mail SMTP, Elementor…) wysyła każde zgłoszenie z tego
 * samego adresu — często z adresu samego studia — pod tym samym tematem. Wątkowanie
 * „ten sam temat + ten sam nadawca" sklejało więc zgłoszenia kilkudziesięciu różnych
 * ludzi w jedną rozmowę (u jednego studia: 112 wiadomości, 38 klientów), a odpowiedź
 * z CRM-a szła na adres robota, czyli do samego studia, a nie do klienta.
 */
enum class CommThreadKind {
    /** Zwykła korespondencja: drugą stroną jest nadawca albo odbiorca maila. */
    DIRECT,

    /**
     * Jedno zgłoszenie z formularza na stronie. Drugą stroną jest klient z nagłówka
     * `Reply-To` (albo — gdy robot go nie ustawia — z treści, po odczycie), a nie robot.
     * Nasze odpowiedzi i odpisy klienta dołączają tu po `In-Reply-To`.
     */
    FORM,

    /**
     * Zwroty i ostrzeżenia serwera pocztowego (mailer-daemon, postmaster). Jeden wątek
     * na skrzynkę, od razu w archiwum: zwrot niesie w `References` identyfikator maila,
     * którego dotyczy, i bez tej przegródki dopinał się do rozmowy z klientem.
     */
    SYSTEM
}

/**
 * Werdykt automatu, że zgłoszenie z formularza nie jest zapytaniem klienta. Wątek
 * zostaje w skrzynce (w zakładce „Odrzucone"), bo pomyłka automatu ma być odwracalna
 * jednym kliknięciem — a nie zniknąć razem z klientem.
 */
enum class CommThreadScreening {
    /** Spam, oferty pozycjonowania, boty wypełniające formularze. */
    SPAM,

    /** Zgłoszenie wysłane przez kogoś ze studia — test formularza, nie klient. */
    INTERNAL
}

/**
 * Who flipped the message to read first. EXTERNAL means the \Seen flag arrived from
 * the IMAP server (phone, webmail…), CRM means one of our users opened it here.
 */
enum class CommReadSource {
    CRM,
    EXTERNAL
}

enum class CommSendStatus {
    RECEIVED,
    SENDING,
    SENT,
    FAILED
}

/** Asynchronous IMAP work queued by user actions; each command is idempotent. */
enum class CommOutboxType {
    /** STORE +FLAGS (\Seen) for one message. */
    MARK_SEEN,
    /** STORE -FLAGS (\Seen) for one message — użytkownik oznaczył ją w CRM jako nieprzeczytaną. */
    MARK_UNSEEN,
    /** APPEND a copy of a message we sent over SMTP into the server's Sent folder. */
    APPEND_SENT
}

enum class CommOutboxStatus {
    PENDING,
    DONE,
    FAILED
}

/** One attachment extracted from MIME, inline images (cid:) included. */
data class ParsedAttachment(
    val fileName: String,
    val contentType: String,
    val contentId: String?,
    val inline: Boolean,
    val content: ByteArray
)

/**
 * Transport-agnostic result of parsing one MIME message. Everything downstream
 * (threading, sanitising, persistence) works on this shape only.
 */
data class ParsedEmail(
    /** Normalised Message-ID without angle brackets; null when the sender omitted it. */
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val fromEmail: String,
    val fromName: String?,
    val toEmails: List<String>,
    val ccEmails: List<String>,
    val subject: String?,
    val sentAt: Instant,
    val bodyHtml: String?,
    val bodyText: String?,
    val attachments: List<ParsedAttachment>,
    val imapUid: Long?,
    val seen: Boolean,
    /** Lowercase keys, limited to list/auto-reply headers used for lead heuristics. */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Pierwszy adres z nagłówka `Reply-To`, znormalizowany; null, gdy nagłówka nie ma.
     *
     * Na ten adres odpowiada KAŻDY program pocztowy (RFC 5322 §3.6.2) i dokładnie tam
     * formularz na stronie wpisuje klienta. Przez lata go nie czytaliśmy, więc odpowiedź
     * z CRM-a na zgłoszenie z formularza trafiała do robota — czyli do samego studia.
     */
    val replyToEmail: String? = null,
    val replyToName: String? = null
)

/** Snapshot pushed over WebSocket when a thread gains a message or changes state. */
data class CommThreadChangedEvent(
    val studioId: UUID,
    val threadId: UUID,
    /**
     * Wątek dostał nową wiadomość PRZYCHODZĄCĄ — jedyny przypadek, w którym warto
     * zaczepić użytkownika powiadomieniem. Własna wysyłka (z CRM-a albo z telefonu,
     * zassana z folderu Wysłane) zmienia wątek, ale nie jest niczym nowym dla tego,
     * kto ją napisał: idzie z tą flagą wyłączoną i odświeża tylko listę.
     */
    val newMessage: Boolean
)

/** Pushed when a message's read state changes (either side). */
data class CommMessageReadEvent(
    val studioId: UUID,
    val threadId: UUID,
    val messageId: UUID,
    val readSource: CommReadSource
)

/** Published after a reply leaves over SMTP — feeds the lead first-response metric. */
data class CommOutboundSentEvent(
    val studioId: UUID,
    val threadId: UUID,
    val sentAt: Instant
)

/**
 * Published after a brand-new INBOUND message is committed. Carries the sender so
 * listeners (form-mail auto-leads) can decide relevance without loading the row.
 */
data class CommInboundMessageStoredEvent(
    val studioId: UUID,
    val accountId: UUID,
    val threadId: UUID,
    val messageId: UUID,
    val fromEmail: String,
    val sentAt: Instant,
    /**
     * Wiadomość przedstawiła się nagłówkami jako automat — newsletter, autoresponder,
     * powiadomienie systemowe (patrz [AutomatedMailDetector]).
     *
     * Werdykt jedzie w zdarzeniu, bo nagłówki są dostępne WYŁĄCZNIE w chwili parsowania
     * MIME: baza przechowuje treść i adresy, nie `List-Id` ani `Precedence`. Listener,
     * który chciałby je sprawdzić później, musiałby po nie wrócić na serwer IMAP.
     *
     * Klasyfikacja leadów używa tego jako darmowego pre-filtra: newsletter i tak nigdy
     * nie jest zapytaniem o wycenę, więc nie ma po co płacić za jego przeczytanie modelem.
     */
    val automated: Boolean = false,
    /**
     * Wiadomość jest zgłoszeniem z formularza i dostała własny wątek ([CommThreadKind.FORM]).
     * Automaty leadów traktują ją jak osobne zapytanie, nawet gdy nadawcy (robota) nie
     * oznaczono ręcznie jako formularza.
     */
    val formSubmission: Boolean = false,
    /**
     * Nadawcą jest jedna ze skrzynek studia. Taki adres bywa zarejestrowany jako robot
     * formularza (WP Mail SMTP wysyła z adresu studia na adres studia) — ale mail „do
     * siebie" bez klienta w `Reply-To` nie jest zgłoszeniem i nie ma trafiać do odczytu.
     */
    val fromOwnMailbox: Boolean = false
)

/**
 * Po naszej stronie, czy po stronie klienta — książka adresowa, z której import,
 * wysyłka i podgląd wątku rozstrzygają, kto jest drugą stroną rozmowy.
 *
 * Adresy zawsze znormalizowane (lowercase + trim).
 */
data class MailAddressBook(
    /** Skrzynki podłączone przez studio — nasza strona każdej rozmowy. */
    val ownAddresses: Set<String>,
    /** Aktywne roboty formularzy (`form_mail_sources`). */
    val formSenders: Set<String>
) {
    fun isOwn(email: String?): Boolean = email != null && normalize(email) in ownAddresses

    fun isFormSender(email: String?): Boolean = email != null && normalize(email) in formSenders

    /**
     * Adres, na który odpowiedź nigdy nie jest odpowiedzią klientowi: nasza skrzynka,
     * robot formularza, serwer pocztowy.
     */
    fun isNotAClient(email: String?): Boolean =
        email.isNullOrBlank() || isOwn(email) || isFormSender(email) || DeliveryReportDetector.isSystemSender(email)

    companion object {
        val EMPTY = MailAddressBook(emptySet(), emptySet())

        fun normalize(email: String): String = email.trim().lowercase()
    }
}

/**
 * Źródło [MailAddressBook] dla studia. Interfejs po stronie poczty, bo rejestr robotów
 * formularzy należy do modułu leadów — import nie musi wiedzieć, skąd się bierze.
 */
interface MailAddressDirectory {
    fun addressBook(studioId: UUID): MailAddressBook

    /** Rejestr robotów się zmienił — następny odczyt ma go zobaczyć od razu. */
    fun invalidate(studioId: UUID) {}
}
