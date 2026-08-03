# Moduł Kampanii SMS & E-mail — Projekt

> Status: propozycja (Lead Dev + PM + UX) · Data: 2026-08-03
> Zastępuje w całości legacy `smscampaigns` (kampanie ręczne) — automatyzacje transakcyjne
> (przypomnienia o wizycie, potwierdzenia rezerwacji itd.) pozostają osobnym, istniejącym modułem.

---

## 1. Cel i zasady projektowe

Moduł ma pozwolić właścicielowi studia detailingu — osobie nietechnicznej — w kilka minut
zaplanować wysyłkę SMS/e-mail do trafnie dobranej grupy klientów.

Zasady:

1. **Scenariusze zamiast filtrów.** Użytkownik nie zaczyna od pustego formularza z 15 polami,
   tylko od gotowego scenariusza ("Reaktywacja", "Święta", "Przypomnienie po usłudze"), który
   wstępnie wypełnia odbiorców i treść. Tryb "od zera" jest zawsze dostępny, ale nie jest domyślny.
2. **Pełna przejrzystość zasięgu.** Na każdym kroku widać: ilu klientów pasuje do filtrów,
   ilu odpadnie (brak zgody / brak numeru / limit anty-spam) i ile ostatecznie SMS-ów
   (kredytów) to będzie kosztowało.
3. **Bezpieczeństwo domyślnie.** Zgody RODO, godziny ciszy i limit częstotliwości są wbudowane
   w silnik wysyłki — użytkownik nie może przypadkowo zaspamować bazy ani wysłać do osób bez zgody.
4. **Dwa rodzaje kampanii, jeden model.** Jednorazowa (świąteczna, okazjonalna) i automatyczna
   (cyklicznie zapisująca klientów spełniających warunek, np. "180 dni po powłoce ceramicznej")
   różnią się tylko sposobem uruchamiania — reszta (odbiorcy, treść, raportowanie) jest wspólna.

---

## 2. Na jakich danych stoimy (audyt schematu)

Wszystko poniżej już istnieje w bazie — moduł kampanii **niczego nie duplikuje**, tylko czyta:

| Obszar | Tabele / kolumny | Co dają kampaniom |
|---|---|---|
| Klienci | `customers` (imię, nazwisko, `phone`, `email`, dane firmowe, `is_active`, `created_at`) | adresaci, personalizacja, filtr firma/indywidualny, "nowi klienci" |
| Pojazdy | `vehicles` (`brand`, `model`, `year_of_production`, `color`, `paint_type`) + `vehicle_owners` (M:N, rola) | targetowanie po marce/modelu/roczniku/typie lakieru |
| Wizyty | `visits` (`status`, `scheduled_date`, `pickup_date`, `actual_completion_date`) | data ostatniej wizyty, liczba wizyt, trigger "X dni po usłudze" |
| Usługi na wizytach | `visit_service_items` (`service_id`, `service_name`, `final_price_gross`, `status`) | filtr "korzystał / nie korzystał z usługi", przychód per klient |
| Katalog usług | `services` | słownik do wyboru usług w filtrach |
| Zgody RODO | `consent_definitions.marketing_channels` (EMAIL/SMS) + `customer_consents` (`signed_at`, `revoked_at`) | **gotowy mechanizm zgody marketingowej per kanał** |
| Historia komunikacji | `communication_log` (kanał, typ, treść, status, `sent_at`) | limit częstotliwości, timeline klienta, dedup |
| Kredyty SMS | `sms_credit_balances` + transakcje | saldo, blokada wysyłki bez środków, koszt kampanii |
| Nadawca SMS | `sms_automation_configs.sms_sender_name` (+ dokument autoryzacyjny) | pole nadawcy w SMSAPI |
| Studio | `studio_settings` (nazwa, telefon, www) | placeholdery `{{studio}}`, stopka e-maila |
| Providery | `SmsApiProvider` (SMSAPI, webhook odpowiedzi przychodzących), `EmailProvider` | istniejąca infrastruktura wysyłki — reużywamy |

