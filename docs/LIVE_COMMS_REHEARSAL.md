# Próba generalna komunikacji automatycznej — strategia testu „na żywo"

Dokument dla founderów DetailBoost. Opisuje, jak przed wdrożeniem produkcyjnym
modułu szablonów (`/settings?tab=templates`) wysłać **każdą** wiadomość, jaką
system potrafi wygenerować, przez **prawdziwych dostawców** (SMSAPI, SMTP home.pl)
na **nasze** telefony i skrzynki — bez ryzyka, że cokolwiek trafi do realnego klienta,
i bez ryzyka, że mechanizm testowy zostanie w produkcji.

Wszystkie odwołania do kodu dotyczą stanu gałęzi `claude/detailboost-production-test-strategy-9caef5`.

---

## 0. Stan faktyczny — co już jest w kodzie i co trzeba naprawić PRZED testem

Zanim zbudujemy cokolwiek nowego, trzy rzeczy w obecnym kodzie są dokładnie tym
„wyciekiem trybu testowego na produkcję", którego się obawiacie. One już tam są.

| # | Gdzie | Co | Skutek na produkcji |
|---|-------|----|---------------------|
| 1 | `src/main/kotlin/pl/detailing/crm/email/provider/javamail/JavaMailProvider.kt:34-48` | Zahardkodowana w kodzie lista 11 adresów `allowedMails`. Każdy inny adres dostaje `failure("Email celowo zablokowany. Faza testowa…")`. | **Żaden klient nie dostanie żadnego maila.** Błąd trafia tylko do logu i `communication_log`. |
| 2 | `src/main/resources/application.properties:139` | `smsapi.whitelist=+48888915358,…` w **domyślnym** pliku properties, nie w env. Ostatni wpis ma literalne cudzysłowy, więc i tak nie działa. | **Żaden klient nie dostanie SMS-a.** `SmsApiProvider.kt:59-62` zwraca `failure("Numer został celowo zablokowany. Faza testowa.")`. |
| 3 | `application.properties:165` | `email.javamail.password=${MAIL_PASSWORD:haslo123!}` — hasło SMTP jako domyślna wartość w repo. | Sekret w historii gita. Do rotacji. |

Plus dwie rzeczy, które nie są blokadami, ale wyjdą w teście, więc lepiej wiedzieć:

- `JavaMailProvider.kt:88,93` wysyła treść jako `text/plain`. Szablony e-mail w UI są
  edytowane w zwykłym `<textarea>` (`ChannelEditor.tsx:298-306`), więc to spójne — ale
  jeśli ktoś wklei HTML do szablonu, klient zobaczy surowe tagi. Walidator w pkt 3 to łapie.
- `JavaMailProvider.kt:70` — nadawca to na sztywno `kontakt@sienkiewicz-maciej.pl`, komentarz
  w klasie mówi o `automat@detailboost.pl`. SPF/DKIM domeny nadawcy zdecyduje, czy maile
  wylądują w spamie. Sprawdzamy to w sign-off (pkt 4).

**Decyzja:** obie listy (1 i 2) usuwamy i zastępujemy jednym, jawnym mechanizmem z pkt 1.
Nie da się „upewnić na 100%, że mechanizm nie wycieknie", dopóki istnieją dwa inne,
które już wyciekły.

---

## 1. Przekierowanie na dane studia — funkcja produktu, nie tryb z env

> Wersja pierwotna tego rozdziału opisywała tryb włączany zmiennymi środowiskowymi.
> Zastąpiła go decyzja biznesowa: **każde studio** ma w `/settings?tab=templates`
> przełącznik „Przekieruj każdą wiadomość mailową i SMS na moje dane" z dwoma polami.
> Studio włącza go na czas oglądania prawdziwych wiadomości na własnym telefonie
> (dzień, tydzień — ile trzeba), a wyłącza, gdy chce, żeby klienci zaczęli je dostawać.
> Poniżej stan **zaimplementowany** na tej gałęzi.

### 1.1. Gdzie to żyje

| Warstwa | Plik | Rola |
|---------|------|------|
| Tabela | `db/migration/V100__communication_redirect_settings.sql` | `communication_redirect_settings`: jeden wiersz na studio (`enabled`, `phone`, `email`, `updated_at`, `updated_by_user_id`). Brak wiersza = wysyłka do klientów. |
| Serwis | `communication/redirect/CommunicationRedirectService.kt` | `settings`, `update` (walidacja: włączenie wymaga **obu** danych; telefon do E.164, e-mail sprawdzony i obniżony do małych liter), `activeFor(studioId)` — `null`, gdy nie przekierowujemy. |
| API | `communication/redirect/CommunicationRedirectController.kt` | `GET/PUT /api/v1/communication/redirect`, uprawnienie `COMMUNICATION_SEND`, moduł `COMM_SEND_TRANSACTIONAL`. Pola `phone`/`email` oznaczone `@Pii`. |
| Bramka | `communication/OutboundCommunicationGateway.kt` | **Jedyne miejsce podmiany.** `redirected()` tuż przed `provider.send`: SMS dostaje prefiks `[TEST → +48…] ` w treści, e-mail w **temacie** (treść musi być dokładnie tym, co zobaczyłby klient). Licznik `communication.redirected{channel}` i log INFO per wysyłka. |
| UI | `detailing-crm-v2/src/modules/message-templates/components/RedirectCard.tsx` | Karta nad listą szablonów: przełącznik, dwa pola, „Sprawdź szablony", „Wyślij wszystkie testowo". Włączona = bursztyn i zdanie wprost: *Klienci nie dostają teraz żadnych wiadomości.* |

