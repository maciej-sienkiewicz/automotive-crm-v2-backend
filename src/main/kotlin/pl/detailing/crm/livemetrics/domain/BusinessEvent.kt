package pl.detailing.crm.livemetrics.domain

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Rodzaje zdarzeń biznesowych śledzonych w czasie rzeczywistym.
 *
 * Każdy typ ma stały, mały zbiór wymiarów ([dimensions]) — to jedyne etykiety, po
 * których liczniki są rozbijane na pod-serie (np. `VISIT_CREATED:FROM_RESERVATION`).
 * Zbiór jest zamknięty z założenia: wymiar o nieograniczonej kardynalności (id
 * klienta, nazwa pliku) trafia wyłącznie do [BusinessEvent.attributes], czyli do
 * strumienia zdarzeń, a nigdy do kluczy liczników w Redisie.
 */
enum class BusinessEventType(
    /** Nazwa wymiaru, po którym powstają pod-serie; `null` = typ bez wymiaru. */
    val dimension: String?,
    /** Dozwolone wartości wymiaru. */
    val dimensions: Set<String>,
    /** Etykieta czytelna dla człowieka (dashboardy). */
    val label: String,
    /**
     * Czy typ ma kafel „dziś” (gauge odświeżany co 15 s).
     *
     * Domyślnie `false`, bo licznik dzienny kosztuje jeden HGET na tenanta i typ przy KAŻDYM
     * odświeżeniu: przy kilkudziesięciu typach i setkach tenantów to dziesiątki tysięcy poleceń
     * co 15 sekund za odpowiedź, której nikt nie czyta. „Dziś” ma sens wyłącznie dla zdarzeń
     * o realnym dobowym rytmie (rezerwacje, wizyty, leady, wiadomości). Reszta odpowiada na
     * pytanie „ile łącznie”, które obsługuje [BusinessEventType] przez sumę od początku —
     * odświeżaną co 5 minut jednym HGETALL na tenanta, niezależnie od liczby typów.
     */
    val daily: Boolean = false,
    /**
     * Czy zdarzenie niesie kwotę w groszach ([BusinessEvent.amountCents]).
     *
     * Kwota jest sumowana w osobnym hashu Redisa i eksportowana jako
     * `crm_business_events_sum_all_time`. Typ bez tej flagi musi mieć kwotę zerową —
     * inaczej sumowalibyśmy po cichu coś, czego nikt nie zamierzał zsumować.
     */
    val monetary: Boolean = false
) {
    /** Użytkownik utworzył rezerwację (Appointment) — ręcznie, z leada lub jako serię. */
    RESERVATION_CREATED(null, emptySet(), "Rezerwacje", daily = true),

    /**
     * Powstała wizyta. Wymiar `origin` rozróżnia wizytę utworzoną bezpośrednio
     * (walk-in „z palca”, bez wcześniejszej rezerwacji) od wizyty będącej
     * przekształceniem istniejącej rezerwacji.
     */
    VISIT_CREATED("origin", VisitOrigin.entries.map { it.name }.toSet(), "Wizyty", daily = true),

    /** Do cennika/katalogu tenanta dodano nową usługę (lub pakiet usług). */
    SERVICE_CREATED("kind", ServiceKind.entries.map { it.name }.toSet(), "Nowe usługi"),

    /** Udany upload zdjęcia; wymiar `target` mówi, do czego zdjęcie przypięto. */
    PHOTO_UPLOADED("target", PhotoTarget.entries.map { it.name }.toSet(), "Zdjęcia", daily = true),

    /** Powstał nowy rekord w ogólnej historii aktywności (audit log) tenanta. */
    ACTIVITY_LOGGED(null, emptySet(), "Aktywność", daily = true),

    /** Powstał nowy lead. Wymiar `source` mówi, skąd przyszedł (telefon, mail, formularz, ręcznie). */
    LEAD_CREATED("source", LeadSource.entries.map { it.name }.toSet(), "Leady", daily = true),

    /**
     * Wiadomość wyszła do odbiorcy. Wymiar `channel` rozróżnia trzy drogi, bo mierzą co innego:
     * `SMS` i `EMAIL` to wysyłka systemowa (przypomnienia, kampanie, karty wizyt) przez
     * `OutboundCommunicationGateway`, a `MAILBOX` to mail napisany ręcznie przez człowieka
     * w module Poczta. Wszystkie maile razem to `EMAIL + MAILBOX`.
     */
    MESSAGE_SENT("channel", MessageChannel.entries.map { it.name }.toSet(), "Wysłane wiadomości", daily = true),

    /** Utworzono kampanię. Wymiar `medium` = kanał kampanii (SMS, e-mail albo oba). */
    CAMPAIGN_CREATED("medium", CampaignMedium.entries.map { it.name }.toSet(), "Kampanie"),

    /** Do studia dodano pracownika. */
    EMPLOYEE_CREATED(null, emptySet(), "Pracownicy"),

    /** Wysłano link do Karty Wizyty lub Karty Rezerwacji. Wymiar `channel` = kanał dostarczenia. */
    VISIT_CARD_SENT("channel", VisitCardChannel.entries.map { it.name }.toSet(), "Karty wizyt"),

    /** Studio dodało profil na Instagramie do obserwowanych. */
    INSTAGRAM_PROFILE_ADDED(null, emptySet(), "Profile IG"),

    /** Studio podłączyło skrzynkę pocztową (moduł Poczta). */
    MAILBOX_CONNECTED(null, emptySet(), "Podłączona poczta"),

    // ── Tablica i kalendarz ──────────────────────────────────────────────────

    /** Na Tablicy powstało zadanie. */
    TASK_CREATED(null, emptySet(), "Zadania"),

    /** W kalendarzu powstało wydarzenie własne (nie rezerwacja). */
    CALENDAR_EVENT_CREATED(null, emptySet(), "Wydarzenia kalendarza"),

    // ── Zlecenia zbiorcze ────────────────────────────────────────────────────

    /** Do słownika zleceń zbiorczych dodano kontrahenta. */
    BATCH_CONTRACTOR_CREATED(null, emptySet(), "Kontrahenci"),

    /**
     * Do zlecenia zbiorczego dodano usługę — jedno zdarzenie na pozycję wpisu.
     *
     * Liczymy pozycje wpisu (pojazd + usługi u kontrahenta), a nie „douczanie" katalogu
     * nazwami: tamten zapis jest cichym efektem ubocznym, nie decyzją użytkownika.
     */
    BATCH_SERVICE_ADDED(null, emptySet(), "Usługi w zleceniach"),

    // ── Leady ────────────────────────────────────────────────────────────────

    /** Lead trafił w stan terminalny. Wymiar `status` mówi, czym się skończył. */
    LEAD_COMPLETED("status", LeadOutcome.entries.map { it.name }.toSet(), "Zakończone leady"),

    /**
     * Lead po raz pierwszy dostał niepustą wycenę.
     *
     * „Niepusta" znaczy: istnieje pozycja przyjęta przez człowieka i z kwotą
     * (`status != SUGGESTED && priceGross != null`) — sama sugestia AI ani pozycja bez ceny
     * nie wystarcza. Zdarzenie pada tylko przy przejściu pusta → niepusta, żeby liczyć leady
     * z wyceną, a nie kolejne edycje tej samej wyceny.
     */
    LEAD_QUOTED(null, emptySet(), "Leady z wyceną"),

    /** Do leada podpięto rezerwację. */
    LEAD_RESERVATION_LINKED(null, emptySet(), "Leady z rezerwacją"),

    // ── Klienci i pojazdy ────────────────────────────────────────────────────

    /** Powstał klient. Wymiar `origin` rozróżnia kartotekę od założenia przy rezerwacji. */
    CUSTOMER_CREATED("origin", RecordOrigin.entries.map { it.name }.toSet(), "Nowi klienci"),

    /** Klient podpisał zgodę (moduł zgód — w tym marketingowa). */
    CONSENT_SIGNED(null, emptySet(), "Podpisane zgody"),

    /** Do klienta dodano notatkę. */
    CUSTOMER_NOTE_ADDED(null, emptySet(), "Notatki klientów"),

    /** Klient został usunięty (anonimizacja RODO). */
    CUSTOMER_DELETED(null, emptySet(), "Usunięci klienci"),

    /** Klient po raz pierwszy dostał uzupełniony NIP (przejście brak → wartość). */
    CUSTOMER_NIP_SET(null, emptySet(), "Klienci z NIP"),

    /** Powstał pojazd. Wymiar `origin` jak przy kliencie. */
    VEHICLE_CREATED("origin", RecordOrigin.entries.map { it.name }.toSet(), "Nowe pojazdy"),

    /** Do pojazdu dodano notatkę. */
    VEHICLE_NOTE_ADDED(null, emptySet(), "Notatki pojazdów"),

    // ── Finanse ──────────────────────────────────────────────────────────────

    /**
     * Wystawiono dokument przychodowy. Wymiar `documentType` = faktura / paragon / inny.
     * Kwota: dokładne brutto zapisane na dokumencie (w groszach).
     */
    FINANCIAL_DOC_ISSUED(
        "documentType", FinancialDocumentKind.entries.map { it.name }.toSet(),
        "Dokumenty przychodowe", monetary = true
    ),

    /** Zarejestrowano dokument kosztowy. Kwota: dokładne brutto dokumentu. */
    EXPENSE_RECORDED(null, emptySet(), "Dokumenty kosztowe", monetary = true),

    /**
     * Ruch w kasie. Wymiar `operationType` niesie kierunek, więc kwota jest zawsze dodatnia —
     * inaczej suma w Prometheusie przestałaby być monotoniczna.
     */
    CASH_OPERATION(
        "operationType", CashOperationKind.entries.map { it.name }.toSet(),
        "Operacje kasowe", monetary = true
    ),

    // ── Statystyki ───────────────────────────────────────────────────────────

    /** Powstała kategoria. Wymiar `kind` rozróżnia kategorie przychodowe od kosztowych. */
    STATS_CATEGORY_CREATED("kind", StatsCategoryKind.entries.map { it.name }.toSet(), "Kategorie"),

    // ── Instagram ────────────────────────────────────────────────────────────

    /** Studio oceniło treść. Wymiar `target`: post konkurencji vs treść wygenerowana przez AI. */
    INSTAGRAM_CONTENT_RATED("target", RatedContentTarget.entries.map { it.name }.toSet(), "Ocenione treści"),

    /** Ktoś otworzył szczegóły kampanii reklamowej. Jedyne zdarzenie czysto odczytowe. */
    INSTAGRAM_AD_DETAILS_VIEWED(null, emptySet(), "Podglądy kampanii"),

    // ── Ustawienia ───────────────────────────────────────────────────────────

    /** Zapisano wpis godzin pracownika. */
    WORKTIME_ENTRY_SAVED(null, emptySet(), "Wpisy godzin"),

    /** Dodano kolor oznaczeń rezerwacji. */
    APPOINTMENT_COLOR_CREATED(null, emptySet(), "Dodane kolory"),

    /** Zapisano konfigurację automatów/szablonów SMS. */
    SMS_TEMPLATE_UPDATED(null, emptySet(), "Edycje szablonów SMS"),

    /** Dodano rolę. */
    ROLE_CREATED(null, emptySet(), "Dodane role"),

    /** Sparowano tablet do podpisów. */
    TABLET_PAIRED(null, emptySet(), "Parowania tabletu"),

    // ── Wizyty ───────────────────────────────────────────────────────────────

    /**
     * Skorzystano z upsellingu. Wymiar `stage`: `REQUESTED` = klient wybrał usługę na Karcie
     * Wizyty, `CONFIRMED` = potwierdził ją SMS-em. Jedna usługa może dać oba zdarzenia —
     * to dwa różne kroki lejka, nie duplikat.
     */
    UPSELL_USED("stage", UpsellStage.entries.map { it.name }.toSet(), "Upselling"),

    /** Zmieniono cenę pozycji w trakcie realizacji wizyty. */
    VISIT_PRICE_EDITED(null, emptySet(), "Edycje ceny"),

    /** Wysłano do klienta SMS z prośbą o potwierdzenie ceny. */
    PRICE_CONFIRMATION_REQUESTED(null, emptySet(), "Prośby o potwierdzenie ceny"),

    /** Dodano komentarz do wizyty. Wymiar `commentType` = wewnętrzny vs dla klienta. */
    VISIT_COMMENT_ADDED("commentType", VisitCommentKind.entries.map { it.name }.toSet(), "Komentarze wizyt"),

    /** Podpisano protokół. Wymiar `stage`: przyjęcie (`CHECK_IN`) vs wydanie (`CHECK_OUT`). */
    PROTOCOL_SIGNED("stage", ProtocolSignatureStage.entries.map { it.name }.toSet(), "Podpisane protokoły"),

    /** Mapa uszkodzeń uzupełniona PONOWNIE (rewizja > 1) — pierwsze wypełnienie się nie liczy. */
    DAMAGE_MAP_REFILLED(null, emptySet(), "Ponowne mapy uszkodzeń");

    /** Nazwa serii bazowej (bez wymiaru). */
    val series: String get() = name

    /** Nazwa pod-serii dla danej wartości wymiaru, np. `VISIT_CREATED:DIRECT`. */
    fun subSeries(dimensionValue: String): String = "$name:$dimensionValue"

    /** Wszystkie serie, jakie ten typ może wytworzyć: bazowa + po jednej na wartość wymiaru. */
    fun allSeries(): List<String> = listOf(series) + dimensions.map { subSeries(it) }

    companion object {
        /** Pełna lista serii dla dashboardów — także tych, które nie miały jeszcze zdarzeń. */
        fun allKnownSeries(): List<String> = entries.flatMap { it.allSeries() }
    }
}