Wniosek kluczowy: **nie potrzebujemy żadnych nowych źródeł danych** — potrzebujemy jedynie
tabel samego modułu kampanii i jednej flagi opt-out.

---

## 3. Model domenowy i schemat bazy

### 3.1 `campaigns`

```sql
CREATE TABLE campaigns (
    id                  uuid PRIMARY KEY,
    studio_id           uuid NOT NULL,
    name                varchar(200) NOT NULL,

    -- ONE_TIME  – wysyłka raz (teraz albo o zaplanowanej dacie)
    -- AUTOMATIC – silnik codziennie zapisuje klientów spełniających warunek triggera
    kind                varchar(20) NOT NULL,          -- ONE_TIME | AUTOMATIC

    channel             varchar(10) NOT NULL,          -- SMS | EMAIL | BOTH

    -- DRAFT → SCHEDULED → SENDING → COMPLETED   (ONE_TIME)
    -- DRAFT → ACTIVE ⇄ PAUSED → ARCHIVED        (AUTOMATIC)
    -- + CANCELLED (z DRAFT/SCHEDULED), FAILED (błąd krytyczny wysyłki)
    status              varchar(20) NOT NULL,

    -- Definicja odbiorców — wersjonowany JSONB (patrz §4)
    audience            jsonb NOT NULL,

    -- Treść
    sms_template        text,
    email_subject       text,
    email_body          text,

    -- Harmonogram
    scheduled_at        timestamptz,                   -- ONE_TIME: kiedy wysłać (NULL = natychmiast po zatwierdzeniu)
    trigger_config      jsonb,                         -- AUTOMATIC: patrz §5.2

    -- Zamrożone liczniki (aktualizowane przez silnik)
    recipients_total    int NOT NULL DEFAULT 0,
    recipients_sent     int NOT NULL DEFAULT 0,
    recipients_failed   int NOT NULL DEFAULT 0,
    recipients_skipped  int NOT NULL DEFAULT 0,
    credits_spent       int NOT NULL DEFAULT 0,

    created_by uuid NOT NULL, updated_by uuid NOT NULL,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    started_at timestamptz, completed_at timestamptz
);
CREATE INDEX idx_campaigns_studio_status ON campaigns (studio_id, status);
CREATE INDEX idx_campaigns_due ON campaigns (status, scheduled_at);
```

### 3.2 `campaign_recipients` — snapshot odbiorców

Tworzony w momencie startu wysyłki (ONE_TIME) lub zapisania klienta przez trigger (AUTOMATIC).
Daje audyt "do kogo i dlaczego poszło / nie poszło" oraz idempotencję.

```sql
CREATE TABLE campaign_recipients (
    id              uuid PRIMARY KEY,
    campaign_id     uuid NOT NULL REFERENCES campaigns(id),
    studio_id       uuid NOT NULL,
    customer_id     uuid NOT NULL,
    channel         varchar(10) NOT NULL,              -- SMS | EMAIL (BOTH → dwa wiersze)
    address         varchar(255) NOT NULL,             -- numer E.164 lub e-mail (snapshot!)
    rendered_body   text NOT NULL,                     -- treść po podstawieniu placeholderów
    status          varchar(30) NOT NULL,
    -- PENDING | SENT | FAILED
    -- SKIPPED_NO_CONSENT | SKIPPED_NO_ADDRESS | SKIPPED_FREQUENCY_CAP
    -- SKIPPED_OPTED_OUT | EXCLUDED_MANUALLY | SKIPPED_NO_CREDITS
    error_message   text,
    communication_log_id uuid,                         -- powiązanie z communication_log
    scheduled_for   timestamptz NOT NULL,
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL,

    -- AUTOMATIC: klient dostaje wiadomość z danej kampanii najwyżej raz na wizytę-trigger
    CONSTRAINT uq_campaign_recipient UNIQUE (campaign_id, customer_id, channel, trigger_source_visit_id)
);
CREATE INDEX idx_campaign_recipients_due ON campaign_recipients (status, scheduled_for);
CREATE INDEX idx_campaign_recipients_campaign ON campaign_recipients (campaign_id, status);
```