### 1.2. Dlaczego bramka, i co z tym zrobiliśmy

Podmiana odbiorcy jest bezpieczna tylko wtedy, gdy **każda** wiadomość do klienta przechodzi
przez bramkę. Przed tą zmianą cztery ścieżki wołały providerów bezpośrednio, więc ominęłyby
przekierowanie (i przy okazji kredyty SMS):

| Ścieżka | Było | Jest |
|---------|------|------|
| `campaigns/api/CampaignController.testSend` (e-mail) | `emailProvider.send` | `gateway.sendTransactionalEmail(category = CAMPAIGN)` |
| `batchorder/report/CloseMonthHandler` (zestawienie B2B) | `emailProvider.send` | `gateway.sendTransactionalEmail` |
| `smscampaigns/consent/SmsConsentService` (2 SMS-y o zmianie usług) | `smsProvider.send` | `gateway.sendTransactionalSms` (brak kredytów = wynik `failure`, nie wyjątek) |
| `visitcard/upsell/RequestUpsellServicesHandler` | `smsProvider.send` | `gateway.sendTransactionalSms` |

Bramka dostała nową metodę `sendTransactionalEmail(studioId, to, subject, body, attachments, category)`
dla maili bez rekordu klienta (kontrahent, test do siebie).

Bezpośrednio z providerów korzystają już **tylko** maile do personelu i do DetailBoost:
reset hasła, zaproszenie pracownika, zgłoszenie problemu, powiadomienie o upoważnieniu SMS.
To celowo — to nie jest komunikacja z klientem i nie podlega przekierowaniu.

### 1.3. Fail-closed

- Włączenie bez telefonu lub bez e-maila → `ValidationException` przy zapisie, nie przy wysyłce.
- Wiersz `enabled=true` z pustym polem (np. po ręcznej edycji bazy) → `activeFor` zwraca `null`:
  nie przekierowuje i **nie blokuje**. Nie ma stanu, w którym wiadomość nie wychodzi nigdzie.
- Przekierowanie jest per studio. Inne studia w tej samej bazie nic nie zauważą
  (test `redirect is looked up per studio`).
- Wyłączenie przełącznika zostawia wpisane dane, ale od tej chwili klienci dostają wiadomości.

### 1.4. Usunięte blokady z fazy testów

- `JavaMailProvider.allowedMails` — skasowane; przy `enabled=false` provider zwraca `success("mock-disabled")`
  tak jak provider SMS, zamiast udawać błąd.
- `smsapi.whitelist` — skasowane z `SmsApiProperties`, `SmsApiProvider` i `application.properties`.
- `email.javamail.password` — bez wartości domyślnej; tylko `MAIL_PASSWORD` z env. **Stare hasło do rotacji.**
- Strażnik: `NoHardcodedRecipientAllowListTest` nie pozwoli żadnej z tych rzeczy wrócić
  (skanuje providerów i pliki properties po `allowedMails`, `smsapi.whitelist`, „celowo zablokowany", „Faza testowa"
  i sprawdza, że hasło SMTP nie ma domyślnej wartości).

### 1.5. Co przestaje być problemem, a co zostaje

Pytanie „jak upewnić się, że tryb nie wycieknie na produkcję" zmienia sens: przekierowanie
**ma** być na produkcji, bo to funkcja dla studiów. Ryzykiem nie jest wyciek, tylko
**zapomniany przełącznik** — studio, które włączyło przekierowanie i nie pamięta, że klienci
nic nie dostają. Zabezpieczenia:

1. Karta w stanie włączonym jest bursztynowa i mówi wprost, że klienci nic nie dostają.
2. Każda przekierowana wiadomość ma prefiks `[TEST → …]`, więc odbiorca widzi, że to nie do niego.
3. Log WARN przy włączeniu, INFO przy każdej wysyłce, metryka `communication.redirected` do alertu
   „studio X ma przekierowanie włączone od > 7 dni".
4. Do rozważenia (nie zaimplementowane): baner na dashboardzie, gdy przekierowanie jest włączone
   dłużej niż 24 h, i automatyczny e-mail przypominający po tygodniu.