enum class VisitOrigin {
    /** Wizyta utworzona bezpośrednio — bez wcześniejszej rezerwacji w kalendarzu. */
    DIRECT,

    /** Wizyta powstała z przekształcenia / potwierdzenia istniejącej rezerwacji. */
    FROM_RESERVATION
}

enum class ServiceKind { SERVICE, PACKAGE }

/**
 * Kanał wysyłki dla [BusinessEventType.MESSAGE_SENT].
 *
 * `MAILBOX` celowo stoi obok `EMAIL`, zamiast się w nim rozpływać: mail wygenerowany przez
 * automat i mail napisany ręcznie w Poczcie to dwie różne rzeczy, a rozdzielenie ich po fakcie
 * (gdy oba wpadły do jednego kubełka) jest niemożliwe.
 */
enum class MessageChannel { SMS, EMAIL, MAILBOX }

/**
 * Kanał kampanii. Świadoma kopia `campaigns.domain.CampaignChannel` — moduł metryk nie zależy
 * od modułów biznesowych. Nowa wartość po tamtej stronie nie wywróci wysyłki: konstruktor
 * [BusinessEvent] odrzuci nieznany wymiar, a publisher zaloguje błąd i pójdzie dalej.
 */
enum class CampaignMedium { SMS, EMAIL, BOTH }