(`trigger_source_visit_id uuid NULL` — dla kampanii AUTOMATIC wizyta, która wywołała zapis;
dla ONE_TIME zawsze NULL, unikalność zapewnia wtedy para kampania+klient+kanał.)

### 3.3 `campaign_settings` — ustawienia per studio

```sql
CREATE TABLE campaign_settings (
    studio_id                uuid PRIMARY KEY,
    quiet_hours_start        time NOT NULL DEFAULT '20:00',
    quiet_hours_end          time NOT NULL DEFAULT '08:00',
    frequency_cap_days       int  NOT NULL DEFAULT 7,    -- min. odstęp między kampaniami do tego samego klienta
    sms_footer               text,                       -- np. "STOP: odpisz STOP"
    email_footer             text,                       -- stopka + link rezygnacji (wymagany)
    updated_at               timestamptz NOT NULL
);
```

### 3.4 Opt-out klienta

Jedna migracja na `customers`:

```sql
ALTER TABLE customers ADD COLUMN marketing_opt_out_at timestamptz NULL;
```

Ustawiane, gdy: klient odpisze `STOP` na SMS (istniejący webhook `SmsInboundController`
rozszerzamy o rozpoznawanie STOP), kliknie link rezygnacji w e-mailu, albo pracownik ręcznie
oznaczy w karcie klienta. Opt-out jest nadrzędny wobec zgód — działa nawet przy aktywnej zgodzie.

---

## 4. Definicja odbiorców (`audience` JSONB)

Kryteria łączone **spójnikiem I** (wszystkie muszą być spełnione) — celowo bez zagnieżdżonych
OR/AND: badania z użytkownikami CRM pokazują, że złożona algebra filtrów jest główną przyczyną
porzucania kreatorów. Wewnątrz jednego kryterium wielokrotny wybór działa jako LUB
(np. marka = BMW **lub** Audi).

```jsonc
{
  "version": 1,
  "criteria": {
    "visits": {                        // Wizyty
      "countMin": 2, "countMax": null,
      "lastVisitOlderThanDays": 180,   // "nie było go ponad pół roku"
      "lastVisitNewerThanDays": null,
      "lastVisitBetween": null         // {"from": "2026-01-01", "to": "2026-03-31"}
    },
    "revenue": {                       // Przychody (suma final_price_gross z zakończonych wizyt)
      "totalGrossMin": 500000,         // w groszach — UI pokazuje "5 000 zł"
      "totalGrossMax": null
    },
    "services": {                      // Usługi
      "usedAnyOf": ["<service_id>"],   // korzystał z którejkolwiek
      "usedNoneOf": [],                // nigdy nie korzystał (upsell!)
      "lastUsedOlderThanDays": null    // ostatnie wykonanie usługi z usedAnyOf starsze niż X dni
    },
    "vehicles": {                      // Pojazdy (przez vehicle_owners)
      "brands": [{"brand": "BMW", "model": null}],
      "yearMin": null, "yearMax": null,
      "paintTypes": []
    },
    "customer": {                      // Klient
      "type": "ALL",                   // ALL | INDIVIDUAL | COMPANY
      "createdAfter": null             // "nowi klienci od..."
    },
    "manual": {                        // Ręczna korekta listy
      "includeCustomerIds": [],        // dopisani poza filtrami
      "excludeCustomerIds": []         // wykluczeni z wyników filtrów
    }
  }
}
```

**Filtry systemowe — zawsze aktywne, niekonfigurowalne w kreatorze** (widoczne jako
rozbicie w estymacji, patrz §6):

1. `customers.is_active = true` i `marketing_opt_out_at IS NULL`;
2. ważna zgoda marketingowa dla kanału: istnieje `customer_consents` (nieodwołana) na aktywny
   `consent_definition` zawierający dany `marketing_channel`;
3. adres dla kanału istnieje (`phone` / `email`);
4. limit częstotliwości: brak wpisu kampanijnego w `communication_log` z ostatnich
   `frequency_cap_days` dni.