## 2. Runner — automatyczne wymuszenie każdej wiadomości w systemie

### 2.1. Zakres: co znaczy „wszystkie szablony"

Szablony nie są tabelą, tylko kolumnami dwóch rekordów per studio
(`sms_automation_configs`, `email_automation_configs`). Pełna lista rodzajów to enum
`MessageTemplateKind` (`communication/template/MessageTemplateKind.kt:26-51`):

| Kanał | Rodzaj | Placeholdery |
|-------|--------|--------------|
| SMS | SMS_PRE_VISIT, SMS_POST_VISIT, SMS_DELAYED_REMINDER, SMS_BOOKING_CONFIRMATION, SMS_RESCHEDULE_CONFIRMATION | imie, nazwisko, data, godzina |
| SMS | SMS_VISIT_READY_FOR_PICKUP | + pojazd, rejestracja, numer_wizyty |
| SMS | SMS_VISIT_CARD_LINK | + data, godzina, link |
| SMS | SMS_RESERVATION_CARD_LINK | imie, nazwisko, data, godzina, link |
| SMS | SMS_UPSELL_CONSENT | imie, nazwisko, uslugi, kwota |
| SMS | SMS_SIGNATURE_REQUEST | imie, nazwisko, link, dokument |
| E-mail | EMAIL_VISIT_WELCOME, EMAIL_VISIT_READY_FOR_PICKUP, EMAIL_VISIT_CARD_LINK | imie, nazwisko, imie_nazwisko, pojazd, rejestracja, numer_wizyty, data, godzina (+ link) |
| E-mail | EMAIL_RESERVATION_CARD_LINK | imie, nazwisko, imie_nazwisko, data, godzina, link |
| E-mail | EMAIL_BATCH_ORDER_CLOSE | kontrahent, okres, kwota_brutto, liczba_wpisow |
| Kampanie | CAMPAIGN | imie, nazwisko, marka, model, ostatnia_usluga, data_ostatniej_wizyty, dni_od_wizyty |

Razem: **10 SMS + 5 e-mail + 1 kampania = 16 wiadomości.** Do tego cztery wiadomości
stałe w kodzie, spoza szablonów (`MessageTemplateKind.kt:20-24`): dwa SMS-y zgody na
zmianę usług, reset hasła, zaproszenie pracownika. Te też wysyłamy w Tier 2.

Runner musi być **związany z enumem**, nie z ręczną listą: iteruje po
`MessageTemplateKind.entries`. Jeśli ktoś doda nowy rodzaj, runner go podniesie
automatycznie, a test fixture'a (2.3) zmusi do dopisania danych.

### 2.2. Dwa poziomy testu

**Tier 1 — renderer-level (deterministyczny, 16 wiadomości, ~2 min).**
Bierze szablony **z bazy studia testowego** (dokładnie te, które wpiszecie w
`/settings?tab=templates`), renderuje je przez ten sam `MessageTemplateRenderer`,
waliduje (pkt 3) i wysyła przez `OutboundCommunicationGateway`. Testuje: treść szablonów,
parser, walidację, providerów, dostarczalność.

**Tier 2 — end-to-end przez prawdziwe handlery (co system zrobi sam).**
Nie renderuje nic ręcznie — wykonuje zdarzenia biznesowe i patrzy, co system wyśle:

| Krok | Wywołanie | Oczekiwane wiadomości |
|------|-----------|------------------------|
| Utwórz rezerwację na jutro 10:00 | `AppointmentController` (`:55`) | SMS_BOOKING_CONFIRMATION |
| Wyślij kartę rezerwacji | `SendReservationCardLinkHandler` | SMS + EMAIL_RESERVATION_CARD_LINK |
| Przełóż na jutro 11:00 | `AppointmentController` (`:56`) | SMS_RESCHEDULE_CONFIRMATION |
| Ustaw `pre_visit_offset_minutes` tak, by scheduler odpalił w ≤ 2 min | `SmsAutomationScheduler` (`:78`, cron co minutę) | SMS_PRE_VISIT |
| Przyjmij auto (start wizyty) | `VisitController` (`:71`) | EMAIL_VISIT_WELCOME |
| Wyślij kartę wizyty | `SendVisitCardLinkHandler` | SMS + EMAIL_VISIT_CARD_LINK |
| Zamów usługi z karty | `RequestUpsellServicesHandler` (`:369`) | SMS_UPSELL_CONSENT; odpowiedź „TAK" z telefonu → `SmsInboundController` |
| Wyślij dokument do podpisu | `RequestSignatureHandler` (`:230`) | SMS_SIGNATURE_REQUEST |
| Oznacz gotowość do odbioru | `MarkVisitReadyForPickupHandler` (`:62,73`) | SMS + EMAIL_VISIT_READY_FOR_PICKUP |
| Wydaj auto, `post_visit_offset` = 1 min, `delayed_reminder_offset` = 2 min | scheduler | SMS_POST_VISIT, SMS_DELAYED_REMINDER |
| Zamknij miesiąc B2B | `CloseMonthHandler` (`:65`) | EMAIL_BATCH_ORDER_CLOSE |
| Kampania testowa | `POST /v1/campaigns/test-send` | CAMPAIGN (SMS + e-mail) |