/** Kanał dostarczenia Karty Wizyty / Karty Rezerwacji. */
enum class VisitCardChannel { EMAIL, SMS }

enum class PhotoTarget { VISIT, VEHICLE, BATCH_ORDER, CHECKIN }

/**
 * Stan terminalny leada. Świadoma kopia trzech wartości `shared.LeadStatus` — tych, które
 * kończą cykl życia. Stany pośrednie (NEW, IN_PROGRESS, CONFIRMED) nie są zakończeniem
 * i nie mają prawa tu trafić.
 */
enum class LeadOutcome { COMPLETED, LOST, NO_SHOW }

/**
 * Skąd wzięła się kartoteka. `DIRECT` = ktoś świadomie założył klienta/pojazd w module,
 * `APPOINTMENT` = rekord powstał mimochodem przy tworzeniu rezerwacji. Rozróżnienie mówi,
 * czy studio prowadzi bazę, czy tylko zapisuje to, co akurat przyjechało.
 */
enum class RecordOrigin { DIRECT, APPOINTMENT }

/** Rodzaj dokumentu finansowego. Kopia `finance.domain.DocumentType`. */
enum class FinancialDocumentKind { RECEIPT, INVOICE, OTHER }

/** Rodzaj ruchu kasowego. Kopia `finance.domain.CashOperationType`. */
enum class CashOperationKind { PAYMENT_IN, PAYMENT_OUT, MANUAL_ADJUSTMENT }