Estymacja i materializacja odbiorców to jedno zapytanie SQL budowane z JSONB — bez ORM-owej
magii; agregaty (liczba wizyt, suma przychodu, data ostatniej wizyty) liczone podzapytaniami
po `visits`/`visit_service_items` z indeksami, które już istnieją (`idx_visits_studio_customer`).

---

## 5. Rodzaje kampanii i cykl życia

### 5.1 ONE_TIME (świąteczna, okazjonalna, do wyselekcjonowanych)

```
DRAFT ──(zatwierdź)──► SCHEDULED ──(scheduled_at)──► SENDING ──► COMPLETED
  │                        │                                        
  └──► CANCELLED ◄─────────┘        (błąd krytyczny → FAILED)
```

- Start wysyłki: silnik **materializuje snapshot** odbiorców (ponownie sprawdzając zgody
  i saldo), rezerwuje kredyty, wysyła partiami (rate-limit SMSAPI), każdy sukces/błąd
  zapisuje w `campaign_recipients` + `communication_log`.
- `scheduled_at` w przeszłości / brak → wysyłka natychmiast po zatwierdzeniu.
- Wysyłka wchodząca w godziny ciszy jest przesuwana na najbliższe okno (per odbiorca).

### 5.2 AUTOMATIC (przypominająca po usłudze — "evergreen")

`trigger_config`:

```jsonc
{
  "event": "SERVICE_COMPLETED",        // baza: visits.pickup_date (fallback actual_completion_date)
  "serviceIds": ["<uuid-powłoki>"],    // po której usłudze
  "afterDays": 180,                    // ile dni po
  "sendTime": "10:00",                 // o której godzinie lokalnie
  "onlyIfNoVisitSince": true           // pomiń, jeśli klient był ponownie w międzyczasie
}
```

```
DRAFT ──(aktywuj)──► ACTIVE ⇄ PAUSED ──► ARCHIVED
```

- Codzienny job (wzorzec istniejącego `ScheduledSmsReminderScheduler`): znajdź wizyty,
  dla których `pickup_date + afterDays` wypada dziś, klient przechodzi filtry `audience`
  i filtry systemowe → wstaw `campaign_recipients(status=PENDING, scheduled_for=dziś sendTime)`.
- Unikalność `(campaign_id, customer_id, channel, trigger_source_visit_id)` gwarantuje,
  że jedna wizyta odpala przypomnienie raz, a kolejna wizyta klienta — znów (o to chodzi).
- `onlyIfNoVisitSince` eliminuje absurd "przypominamy o powłoce", gdy klient był tydzień temu.

### 5.3 Silnik wysyłki (wspólny)

Jeden scheduler co minutę: `SELECT ... FROM campaign_recipients WHERE status='PENDING'
AND scheduled_for <= now() FOR UPDATE SKIP LOCKED LIMIT batch`. Dla każdego wiersza:
re-walidacja (opt-out mógł przyjść po zaplanowaniu), pobranie kredytu (SMS), wysyłka przez
istniejący `SmsApiProvider`/`EmailProvider`, wpis do `communication_log`, aktualizacja statusu
i liczników kampanii. Retry dla błędów przejściowych (3 próby, backoff), potem `FAILED`.
Gdy saldo = 0 → pozostali odbiorcy `SKIPPED_NO_CREDITS`, kampania kończy się z ostrzeżeniem
widocznym na dashboardzie ("Doładuj kredyty i wznów").

---

## 6. Treść wiadomości

**Placeholdery** (wspólne dla SMS i e-mail, podstawiane per odbiorca):

| Placeholder | Źródło |
|---|---|
| `{{imie}}`, `{{nazwisko}}` | `customers` |
| `{{studio}}`, `{{telefon_studia}}`, `{{www}}` | `studio_settings` |
| `{{marka}}`, `{{model}}` | pojazd z ostatniej wizyty (fallback: jedyny pojazd klienta) |
| `{{ostatnia_usluga}}`, `{{data_ostatniej_wizyty}}` | `visits` + `visit_service_items` |
| `{{dni_od_wizyty}}` | wyliczane |