Tier 2 testuje to, czego Tier 1 nie widzi: że handler w ogóle jest wołany, dedup w
`sms_logs`, offsety, kredyty SMS, zgody, sender name, webhook zwrotny.

### 2.3. Fixture — dane branżowe

```kotlin
// communication/rehearsal/RehearsalFixture.kt
object RehearsalFixture {
    val tomorrowTen: Instant = LocalDate.now(WARSAW).plusDays(1).atTime(10, 0).atZone(WARSAW).toInstant()

    fun values(kind: MessageTemplateKind, seq: Int, cardUrl: String): Map<String, String> {
        val all = mapOf(
            "imie" to "Jan", "nazwisko" to "Kowalski", "imie_nazwisko" to "Jan Kowalski",
            "pojazd" to "Audi RS6 Avant", "rejestracja" to "WE 4RS6X",
            "numer_wizyty" to "WIZ/2026/09/%03d".format(seq),
            "link" to cardUrl,
            "uslugi" to "Powłoka ceramiczna 9H, korekta lakieru 2-etapowa",
            "kwota" to "4 900,00 zł",
            "dokument" to "Protokół przyjęcia pojazdu",
            "kontrahent" to "Flota Premium Sp. z o.o.", "okres" to "sierpień 2026",
            "kwota_brutto" to "18 450,00 zł", "liczba_wpisow" to "7",
            "marka" to "Audi", "model" to "RS6 Avant", "ostatnia_usluga" to "Powłoka ceramiczna",
            "data_ostatniej_wizyty" to "12.03.2026", "dni_od_wizyty" to "175",
        ) + MessageTemplateRenderer.scheduleValues(tomorrowTen)
        return all.filterKeys { it in kind.allowedPlaceholders }
    }
}
```

Test jednostkowy, który pilnuje kompletności (odpala się w zwykłym `./gradlew test`):

```kotlin
@Test fun `fixture covers every placeholder of every kind`() {
    MessageTemplateKind.entries.forEach { kind ->
        val missing = kind.allowedPlaceholders - RehearsalFixture.values(kind, 1, "https://x").keys
        assertThat(missing).describedAs(kind.name).isEmpty()
    }
}
```

Ważne szczegóły fixture'a, celowo dobrane pod pułapki:
- `pojazd` z nazwą modelu i spacją, `rejestracja` ze spacją — łapie łamanie linii w SMS.
- `kwota` z twardą spacją i przecinkiem — łapie kodowanie GSM-7 vs UCS-2 (`SmsSegmentCalculator`).
- `link` **musi być prawdziwym URL-em karty** z bazy testowej — walidator go otworzy (pkt 3).
- Data jutro 10:00 w `Europe/Warsaw` — sprawdza formatowanie `dd.MM.yyyy` i strefę
  (na serwerze z UTC różnica godziny wyjdzie natychmiast).
- `imie` bez polskich znaków, ale `uslugi` z „ł" i „ą" — ECO wymusza `setNormalize(true)`
  (`SmsApiProvider.kt:73`), więc na telefonie zobaczycie „Powloka ceramiczna". Oceńcie,
  czy to akceptowalne dla studiów bez potwierdzonego nadawcy.

### 2.4. Endpoint / uruchomienie

Nie CLI (aplikacja to jeden monolit w Dockerze — nie ma jak wpiąć się z boku w
bramkę i kredyty) tylko endpoint, który wysyła **wyłącznie przy włączonym przekierowaniu**
danego studia. Zaimplementowane: `communication/rehearsal/CommsRehearsalController.kt`
(`POST /api/v1/communication/rehearsal/plan`, `POST …/run`) oraz `CommsRehearsalRunner.kt`,
`RehearsalFixture.kt`, `RenderedMessageValidator.kt`. Poniższy szkic zachowany dla kontekstu;
`journey` (Tier 2) **nie jest** zaimplementowany — Tier 2 wykonuje się ręcznie z UI wg pkt 4.4.

```kotlin
@RestController
@RequestMapping("/api/v1/internal/comms-rehearsal")
@ConditionalOnProperty("comms.live-override.enabled", havingValue = "true")
class CommsRehearsalController(private val runner: CommsRehearsalRunner) {

    @PostMapping("/plan")   // Tier 1 dry-run: renderuje + waliduje, NIE wysyła, zwraca raport
    fun plan(@RequestParam studioId: UUID): RehearsalReport = runner.plan(studioId)

    @PostMapping("/run")    // Tier 1: wysyła TYLKO, jeśli plan przeszedł bez błędu
    fun run(@RequestParam studioId: UUID, @RequestHeader("X-Rehearsal-Token") token: String): RehearsalReport

    @PostMapping("/journey") // Tier 2: przeprowadza Jana Kowalskiego przez całą wizytę
    fun journey(@RequestParam studioId: UUID, @RequestHeader("X-Rehearsal-Token") token: String): RehearsalReport
}
```