/** Rodzaj kategorii w statystykach: przychodowa (usługowa) vs kosztowa. */
enum class StatsCategoryKind { SERVICE, COST }

/** Co oceniono: post konkurencji czy treść wygenerowaną przez AI. */
enum class RatedContentTarget { COMPETITOR, AI }

/** Krok lejka upsellingu: klient wybrał usługę, klient potwierdził ją SMS-em. */
enum class UpsellStage { REQUESTED, CONFIRMED }

/** Rodzaj komentarza do wizyty. Kopia `shared.CommentType` (INTERNAL / FOR_CUSTOMER). */
enum class VisitCommentKind { INTERNAL, FOR_CUSTOMER }

/** Etap protokołu. Kopia `shared.ProtocolStage` — protokoły zgód nie mają etapu i się nie liczą. */
enum class ProtocolSignatureStage { CHECK_IN, CHECK_OUT }

/**
 * Pojedyncze zdarzenie biznesowe — niezmienny fakt: „u tenanta X o czasie T stało się Y”.
 *
 * [dimensionValue] musi należeć do [BusinessEventType.dimensions] (albo być `null`,
 * gdy typ nie ma wymiaru); [attributes] to dowolny, płaski kontekst wysyłany dalej
 * strumieniem (id encji, nazwa, kto) — nigdy nie trafia do kluczy liczników.
 */