**SMS:** licznik na żywo — znaki, wykrycie polskich znaków (GSM-7 160/153 vs UCS-2 70/67),
liczba segmentów i **koszt kampanii = odbiorcy × segmenty** (podstawa: przypadek najdłuższego
podstawienia placeholderów). Stopka `campaign_settings.sms_footer` doliczana do limitu.
Przycisk "Wyślij test na mój numer".

**E-mail:** temat + treść w prostym edytorze (pogrubienie, listy, link, logo studia z
`studio_settings.logo_s3_key`) — świadomie **bez** drag&drop buildera HTML w v1. Stopka
z danymi studia i obowiązkowym linkiem rezygnacji doklejana automatycznie. "Wyślij test".

---

## 7. UX — ekrany

### 7.1 Dashboard modułu (landing)

- **4 kafle KPI:** Aktywne (AUTOMATIC ACTIVE + ONE_TIME SENDING) · Zaplanowane (SCHEDULED) ·
  Wysłane łącznie (COMPLETED, z liczbą wiadomości) · **Saldo kredytów SMS** (z linkiem do doładowania).
- Lista kampanii: nazwa, typ (ikona: 🕐 jednorazowa / 🔁 automatyczna), kanał, status (badge),
  odbiorcy (wysłane/wszyscy), koszt, data. Filtrowanie po statusie, sortowanie po dacie.
- Wiersz rozwija podgląd: treść, rozbicie statusów odbiorców, przycisk "Duplikuj" (najczęstsza
  ścieżka tworzenia kolejnej kampanii!), dla AUTOMATIC: Pauza/Wznów.
- CTA: **"+ Nowa kampania"**.

### 7.2 Kreator — 4 kroki

**Krok 1 — Scenariusz.** Karty do wyboru:

| Scenariusz (ikona Lucide) | Prefill |
|---|---|
| Świąteczna / okazjonalna (`gift`) | ONE_TIME, wszyscy ze zgodą, szablon życzeń, planowanie daty |
| Przypomnienie po usłudze (`repeat`) | AUTOMATIC, wybór usługi + suwak dni (90/180/365), szablon "czas na odświeżenie" |
| Reaktywacja (`moon`) | ONE_TIME, `lastVisitOlderThanDays: 180`, szablon "dawno Cię nie było" |
| Właściciele wybranych aut (firmowa sylwetka coupé) | ONE_TIME, filtr marka/model otwarty na starcie |
| Klienci VIP (`gem`) | ONE_TIME, `revenue.totalGrossMin` + `visits.countMin` |
| Własna (`settings-2`) | wszystko puste |

**Krok 2 — Odbiorcy.** Dwie kolumny:
- Lewa: filtry jako składane sekcje (Wizyty / Przychody / Usługi / Pojazdy / Klient) —
  domyślnie zwinięte, aktywne oznaczone chipem; wartości w ludzkim języku
  ("Ostatnia wizyta: ponad **180 dni** temu").
- Prawa, przyklejona: **licznik na żywo** — duża liczba "**117** odbiorców" + rozbicie:
  *134 pasuje do filtrów · −9 brak zgody SMS · −5 brak numeru · −3 limit wysyłek* —
  oraz przewijalna lista odbiorców (imię, telefon/e-mail, auto, ostatnia wizyta) z ×
  do ręcznego wykluczenia i wyszukiwarką "dopisz klienta".

**Krok 3 — Treść.** Wybór kanału (SMS / E-mail / Oba — pokazujemy ilu odbiorców ma który
adres), edytor z placeholderami wstawianymi kliknięciem, podgląd na przykładowym prawdziwym
odbiorcy, licznik segmentów i **koszt w kredytach**, przycisk testu.

**Krok 4 — Podsumowanie.** Karta-recap (kto/co/ile kosztuje) + wybór:
"Wyślij teraz" / "Zaplanuj na [data, godzina]" / (AUTOMATIC) "Aktywuj".
Walidacje blokujące: saldo < koszt (link do doładowania), brak `sms_sender_name`
(link do konfiguracji), pusta grupa odbiorców.