`@ConditionalOnProperty` oznacza, że na produkcji bez override **bean nie istnieje** —
endpoint zwraca 404. Dodatkowo `@RequiresPermission(COMMUNICATION_SEND)` i token
z env (`COMMS_LIVE_OVERRIDE_TOKEN`), żeby nikt z uprawnieniami studia nie odpalił go sam.

Runner, w skrócie:

```kotlin
fun run(studioId: UUID): RehearsalReport {
    val report = plan(studioId)                         // 1. render + walidacja wszystkiego
    if (report.hasErrors) return report                 // 2. all-or-nothing: ani jeden SMS nie wychodzi
    report.items.forEach { item ->                      // 3. dopiero teraz wysyłka
        val result = when (item.channel) {
            SMS   -> gateway.sendTransactionalSms(studioId, fixtureCustomer.phone, item.body, context = "REHEARSAL:${item.kind}")
            EMAIL -> gateway.sendEmail(fixtureCustomer.id, studioId, fixtureCustomer.email, item.subject!!, item.body, context = "REHEARSAL:${item.kind}")
        }
        item.delivery = result; Thread.sleep(1500)      // 4. odstęp — SMS-y przychodzą w kolejności
    }
    return report
}
```

Każda wiadomość dostaje numer porządkowy na początku treści, np. `[R07/16] `,
osobno dla SMS i e-mail. To jest to, po czym w pkt 4 odhaczacie odbiór na telefonie.

`RehearsalReport` to JSON: lista `{seq, kind, channel, subject, body, segments, validation:[…], delivery:{ok, providerId, error}}`
plus zapis do pliku `rehearsal-<studio>-<timestamp>.json` w wolumenie — dowód do sign-offu.

### 2.5. Studio testowe

Osobne studio „DetailBoost — Próba generalna" w **produkcyjnej bazie**, z:
- wykupionym modułem komunikacji (`CommunicationOnboardingService`), inaczej bramka zablokuje
  na `COMM_SEND_TRANSACTIONAL`;
- pakietem kredytów SMS (koszt testu: ~12 SMS × 2 osoby × 2 przebiegi ≈ 50 SMS);
- potwierdzonym `sms_sender_name` (jeśli chcecie zobaczyć nagłówek, a nie numer ECO)
  — a najlepiej **dwoma przebiegami**: z nagłówkiem i bez, bo klienci będą mieli oba;
- jednym klientem: Jan Kowalski, telefon i e-mail = founder A; drugim: Anna Nowak = founder B.

`COMMS_LIVE_OVERRIDE_STUDIO_IDS` = UUID tego studia. Realne studia w tej samej bazie
działają normalnie. To jest powód, dla którego override musi być per-studio, a nie globalny.

---

## 3. Walidacja przed wysyłką

### 3.1. Co już jest

`MessageTemplateRenderer.render` (`MessageTemplateRenderer.kt:32-47`) rzuca
`UnresolvedPlaceholderException` dla każdego `{{x}}`, którego nie ma w mapie wartości,
a `MessageTemplateKind.validate` (`:66-75`) odrzuca zapis szablonu z nieznaną zmienną.
Literówka `{{clinet_name}}` zostanie więc złapana już przy zapisie w UI.

### 3.2. Czego to NIE łapie — i co łapie walidator runnera

Regex `\{\{\s*([a-zA-Z0-9_]+)\s*}}` widzi tylko poprawnie sformowane tokeny ASCII.
Przechodzą bez błędu, a klient je zobaczy:

| Wpis w szablonie | Dlaczego przechodzi |
|------------------|---------------------|
| `{{imię}}` | „ę" nie pasuje do `[a-zA-Z0-9_]` — to nie jest token, to zwykły tekst |
| `{imie}` / `{{imie}` / `{{imie}}}` | niepełne klamry |
| `{{imie-nazwisko}}`, `{{imie nazwisko}}` | myślnik/spacja w środku |
| `{{ imie }}}` | nadmiarowa klamra zostaje w tekście |
| `<b>Jan</b>` w mailu | wysyłka jako text/plain — klient zobaczy tagi |

`RenderedMessageValidator` — uruchamiany na **wyniku** renderowania, przed wysyłką,
dla każdej wiadomości. Każda reguła to osobny `Finding(severity, rule, snippet)`:

```kotlin
object RenderedMessageValidator {
    private val ORPHAN_BRACES = Regex("""\{\{|}}|\{[^{}\n]{1,40}}|\{\{[^}]*$""")
    private val TEMPLATE_LEFTOVERS = Regex("""\$\{|%s|%d|\[\[|]]|\bnull\b|\bundefined\b|\bNaN\b""", IGNORE_CASE)
    private val HTML_TAG = Regex("""</?[a-z][a-z0-9]*[^>]*>""", IGNORE_CASE)
    private val DIACRITIC_PLACEHOLDER = Regex("""\{\{\s*[^}]*[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ][^}]*}}""")

    fun validate(m: RenderedMessage, expected: Map<String, String>): List<Finding> = buildList {
        // A. Osierocone / zniekształcone zmienne — twarde STOP
        ORPHAN_BRACES.findAll(m.text).forEach { add(error("orphan-braces", it.value)) }
        DIACRITIC_PLACEHOLDER.findAll(m.text).forEach { add(error("placeholder-with-diacritics", it.value)) }
        TEMPLATE_LEFTOVERS.findAll(m.text).forEach { add(error("template-leftover", it.value)) }

        // B. Każda wartość, którą podstawiliśmy, MUSI być widoczna w wyniku
        expected.filterValues { it.isNotBlank() }.forEach { (k, v) ->
            if (v !in m.text) add(error("value-missing", "{{$k}} = $v nie występuje w treści"))
        }
        // C. Wartości, które nigdy nie mogą być puste
        setOf("imie", "data", "godzina", "link").intersect(expected.keys)
            .filter { expected[it].isNullOrBlank() }.forEach { add(error("required-empty", it)) }

        // D. Link: absolutny https, żywy (GET 200), bez spacji
        expected["link"]?.let { url ->
            if (!url.startsWith("https://") || ' ' in url) add(error("link-format", url))
            else if (http.head(url).status != 200) add(error("link-dead", url))
        }

        // E. Data: to naprawdę jutro w Europe/Warsaw, z rokiem
        if ("data" in expected && !m.text.contains(Regex("""\b\d{2}\.\d{2}\.\d{4}\b"""))) add(error("date-without-year", ""))

        // F. Kanałowe
        when (m.channel) {
            SMS -> {
                val seg = SmsSegmentCalculator.segments(m.text)
                if (seg > 3) add(error("sms-too-long", "$seg segmentów"))
                else if (seg > 2) add(warn("sms-long", "$seg segmentów"))
                if (m.text != m.text.trim() || "  " in m.text) add(warn("whitespace", ""))
                if (HTML_TAG.containsMatchIn(m.text)) add(error("html-in-sms", ""))
            }
            EMAIL -> {
                val s = m.subject!!
                if (s.isBlank() || '\n' in s || '\r' in s) add(error("subject-invalid", s))
                if (s.length > 78) add(warn("subject-long", "${s.length} znaków"))
                if (ORPHAN_BRACES.containsMatchIn(s)) add(error("orphan-braces-subject", s))
                if (HTML_TAG.containsMatchIn(m.text)) add(error("html-in-plaintext-email", "provider wysyła text/plain"))
                if (m.text.length < 40) add(warn("body-suspiciously-short", ""))
            }
        }
    }
}
```

Reguła B jest najcenniejsza i najrzadziej spotykana: nie pytamy „czy zostały klamry",
tylko „czy Audi RS6 i 10:00 naprawdę są w tekście". Łapie sytuację, w której szablon
w ogóle nie używa zmiennej, która powinna tam być (np. przypomnienie bez godziny).

### 3.3. Polityka: all-or-nothing

`plan()` waliduje wszystkie 16 wiadomości. **Jeden `error` w dowolnej = zero wysyłek.**
Raport wraca z listą `Finding`ów, poprawiacie szablon w UI, odpalacie `plan` ponownie.
`warn` nie blokuje, ale musi być świadomie zaakceptowany w sign-offie (pkt 4.4).

### 3.4. Trwałe uszczelnienie (poza testem)

Trzy zmiany w kodzie produkcyjnym, żeby to, co łapie runner, było łapane też przy
zapisie w `/settings?tab=templates`:

1. `MessageTemplateKind.validate` dodatkowo: po usunięciu poprawnych tokenów, jeśli w
   szablonie zostaje `{{`, `}}` lub `{slowo}` — odrzuć z komunikatem „niedomknięta zmienna".