data class BusinessEvent(
    val tenantId: StudioId,
    val type: BusinessEventType,
    val occurredAt: Instant = Instant.now(),
    val dimensionValue: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    /**
     * Kwota w groszach, sumowana osobno od liczby zdarzeń (tylko dla typów `monetary`).
     *
     * **Zawsze dokładne brutto zapisane na dokumencie** — nigdy odtworzone z netta. Przejście
     * brutto → netto → brutto nie jest tożsamością na siatce groszowej, więc przeliczona kwota
     * rozjechałaby się z tym, co widzi klient na fakturze (patrz `VatRate.resolveGrossAmount`).
     *
     * Nieujemna: suma w Prometheusie musi rosnąć monotonicznie, więc kierunek operacji idzie
     * do wymiaru (`CASH_OPERATION.operationType`), a nie do znaku kwoty.
     */
    val amountCents: Long = 0,
    val id: UUID = UUID.randomUUID()
) {
    init {
        if (type.dimension == null) {
            require(dimensionValue == null) { "${type.name} has no dimension, got '$dimensionValue'" }
        } else {
            require(dimensionValue != null && dimensionValue in type.dimensions) {
                "${type.name}.${type.dimension} must be one of ${type.dimensions}, got '$dimensionValue'"
            }
        }
        require(amountCents >= 0) { "${type.name} amountCents must be >= 0, got $amountCents" }
        require(type.monetary || amountCents == 0L) {
            "${type.name} is not monetary, got amountCents=$amountCents"
        }
    }

    /** Serie, których liczniki to zdarzenie inkrementuje: bazowa i (jeśli jest) pod-seria. */
    fun series(): List<String> =
        if (dimensionValue == null) listOf(type.series) else listOf(type.series, type.subSeries(dimensionValue))
}