### 7.3 Szczegóły kampanii

Nagłówek ze statusem i licznikami (donut wysłane/pominięte/błędy), pełna lista odbiorców
ze statusem i powodem pominięcia, treść, koszt, oś czasu (utworzono → zaplanowano → wysłano).
Akcje wg statusu: edytuj (DRAFT), anuluj (SCHEDULED), pauza/wznów/archiwizuj (AUTOMATIC),
duplikuj (zawsze).

### 7.4 Ustawienia kampanii (zakładka)

Godziny ciszy, limit częstotliwości, stopki SMS/e-mail, nazwa nadawcy SMS
(przeniesiona z obecnego miejsca — jedna sekcja "wysyłka").

---

## 8. API (REST, `/v1/campaigns`)

```
GET    /v1/campaigns?status=&kind=&page=          lista + liczniki
GET    /v1/campaigns/stats                        kafle KPI dashboardu
POST   /v1/campaigns                              utworzenie (DRAFT)
GET    /v1/campaigns/{id}
PUT    /v1/campaigns/{id}                         edycja (tylko DRAFT)
DELETE /v1/campaigns/{id}                         tylko DRAFT
POST   /v1/campaigns/{id}/schedule                DRAFT → SCHEDULED  {scheduledAt?}
POST   /v1/campaigns/{id}/cancel                  SCHEDULED → CANCELLED
POST   /v1/campaigns/{id}/activate|pause|archive  AUTOMATIC
POST   /v1/campaigns/{id}/duplicate               → nowy DRAFT
GET    /v1/campaigns/{id}/recipients?status=
POST   /v1/campaigns/audience/estimate            {audience, channel} → {matched, excludedBreakdown, finalCount, sample[], estimatedCredits}
POST   /v1/campaigns/test-send                    {channel, address, template...}
GET/PUT /v1/campaigns/settings
```

Backend: nowy pakiet `pl.detailing.crm.campaigns` w istniejącej konwencji
(domain / application / infrastructure / api), scheduler w `application`.
Frontend: nowy moduł `src/modules/campaigns` (dashboard, wizard, details, settings);
`sms-campaigns` i `email-campaigns` do usunięcia po migracji.

---

## 9. Zakres wdrożenia

**Faza 1 (MVP):** tabele + migracje, ONE_TIME end-to-end (kreator, estymacja, wysyłka SMS,
planowanie, dashboard, szczegóły), filtry systemowe, STOP/opt-out, koszt i kredyty.
**Faza 2:** kanał e-mail (+ link rezygnacji), kampanie AUTOMATIC, scenariusze-szablony, duplikowanie.
**Faza 3:** raport skuteczności (wizyty w ciągu 30 dni od wysyłki — mamy `communication_log`
i `visits`, atrybucja "po fakcie" bez dodatkowych danych), test A/B treści.

**Świadomie poza zakresem v1:** builder HTML e-maili, zagnieżdżona logika OR w filtrach,
statusy doręczenia SMS (delivery reports SMSAPI — do rozważenia w F3), kampanie MMS.

---

## 10. Ryzyka i decyzje do potwierdzenia

1. **Zgody historyczne** — część baz klientów może nie mieć podpisanych zgód marketingowych;
   dashboard powinien komunikować "X klientów bez zgody" z linkiem do modułu zgód, inaczej
   pierwsza estymacja ("z 800 klientów wyślemy do 40") będzie szokiem. Do omówienia z PM.
2. **Definicja przychodu klienta** — przyjęto sumę `final_price_gross` pozycji potwierdzonych
   z wizyt zakończonych. Potwierdzić z księgowością/PM.
3. **`service_name` vs `service_id`** — pozycje wizyt mają snapshot nazwy i nullable
   `service_id`; filtr usług działa po `service_id`, z dopasowaniem po nazwie jako fallback
   dla pozycji ręcznych. Do weryfikacji na danych produkcyjnych.
4. **Frequency cap a automaty transakcyjne** — limit dotyczy wyłącznie kampanii
   (wiadomości z `communication_log` o typie kampanijnym), nie przypomnień o wizycie.