2. Odrzucaj `{{…}}` z polskimi znakami z osobnym komunikatem („użyj `{{imie}}`, nie `{{imię}}`").
3. Dla e-maila: odrzucaj tagi HTML, dopóki provider wysyła text/plain.

Podgląd w UI (`ChannelEditor.tsx:325-337`) używa własnego regexa
(`utils/template.ts:44`) i własnych danych przykładowych. Warto, żeby frontend zaczął
używać endpointu `POST /plan` z tymi samymi regułami — jeden walidator zamiast dwóch.

---

## 4. Procedura sign-off — dzień testu

### 4.1. Role

- **Driver** — siedzi przy terminalu, odpala endpointy, czyta raporty JSON.
- **Observer** — ma tylko telefon i skrzynkę. Nie patrzy w logi. Ocenia to, co widzi
  klient. Nic więcej.

Zamieniacie się rolami między przebiegiem 1 i 2. Każdy z was zobaczy obie perspektywy.

### 4.2. D-1 — przygotowanie (Driver, ~1 h)

- [ ] Wdrożony kod z pkt 1–3; `./gradlew test` zielony, w tym `LiveOverrideLeakGuardTest`
      i test kompletności fixture'a.
- [ ] Zrotowane hasło SMTP (pkt 0, poz. 3). Nowe tylko w `.env` hosta.
- [ ] Studio testowe utworzone, moduł komunikacji aktywny, kredyty SMS ≥ 100.
- [ ] Dwaj klienci testowi z Waszymi numerami/adresami. Jeden z nich z adresem
      **Gmail**, drugi z **Outlook/o2/WP** — różne filtry antyspamowe.
- [ ] Wszystkie 16 szablonów wpisane w `/settings?tab=templates` **w wersji, która ma iść na produkcję** —
      to jest test tych tekstów, nie starterów.
- [ ] Wszystkie reguły włączone (`enabled = true`); offsety ustawione na wartości testowe
      (pre_visit tak, by wypadł ≤ 2 min po utworzeniu rezerwacji; post_visit 1; delayed 2).
- [ ] `POST /plan` → raport bez `error`. Jeśli są — poprawiacie dziś, nie jutro.
- [ ] `.env` na hoście: pięć zmiennych `COMMS_LIVE_OVERRIDE_*` z `UNTIL` = jutro 18:00.
      Restart. W logu baner, `/actuator/health` → `active=true`.
- [ ] Grafana: panel z `comms.live_override.active`, `comms.live_override.blocked`,
      `comms.live_override.expired`, `communication.blocked.*`.

### 4.3. D-day — przebieg 1: Tier 1 (30 min)

1. Observer: telefon na stole, skrzynka otwarta, kartka z listą 16 pozycji `[R01/16]…[R16/16]`.
2. Driver: `POST /run`. Czyta na głos każdą pozycję raportu z `delivery.ok=true` i `providerId`.
3. Observer odhacza wiadomości **w miarę przychodzenia**, dla każdej cztery pytania:
   - Czy jest to ta wiadomość (numer zgadza się z raportem)?
   - Czy dane są prawdziwe i pełne: Jan, Audi RS6 Avant, WE 4RS6X, jutrzejsza data, 10:00, 4 900,00 zł?
   - Czy jest cokolwiek, czego klient nie powinien widzieć: klamry, `null`, podwójne spacje,
     ucięty tekst, dziwne znaki zamiast polskich liter, tagi?
   - Czy link **otwiera się na telefonie** i pokazuje właściwą kartę?
4. Dodatkowo dla e-maili: nadawca („DetailBoost" — czy tak chcecie?), temat bez prefiksu
   `[TEST →…]` jest tym, co zobaczy klient, folder **Odebrane, nie Spam**; w Gmailu
   „Pokaż oryginał" → `SPF: PASS`, `DKIM: PASS`. Jeśli FAIL — to jest blocker deployu,
   niezależnie od treści.
5. Dodatkowo dla SMS: nagłówek nadawcy (nazwa vs numer ECO), polskie znaki (ECO je zdejmie),
   liczba segmentów zgodna z raportem, kolejność.
6. Driver: w `communication_log` liczba wierszy z `context LIKE 'REHEARSAL:%'` = 16,
   wszystkie `redirected_from` niepuste, wszystkie ze statusem sukcesu.
   `comms.live_override.blocked` = 0.

**Kryterium przejścia przebiegu 1:** 16/16 odebranych, 0 błędów wizualnych, SPF/DKIM PASS,
0 blokad. Cokolwiek innego = poprawka, `plan`, powtórka. Nie ma „to drobiazg".

### 4.4. D-day — przebieg 2: Tier 2, podróż klienta (45–60 min)

Zamiana ról. Driver: `POST /journey`. Runner wykonuje kroki z tabeli 2.2 z odstępami,
w tym czeka na scheduler (do 2 min na krok). Observer robi to samo co w 4.3, ale
dodatkowo:

- na SMS_UPSELL_CONSENT **odpisuje „TAK"** z telefonu — sprawdza, czy webhook
  `SmsInboundController` zmienił status w CRM;
- na SMS_SIGNATURE_REQUEST otwiera link i **podpisuje** na telefonie;
- na EMAIL_VISIT_CARD_LINK otwiera kartę z maila, nie z SMS-a;
- sprawdza, że **nie dostał nic ponad plan** (dedup w `sms_logs` działa — żaden SMS dwa razy);
- zapisuje czasy: kiedy utworzono rezerwację, kiedy przyszedł PRE_VISIT — czy offset się zgadza.

Cztery wiadomości stałe w kodzie (zgoda na zmianę usług ×2, reset hasła, zaproszenie
pracownika) Driver wywołuje ręcznie z UI na końcu tego przebiegu.

### 4.5. Powtórka z drugą konfiguracją (15 min)

Jeśli studio testowe miało potwierdzony nagłówek nadawcy — ustawcie `sms_api_name_confirmed=false`
i odpalcie `POST /run` jeszcze raz. Klienci studiów bez nagłówka zobaczą numer ECO i tekst
bez polskich znaków. Musicie to zobaczyć na własnym telefonie, zanim zobaczy to klient
z RS6.

### 4.6. Wyłączenie trybu i smoke produkcyjny (20 min) — najważniejszy krok

1. Driver usuwa **wszystkie** `COMMS_LIVE_OVERRIDE_*` z `.env`. Restart.
2. W logu **nie ma** banera. `/actuator/health` → `commsLiveOverride.active=false`.
   `comms.live_override.active` = 0 w Grafanie. Endpoint `/internal/comms-rehearsal/plan` → 404.
3. **Smoke bez override:** w studiu testowym Driver ręcznie, z UI, tworzy rezerwację
   dla klienta Jan Kowalski (numer foundera A) na jutro. Observer dostaje
   SMS_BOOKING_CONFIRMATION **bez prefiksu `[TEST →…]`**. To dowodzi, że po wyłączeniu
   trybu wysyłka do „obcego" numeru (nie z listy allowed — lista już nie istnieje) działa.
   Bez tego kroku nie wiecie, czy nie zostawiliście produkcji w stanie z pkt 0.
4. Wyłączcie reguły w studiu testowym albo skasujcie rezerwację, żeby scheduler nie
   dosłał PRE_VISIT w nocy.

### 4.7. Zapis sign-offu

Plik `docs/signoff/comms-<data>.md` w repo, commit z obydwoma nazwiskami:

```
Próba generalna komunikacji — 2026-09-XX
Kod: <sha>          Raporty: rehearsal-<studio>-<ts>.json (×3)
Przebieg 1 (Tier 1):  16/16   błędy: 0   warn zaakceptowane: sms-long ×1 (SMS_VISIT_CARD_LINK, 3 segmenty — OK)
Przebieg 2 (journey): 18/18   webhook TAK: OK   podpis: OK   dedup: OK   offsety: OK
ECO bez nagłówka:     10/10   polskie znaki zdjęte — zaakceptowane
Dostarczalność:       Gmail Odebrane SPF/DKIM PASS; o2 Odebrane
Override wyłączony:   health active=false, baner brak, endpoint 404, smoke bez prefiksu: OK
Decyzja: GO / NO-GO   Podpisy: __________  __________
```

Zielone światło dajecie **tylko** z kompletnym plikiem. Każde puste pole = NO-GO.

### 4.8. Po deployu produkcyjnym (10 min, tego samego dnia)

- `/actuator/health` na prod → `commsLiveOverride.active=false`.
- Grep `.env` prod → brak `COMMS_LIVE_OVERRIDE`, brak `SMSAPI_WHITELIST`.
- Jedna prawdziwa rezerwacja w prawdziwym studiu na numer foundera → SMS dochodzi bez prefiksu.
- Przez pierwsze 48 h alert na `communication.blocked.*` i na `failure` w `communication_log`
  — to jest wskaźnik, że coś jeszcze blokuje, tak jak dziś blokują `allowedMails` i `whitelist`.

---

## 5. Kolejność wdrożenia (co zrobić w kodzie, w tej kolejności)

1. Usunąć `allowedMails` z `JavaMailProvider` i `smsapi.whitelist` z `SmsApiProperties`/`application.properties`.
   Zrotować hasło SMTP.
2. `LiveOverrideProperties` + walidacja startowa + baner + health + gauge.
3. `RecipientResolver` w bramce + kolumna `communication_log.redirected_from`.
4. `GuardedSmsProvider` / `GuardedEmailProvider` w konfiguracjach beanów.
5. Przepiąć `CampaignController.testSend` (`:518`) na bramkę zamiast `emailProvider.send`.
6. `RehearsalFixture` + test kompletności; `RenderedMessageValidator` + testy na każdą regułę
   (w tym `{{imię}}`, `{imie}`, `{{imie}}}`, HTML w mailu).
7. `CommsRehearsalRunner` + kontroler `@ConditionalOnProperty`; `plan` / `run` / `journey`.
8. `LiveOverrideLeakGuardTest` + krok w `Jenkinsfile`.
9. Uszczelnienie `MessageTemplateKind.validate` (pkt 3.4).
10. Próba generalna wg pkt 4.
