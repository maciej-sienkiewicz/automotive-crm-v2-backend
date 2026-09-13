package pl.detailing.crm.dashboard.hints

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.instagram.analytics.MetricsCalculator
import pl.detailing.crm.instagram.analytics.WeeklyDigestDto
import pl.detailing.crm.instagram.infrastructure.InstagramReportRepository
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.leads.analytics.AwaitingWorkDto
import pl.detailing.crm.leads.analytics.AwaitingWorkService
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Zbiera podpowiedzi paska na Tablicy. Jedno wywołanie, wyłącznie odczyty —
 * żadna reguła nie ma prawa niczego generować (digest Instagrama czytamy
 * z cache'u tygodniowego; brak cache'u = brak podpowiedzi, nie wywołanie LLM
 * w środku ładowania Tablicy).
 *
 * Każda reguła jest niezależna i owinięta w runCatching: awaria jednego
 * źródła (np. moduł komunikacji bez konfiguracji) nie może zgasić paska.
 */
@Service
class GetDashboardHintsHandler(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val workTimePeriodRepository: WorkTimePeriodRepository,
    private val instagramReportRepository: InstagramReportRepository,
    private val commThreadRepository: CommThreadRepository,
    private val ksefCredentialsRepository: KsefCredentialsRepository,
    private val awaitingWorkService: AwaitingWorkService,
    private val visitRepository: VisitRepository,
    private val dismissalRepository: DashboardHintDismissalRepository,
    private val permissionCheckService: PermissionCheckService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val warsawZone = ZoneId.of("Europe/Warsaw")

    companion object {
        /** Od którego dnia przed końcem miesiąca przypominamy o kartach czasu pracy. */
        const val WORKTIME_WINDOW_DAYS = 5

        /** Ile nieprzeczytanych wiadomości uznajemy za bałagan wart podpowiedzi. */
        const val UNREAD_MAIL_THRESHOLD = 10L

        /**
         * KSeF-owy upsell tylko dla studiów, które faktycznie prowadzą wizyty —
         * świeże konto demo nie potrzebuje zachęty do automatyzacji faktur.
         */
        const val KSEF_MIN_COMPLETED_VISITS = 3
    }

    suspend fun handle(principal: UserPrincipal): List<DashboardHint> =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(warsawZone)
            val hints = mutableListOf<DashboardHint>()

            val safely: (String, () -> DashboardHint?) -> Unit = { name, rule ->
                runCatching { rule()?.let(hints::add) }
                    .onFailure { logger.warn("Podpowiedź {} pominięta: {}", name, it.message) }
            }

            val digest = runCatching { readCachedDigest(principal) }
                .onFailure { logger.warn("Digest Instagrama nieczytelny: {}", it.message) }
                .getOrNull()
            val worktime = runCatching { worktimeHint(principal, today) }
                .onFailure { logger.warn("Podpowiedź worktime pominięta: {}", it.message) }
                .getOrNull()

            // Kolejność na liście = ważność. Na samej górze pieniądze, które właśnie
            // wychodzą za drzwi: klient napisał, piłka jest po naszej stronie, a każdy
            // dzień ciszy to rosnące ryzyko, że pójdzie gdzie indziej. Dopiero potem
            // sprawy operacyjne z terminem, sygnały zewnętrzne i na końcu upselle
            // (w tym pytanie o nieużywaną funkcję kart — to rozmowa o konfiguracji,
            // nie zaległość).
            safely("leads-awaiting") { leadsAwaitingHint(principal) }
            safely("worktime-missing") { worktime?.takeIf { it.kind == DashboardHintKind.WORKTIME_MISSING } }
            safely("competitor") { competitorStandoutHint(principal, digest) }
            safely("unread-mail") { unreadMailHint(principal) }
            safely("self-silent") { selfSilentHint(principal, digest, today) }
            safely("worktime-unused") { worktime?.takeIf { it.kind == DashboardHintKind.WORKTIME_UNUSED } }
            safely("ksef") { ksefUpsellHint(principal) }

            filterDismissed(principal, hints)
        }

    // ── Leady: pieniądze czekające na naszą odpowiedź ──────────────────────────

    /**
     * Zaległe odpowiedzi na leady — jedyna podpowiedź z wagą [DashboardHintSeverity.CRITICAL].
     *
     * Pojawia się dokładnie wtedy, gdy jest lead, w którym klient napisał ostatni,
     * a my jeszcze nie odpisaliśmy (piłka po naszej stronie). To nie jest sygnał
     * „na przyszłość" ani upsell: to pieniądze, które wyjdą za drzwi, jeśli nikt
     * nie odpowie — dlatego stoi na górze listy i świeci na czerwono.
     *
     * „Aktywny moduł leadów" = uprawnienie LEADS_MANAGE. Ten sam warunek trzyma
     * zakładkę „Leady" i całe API analityki leadów; bez modułu nie ma czego liczyć.
     * Sam rachunek dzielimy z pasmem „Czeka na Ciebie" w analityce ([AwaitingWorkService]),
     * żeby pasek Tablicy i ekran analityki nigdy nie pokazały dwóch różnych kwot.
     */
    private fun leadsAwaitingHint(principal: UserPrincipal): DashboardHint? {
        if (!hasPermission(principal, Permission.LEADS_MANAGE)) return null

        val awaiting = awaitingWorkService.awaitingWork(principal.studioId)
        if (awaiting.count == 0) return null

        return DashboardHint(
            // Klucz bez kwoty i bez liczby: sześć czy siedem zaległych rozmów to wciąż
            // ta sama zaległość, a zamknięcie ma ją uciszyć na tydzień, nie wracać po
            // każdej nowej wiadomości.
            key = "LEADS_AWAITING",
            kind = DashboardHintKind.LEADS_AWAITING,
            text = awaitingText(awaiting),
            action = DashboardHintAction(
                label = "Odpisz im",
                type = DashboardHintActionType.NAVIGATE,
                // Ten sam deep-link, co przycisk „Odpisz im" w analityce leadów:
                // kolejka „Twój ruch", czyli rozmowy z piłką po naszej stronie.
                url = "/leads?awaiting=1"
            ),
            permanentDismiss = false,
            severity = DashboardHintSeverity.CRITICAL
        )
    }

    /**
     * Zdanie budowane dokładnie tak, jak brzmi pasmo „Czeka na Ciebie" w analityce
     * leadów — te same słowa i formaty, żeby użytkownik nie zobaczył dwóch wersji
     * tej samej informacji. Kwotę okrągłą części leada pomijamy: na pasku liczy
     * się jedno konkretne nazwisko i to, jak długo czeka.
     */
    private fun awaitingText(awaiting: AwaitingWorkDto): String {
        // Miejscownik: zdanie brzmi „w N rozmowach" / „w 1 rozmowie".
        val conversationWord = if (awaiting.count == 1) "rozmowie" else "rozmowach"
        val sentence = StringBuilder(
            "Czeka na Ciebie ${formatMoney(awaiting.value)} w ${awaiting.count} $conversationWord, " +
                "w których piłka jest po Twojej stronie."
        )
        awaiting.oldest?.let { oldest ->
            val vehicle = oldest.vehicle?.let { " - $it" } ?: ""
            sentence.append(" Najdłużej czeka ${oldest.name}$vehicle, ${dayPhrase(oldest.waitingDays)}.")
        }
        return sentence.toString()
    }

    /**
     * „17 283 zł": pełne złote, bez groszy, spacja co trzy cyfry. Ten sam format,
     * co `formatMoney` w interfejsie leadów — kwota-hasło do przeczytania jednym
     * spojrzeniem. Wartości leadów są nieujemne, więc nie ma tu obsługi minusa.
     */
    private fun formatMoney(grosze: Long): String {
        val zloty = Math.round(grosze / 100.0)
        val grouped = zloty.toString()
            .reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
        return "$grouped zł"
    }

    /** „czeka 1 dzień", „czeka 8 dni", „od dziś" — jak `dayWord` w analityce leadów. */
    private fun dayPhrase(days: Int): String = when {
        days <= 0 -> "od dziś"
        days == 1 -> "czeka 1 dzień"
        else -> "czeka $days dni"
    }

    // ── Karty Czasu Pracy ────────────────────────────────────────────────────

    private fun worktimeHint(principal: UserPrincipal, today: LocalDate): DashboardHint? {
        // Rozliczenie pracowników to sprawa właściciela; pracownikowi ta
        // podpowiedź mówiłaby o cudzych zaległościach.
        if (!principal.isOwner) return null

        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        if (today.isBefore(monthEnd.minusDays((WORKTIME_WINDOW_DAYS - 1).toLong()))) return null

        val trackedRoleIds = roleRepository.findByStudioId(principal.studioId.value)
            .filter { it.trackWorkTime }
            .map { it.id }
            .toSet()
        if (trackedRoleIds.isEmpty()) return null

        val trackedUsers = userRepository.findActiveByStudioId(principal.studioId.value)
            .filter { it.customRoleId in trackedRoleIds }
        if (trackedUsers.isEmpty()) return null

        val period = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val missing = trackedUsers.filter { user ->
            val entry = workTimePeriodRepository.findByUserIdAndPeriod(user.id, period)
            entry == null || entry.status !in setOf(PeriodStatus.SUBMITTED, PeriodStatus.APPROVED)
        }
        if (missing.isEmpty()) return null

        if (missing.size == trackedUsers.size) {
            // Nikt nie korzysta: zamiast poganiać, pytamy czy funkcja w ogóle
            // jest potrzebna. Stąd przycisk wyłączenia, nie link do listy.
            return DashboardHint(
                key = "WORKTIME_UNUSED_$period",
                kind = DashboardHintKind.WORKTIME_UNUSED,
                text = "Koniec miesiąca, a Twoi pracownicy nie uzupełnili Kart Czasu Pracy. Czy korzystasz z tej funkcji?",
                action = DashboardHintAction(
                    label = "Wyłącz funkcję",
                    type = DashboardHintActionType.DISABLE_WORKTIME,
                    url = null
                ),
                permanentDismiss = true
            )
        }

        val names = missing.map { "${it.firstName} ${it.lastName}".trim() }
        val who = when (missing.size) {
            1 -> names.first()
            2 -> "${names[0]} i ${names[1]}"
            else -> "${names[0]}, ${names[1]} i ${missing.size - 2} innych"
        }
        val verb = if (missing.size == 1) "nie uzupełnił(a)" else "nie uzupełnili"

        return DashboardHint(
            key = "WORKTIME_MISSING_$period",
            kind = DashboardHintKind.WORKTIME_MISSING,
            text = "Zbliża się koniec miesiąca, a $who jeszcze $verb Karty Czasu Pracy.",
            action = DashboardHintAction(
                label = "Zobacz pracowników",
                type = DashboardHintActionType.NAVIGATE,
                url = "/settings?tab=team"
            ),
            permanentDismiss = false
        )
    }

    // ── Instagram ────────────────────────────────────────────────────────────

    /**
     * Wyłącznie odczyt tygodniowego cache'u digestu. Generowanie (z wywołaniem
     * LLM) zostaje w WeeklyDigestService i jego harmonogramie — pasek Tablicy
     * nie może być tym, co je uruchamia.
     */
    private fun readCachedDigest(principal: UserPrincipal): WeeklyDigestDto? {
        if (!hasPermission(principal, Permission.MARKETING_MANAGE)) return null
        val weekStart = MetricsCalculator.currentWeekStart()
        val entity = instagramReportRepository
            .findByStudioIdAndPeriodStart(principal.studioId.value, weekStart) ?: return null
        return runCatching { objectMapper.readValue<WeeklyDigestDto>(entity.payload) }.getOrNull()
    }

    private fun competitorStandoutHint(principal: UserPrincipal, digest: WeeklyDigestDto?): DashboardHint? {
        val standout = digest?.profiles
            ?.firstOrNull { !it.isSelf && it.verdict == "STANDOUT" && it.highlight != null }
            ?: return null
        val post = standout.highlight ?: return null

        return DashboardHint(
            key = "COMPETITOR_STANDOUT_${standout.username}_${digest.weekStart}",
            kind = DashboardHintKind.COMPETITOR_STANDOUT,
            text = "Profil @${standout.username} ma znaczący wzrost zaangażowania pod ostatnim postem. Zobacz, co dodali.",
            action = DashboardHintAction(
                label = "Przejdź do postu",
                type = DashboardHintActionType.EXTERNAL,
                url = post.permalink
            ),
            permanentDismiss = false
        )
    }

    private fun selfSilentHint(principal: UserPrincipal, digest: WeeklyDigestDto?, today: LocalDate): DashboardHint? {
        // "W tym tygodniu jeszcze nic..." w poniedziałek rano to nagabywanie,
        // nie informacja. Odzywamy się od czwartku, gdy tydzień naprawdę ucieka.
        if (today.dayOfWeek < DayOfWeek.THURSDAY) return null

        val self = digest?.profiles?.firstOrNull { it.isSelf } ?: return null
        if (self.postsCount > 0) return null

        return DashboardHint(
            key = "SELF_IG_SILENT_${digest.weekStart}",
            kind = DashboardHintKind.SELF_IG_SILENT,
            text = "W tym tygodniu jeszcze nic nie dodałeś na Instagrama.",
            action = DashboardHintAction(
                label = "Zobacz konkurencję",
                type = DashboardHintActionType.NAVIGATE,
                url = "/instagram"
            ),
            permanentDismiss = false
        )
    }

    // ── Poczta ───────────────────────────────────────────────────────────────

    private fun unreadMailHint(principal: UserPrincipal): DashboardHint? {
        if (!hasPermission(principal, Permission.LEADS_MANAGE)) return null

        val unread = commThreadRepository.countUnread(principal.studioId.value)
        if (unread < UNREAD_MAIL_THRESHOLD) return null

        return DashboardHint(
            // Klucz bez liczby: 12 czy 15 nieprzeczytanych to wciąż ta sama
            // zaległość i zamknięcie ma ją uciszyć, a nie wracać po każdym mailu.
            key = "UNREAD_MAIL",
            kind = DashboardHintKind.UNREAD_MAIL,
            text = "W skrzynce pocztowej masz $unread nieprzeczytanych wiadomości. Zrób porządek.",
            action = DashboardHintAction(
                label = "Otwórz pocztę",
                type = DashboardHintActionType.NAVIGATE,
                url = "/communication"
            ),
            permanentDismiss = false
        )
    }

    // ── KSeF ─────────────────────────────────────────────────────────────────

    private fun ksefUpsellHint(principal: UserPrincipal): DashboardHint? {
        if (!principal.isOwner) return null
        if (ksefCredentialsRepository.existsByStudioId(principal.studioId.value)) return null

        val monthAgo = Instant.now().minusSeconds(30L * 24 * 3600)
        val recentCompleted = visitRepository.findCompletedByStudioIdAndDateRange(
            studioId = principal.studioId.value,
            from = monthAgo,
            to = Instant.now()
        )
        if (recentCompleted.size < KSEF_MIN_COMPLETED_VISITS) return null

        return DashboardHint(
            key = "KSEF_UPSELL",
            kind = DashboardHintKind.KSEF_UPSELL,
            text = "Nie korzystasz z automatyzacji KSeF. Nie męczy Cię przepisywanie faktur? Aplikacja wystawi fakturę w 2 sekundy.",
            action = DashboardHintAction(
                label = "Dowiedz się więcej",
                type = DashboardHintActionType.NAVIGATE,
                url = "/finances"
            ),
            permanentDismiss = true
        )
    }

    // ── Wspólne ──────────────────────────────────────────────────────────────

    private fun hasPermission(principal: UserPrincipal, permission: Permission): Boolean =
        permissionCheckService.hasPermission(principal.userId, principal.studioId, permission)

    private fun filterDismissed(principal: UserPrincipal, hints: List<DashboardHint>): List<DashboardHint> {
        if (hints.isEmpty()) return hints
        val now = Instant.now()
        val dismissedKeys = dismissalRepository.findByUserId(principal.userId.value)
            .filter { it.snoozeUntil == null || it.snoozeUntil!!.isAfter(now) }
            .map { it.hintKey }
            .toSet()
        return hints.filter { it.key !in dismissedKeys }
    }
}
