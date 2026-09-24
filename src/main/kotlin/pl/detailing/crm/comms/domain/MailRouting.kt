package pl.detailing.crm.comms.domain

/**
 * Zwroty i ostrzeżenia serwera pocztowego („Mail delivery failed", „message delayed
 * 24 hours").
 *
 * Rozpoznajemy je po nadawcy (mailer-daemon, postmaster) i po strukturze raportu
 * doręczenia (RFC 3464: `multipart/report; report-type=delivery-status`), a nie po
 * temacie — temat bywa po polsku, niemiecku albo wcale go nie ma.
 *
 * Po co: zwrot niesie w `References` identyfikator maila, którego dotyczy, więc
 * wątkowanie po przodkach wpinało go w rozmowę z klientem. U jednego studia martwe
 * przekierowanie na hostingu wygenerowało w ten sposób 45 zwrotów w jednym wątku
 * z zapytaniami klientów.
 */
object DeliveryReportDetector {

    private val SYSTEM_LOCAL_PARTS = setOf("mailer-daemon", "mailerdaemon", "mail-daemon", "postmaster")

    fun isSystemSender(email: String?): Boolean {
        val local = email?.trim()?.lowercase()?.substringBefore('@') ?: return false
        return local in SYSTEM_LOCAL_PARTS
    }

    fun isDeliveryReport(fromEmail: String, headers: Map<String, String>): Boolean {
        if (isSystemSender(fromEmail)) return true
        val normalized = headers.entries.associate { (key, value) -> key.lowercase() to value.lowercase() }
        val contentType = normalized["content-type"].orEmpty()
        if (contentType.contains("multipart/report") && contentType.contains("delivery-status")) return true
        // Exim i Postfix dopisują go do każdego zwrotu — nie występuje w poczcie od ludzi.
        return normalized.containsKey("x-failed-recipients")
    }
}

/**
 * Czy mail wygenerował formularz kontaktowy na stronie — po sygnaturze wtyczki albo
 * po adresie robota. Sam ten sygnał NIGDY nie wystarcza: zgłoszeniem jest dopiero
 * mail, który w `Reply-To` wskazuje kogoś spoza studia (patrz [InboundRouter]).
 */
object FormMailerSignature {

    /** Fragmenty nagłówków `X-Mailer` / `User-Agent` wtyczek formularzy i ich wysyłki. */
    private val MAILER_MARKERS = listOf(
        "wpmailsmtp", "wp mail smtp", "wordpress", "phpmailer", "wpforms", "contact form 7",
        "elementor", "gravity forms", "ninja forms", "fluent", "formspree", "jotform", "webflow"
    )

    /** Adresy, z których formularze wysyłają powiadomienia, gdy nikt ich nie przestawi. */
    private val ROBOT_LOCAL_PARTS = setOf(
        "wordpress", "noreply", "no-reply", "no_reply", "donotreply", "do-not-reply",
        "www-data", "formularz", "formularze", "forms", "form", "webform"
    )

    fun matches(fromEmail: String, headers: Map<String, String>): Boolean {
        val local = fromEmail.trim().lowercase().substringBefore('@')
        if (local in ROBOT_LOCAL_PARTS) return true
        val mailer = listOfNotNull(headers["x-mailer"], headers["user-agent"])
            .joinToString(" ")
            .lowercase()
        return MAILER_MARKERS.any { mailer.contains(it) }
    }
}

/** Dokąd import ma wpiąć przychodzącą wiadomość — patrz [InboundRouter]. */
sealed interface InboundRoute {

    /** Zwrot serwera pocztowego — do wątku systemowego skrzynki. */
    data object DeliveryReport : InboundRoute

    /**
     * Zgłoszenie z formularza z klientem w `Reply-To`. Zawsze własny wątek, a drugą
     * stroną rozmowy jest klient — deterministycznie, bez odczytu treści modelem.
     */
    data class FormRelay(
        val clientEmail: String,
        val clientName: String?,
        val relayEmail: String
    ) : InboundRoute

    /**
     * Mail od oznaczonego robota formularza, który `Reply-To` nie ustawia. Też własny
     * wątek, ale klienta znamy dopiero po odczycie treści — do tego czasu drugą stroną
     * jest robot, a odpowiedź blokuje bezpiecznik wysyłki.
     */
    data class FormRobot(val relayEmail: String) : InboundRoute

    /**
     * Mail ze skrzynki studia bez klienta w `Reply-To` — notatka do siebie, test,
     * przekazanie. Nie klejmy go z niczym po temacie: temat robota formularza jest
     * wspólny dla wszystkich zgłoszeń.
     */
    data object OwnMailbox : InboundRoute

    /** Zwykła poczta od klienta. */
    data object Direct : InboundRoute
}

/**
 * Rozstrzyga, czym jest przychodzący mail — zanim import wybierze mu wątek.
 *
 * Kolejność ma znaczenie:
 *  1. Zwrot serwera pocztowego — cokolwiek by niósł w nagłówkach, nie jest rozmową.
 *  2. Klient w `Reply-To` + nadawca po naszej stronie (skrzynka studia, zarejestrowany
 *     robot albo mail z sygnaturą wtyczki formularza) → zgłoszenie z formularza.
 *     `Reply-To` wskazujące na nas samych albo na robota nie jest klientem.
 *  3. Zarejestrowany robot bez klienta w `Reply-To` → zgłoszenie, klient z treści.
 *  4. Nasza skrzynka bez klienta → mail do siebie.
 *  5. Reszta — zwykła korespondencja.
 */
object InboundRouter {

    fun route(parsed: ParsedEmail, book: MailAddressBook): InboundRoute {
        val from = MailAddressBook.normalize(parsed.fromEmail)
        if (DeliveryReportDetector.isDeliveryReport(from, parsed.headers)) return InboundRoute.DeliveryReport

        val replyTo = parsed.replyToEmail
            ?.let(MailAddressBook::normalize)
            ?.takeIf { it.contains('@') && it != from && !book.isNotAClient(it) }

        val fromOwn = book.isOwn(from)
        val fromRobot = book.isFormSender(from)

        if (replyTo != null && (fromOwn || fromRobot || FormMailerSignature.matches(from, parsed.headers))) {
            return InboundRoute.FormRelay(
                clientEmail = replyTo,
                clientName = parsed.replyToName?.trim()?.takeIf { it.isNotEmpty() },
                relayEmail = from
            )
        }
        if (fromRobot && !fromOwn) return InboundRoute.FormRobot(from)
        if (fromOwn) return InboundRoute.OwnMailbox
        return InboundRoute.Direct
    }
}
