# Analiza systemu DetailBoost CRM

> Dokument analityczno‑architektoniczny. Powstał na podstawie kodu trzech repozytoriów:
> `automotive-crm-v2-backend` (Kotlin/Spring), `detailing-crm-v2` (panel React)
> oraz `detailing-crm-v2-tablet` (kiosk podpisu).
> Wszystkie wersje i zachowania biznesowe zostały odczytane z plików konfiguracyjnych,
> encji domenowych i handlerów — nie z dokumentacji marketingowej.

---

# CZĘŚĆ 1: OPIS TECHNICZNY

## 1.1 Baza danych i warstwa danych

| Element | Wersja / szczegóły | Źródło |
|---|---|---|
| PostgreSQL | 15+ (sterownik `org.postgresql:postgresql` z BOM Spring Boota) | `architecture.docs`, `application.properties` |
| pgvector | rozszerzenie wektorowe, tabela `instagram_post_vectors`, HNSW, `COSINE_DISTANCE`, 1536 wymiarów | `application.properties` |
| HikariCP | `com.zaxxer:HikariCP` (wersja zarządzana przez Spring Boot 3.2.5) | `build.gradle.kts` |
| Flyway | `flyway-core` (wersja z BOM); 88 migracji `V1…V98` | `src/main/resources/db/migration` |
| Hibernate / JPA | Spring Data JPA, dialekt `PostgreSQLDialect`, `ddl-auto=update` | `application.properties` |
| Redis | obraz `redis:alpine`; sesje (`spring-session-data-redis`), cache GUS, tokeny uploadu, challenge podpisu, pub/sub | `deploy/docker-compose.yaml` |

Architektura wielotenantowa: **wspólna baza, wspólny schemat, izolacja wierszowa**.
Każdy rekord ma kolumnę `company_id` / `studio_id`, każde zapytanie repozytorium filtruje po tenancie
(`findByIdAndCompanyId`), każdy indeks zaczyna się od kolumny tenanta.

## 1.2 Backend

| Element | Wersja |
|---|---|
| Kotlin (JVM) | 2.0.0 (`kotlin("jvm")`, `plugin.spring`, `plugin.jpa`) |
| Java / JVM target | 17 (`JavaVersion.VERSION_17`, obraz `eclipse-temurin:17-jre-alpine`) |
| Spring Boot | 3.2.5 |
| Spring Dependency Management | 1.1.4 |
| Gradle | 8.14 (wrapper), build w obrazie `gradle:8.5-jdk17` |
| Spring AI (BOM) | 1.0.0 |

Wykorzystane moduły frameworka:

- `spring-boot-starter-web`, `-validation`, `-websocket` (STOMP przez SockJS, endpoint `/ws-registry`)
- `spring-boot-starter-security` + `spring-session-data-redis` + `spring-boot-starter-data-redis`
  (sesje stanowe, ciasteczko HttpOnly/SameSite, natychmiastowa rewokacja)
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-cache` (cache danych GUS)
- `spring-boot-starter-aop` (aspekty uprawnień, entitlementów, metryk)
- `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
- `spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-pgvector`

Biblioteki dodatkowe backendu:

| Biblioteka | Wersja | Zastosowanie |
|---|---|---|
| kotlinx-coroutines (core / reactor / jdk8) | 1.8.0 | równoległe budowanie kontekstów walidacji, `Dispatchers.IO` |
| jackson-module-kotlin, kotlin-reflect | z BOM | serializacja |
| AWS SDK v2 (BOM) | 2.21.0 | `s3`, `sts`, `cloudwatch` — magazyn dokumentów, zdjęć, protokołów |
| Apache PDFBox + FontBox | 3.0.1 | wypełnianie formularzy AcroForm, składanie podpisanych PDF |
| metadata-extractor | 2.19.0 | normalizacja orientacji EXIF zdjęć uszkodzeń |
| thumbnailator | 0.4.20 | miniatury zdjęć |
| jsoup | 1.17.2 | odszumianie HTML e‑maili (cytaty, stopki) przed zapisem i przed LLM |
| BouncyCastle (`bcprov`, `bcpkix`, `bcutil`) | 1.78.1 | pieczęć PAdES (CMS/CAdES) + znacznik czasu RFC 3161 |
| KSeF SDK (`pl.akmf.ksef-sdk:ksef-client`) | 3.0.18 | Krajowy System e‑Faktur (repozytorium GitHub Packages) |
| SMSAPI Java SDK (`pl.smsapi:smsapi-lib`) | 3.0.1 | wysyłka SMS |
| JavaMail (`com.sun.mail:jakarta.mail`) | 2.0.1 | SMTP/IMAP |
| Resilience4j (circuitbreaker, retry, ratelimiter, kotlin) | 2.2.0 | odporność integracji GUS |
| MockK | 1.13.10 | testy |

## 1.3 Frontend — panel CRM (`detailing-crm-v2`)

| Element | Wersja |
|---|---|
| React / React DOM | 19.2.0 |
| TypeScript | 5.9.3 |
| Vite | 7.2.4 |
| React Router DOM | 7.12.0 |
| TanStack React Query (+ devtools) | 5.90.16 / 5.91.2 |
| Axios | 1.13.2 |
| React Hook Form + `@hookform/resolvers` | 7.70.0 / 5.2.2 |
| Zod | 4.3.5 |
| FullCalendar (core, daygrid, timegrid, interaction, react) | 6.1.20 |
| Recharts | 3.7.0 |
| styled-components | 6.3.1 |
| Tailwind CSS + `@tailwindcss/postcss` | 4.1.18 |
| PostCSS / autoprefixer / `@csstools/postcss-oklab-function` | 8.5.6 / 10.4.23 / 5.0.0 |
| lucide-react | 0.563.0 |
| dinero.js + `@dinero.js/currencies` | 2.0.0-alpha.14 |
| pdfjs-dist | 6.1.200 |
| qrcode.react | 4.1.0 |
| dompurify | 3.4.14 |
| `@stomp/stompjs` + sockjs-client | 7.2.1 / 1.6.1 |
| `@radix-ui/react-dialog` | 1.1.15 |
| Vitest + Testing Library + jsdom | 4.1.6 / 16.3.2 / 29.1.1 |
| ESLint + typescript-eslint | 9.39.1 / 8.46.4 |

## 1.4 Frontend — kiosk tabletowy (`detailing-crm-v2-tablet`)

Osobna aplikacja SPA („DetailBoost Tablet") uruchamiana na tablecie recepcyjnym.

| Element | Wersja |
|---|---|
| React / React DOM | 19.1.0 |
| TypeScript | 5.8.3 |
| Vite | 6.3.5 |
| pdfjs-dist | 5.3.31 |
| `@stomp/stompjs` + sockjs-client | 7.1.1 / 1.6.1 |
| Playwright | 1.53.0 (testy e2e) |
| Vitest | 3.2.4 |

## 1.5 Integracje zewnętrzne i infrastruktura

| System | Rola | Konfiguracja |
|---|---|---|
| **KSeF** (MF) | e‑faktury: wysyłka FA(3), pobieranie kosztów i przychodów, UPO, kody QR | `api.ksef.mf.gov.pl/api/v2`, sync co 15 min |
| **GUS BIR** (BIR 1.1 / 1.2, SOAP) | pobieranie danych kontrahenta po NIP | `wyszukiwarkaregon.stat.gov.pl`, sesja 55 min, cache Redis 24 h |
| **SMSAPI** | SMS wychodzące + 2‑way SMS (webhook `/api/sms/inbound`) | `api.smsapi.pl` |
| **OpenAI** (przez Spring AI) | `gpt-4o-mini` (generowanie treści), `text-embedding-3-small` (1536), `whisper-1` (transkrypcja PL) | zmienna `OPENAI_API_KEY` |
| **RapidAPI — Instagram** | dostawca `instagram-looter2` (aktywny) / `ig-scraper5` (legacy) | limit 4 req/s, budżet 2000 wywołań/dobę |
| **AWS S3** | dokumenty, protokoły, zdjęcia, zgody (presigned URL) | region `us-east-1` |
| **Przelewy24** | płatności za subskrypcję i pakiety SMS | sandbox/produkcja, tryb mock bez credentiali |
| **CloudFlare Email Workers** | webhook e‑maili przychodzących (nagłówek `X-Cloudflare-Email-Token`) | |
| **Web Push / VAPID** | powiadomienia push na telefon (click‑to‑call, zarobek po wizycie) | RFC 8292 |
| **CardDAV** | synchronizacja kontaktów klientów z telefonem (`/.well-known/carddav`) | hasła aplikacyjne |
| **Prometheus 2.52.0 + Grafana 11.0.0** | monitoring techniczny i analityka produktowa | `deploy/docker-compose.yaml` |
| **Jenkins** | CI/CD (`Jenkinsfile` w każdym repozytorium) | |
| **Docker** | osobne kontenery: backend, redis, prometheus, grafana, frontend | |

## 1.6 Wzorce architektoniczne

- **Vertical slices** — katalogi po funkcjach biznesowych (`visit/create/…`), nie po warstwach.
- **Composite + Context validation** — `ValidationContextBuilder` zrównolegla zapytania korutynami,
  `ValidatorComposite` uruchamia zestaw walidatorów przed handlerem.
- **Niezmienne snapshoty** — cena i nazwa usługi oraz dane pojazdu zamrażane w momencie utworzenia wizyty;
  edycja usługi w cenniku archiwizuje starą wersję (`is_active = false`) i tworzy nowy rekord.
- **Value classes** — `@JvmInline value class` dla wszystkich identyfikatorów (`StudioId`, `VisitId`…).
- **Money w groszach** — `Money(amountInCents)`, inwariant `netto + VAT = brutto` egzekwowany w konstruktorze.
- **Maskowanie PII na granicy serializacji** — adnotacja `@Pii` + `PiiMaskingModule`, nagłówek
  `X-Pii-Access: granted|masked`.

---

# CZĘŚĆ 2: OPIS FUNKCJONALNY

System jest wielotenantowym CRM‑em dla studiów detailingu samochodowego (tenant = „studio").
Ok. 1100 plików Kotlina w ~57 modułach biznesowych, ok. 90 bazowych ścieżek REST.

## 2.1 Rezerwacje i przyjmowanie wizyt

### Model dwustopniowy: rezerwacja → wizyta

System rozdziela **rezerwację** (`Appointment` — obietnica terminu) od **wizyty**
(`Visit` — auto fizycznie w warsztacie). To dwie osobne encje z osobnymi cyklami życia.

**Cykl życia rezerwacji** (`AppointmentStatus`): `CREATED → CONVERTED` (przyjęto auto) /
`CANCELLED` (odwołana) / `ABANDONED` (klient nie przyjechał).

Status `ABANDONED` nadaje automat: `ReservationStatusUpdateJob` uruchamiany cronem `0 */15 * * * *`
oznacza jako porzucone wszystkie rezerwacje w statusie `CREATED`, których termin (strefa Europe/Warsaw)
minął wczoraj lub wcześniej. Zdarzenie trafia do dziennika audytu jako akcja użytkownika „System".

**Cykl życia wizyty** (`VisitStateMachine`, przejścia egzekwowane w kodzie):

```
IN_PROGRESS ─→ READY_FOR_PICKUP ─→ COMPLETED ─→ ARCHIVED
     │                  │
     │                  └─→ IN_PROGRESS (powrót, gdy trzeba dorobić)
     └─→ REJECTED ─────────────────────────────→ ARCHIVED
```

Próba niedozwolonego przejścia kończy się `IllegalStateTransitionException`.

### Tworzenie rezerwacji

`POST /api/v1/appointments`. Przed zapisem uruchamiany jest komplet walidatorów:
istnienie klienta, istnienie pojazdu, unikalność nowego klienta, dane kontaktowe klienta,
poprawność stawki VAT pozycji, wymagalność ceny ręcznej, poprawność koloru rezerwacji.

Rezerwacja zawiera pozycje usługowe z **silnikiem korekt cenowych** (`AdjustmentType`):

| Typ | Znaczenie |
|---|---|
| `PERCENT` | rabat/narzut w punktach bazowych (−1050 = −10,5 %) |
| `FIXED_NET` | kwota stała doliczana do netto |
| `FIXED_GROSS` | kwota stała doliczana do brutto (netto przeliczane wstecz) |
| `SET_NET` | nadpisanie ceny netto |
| `SET_GROSS` | nadpisanie ceny brutto („VAT w stu") |

Cena wpisana przez użytkownika jest źródłem prawdy — przy wprowadzaniu ceny brutto system
nie przelicza jej wstecz z netto (dopuszczalna tolerancja 1 grosza na zaokrągleniu).

**Rezerwacje cykliczne**: `POST /api/v1/appointments/recurring` tworzy `RecurrenceSeries`.
Pojedyncze wystąpienie można „odczepić" od serii (`isDetached`) i edytować niezależnie;
`GET /api/v1/appointments/series/{seriesId}` zwraca całą serię.

**Rezerwacja z leada**: `POST /api/v1/appointments/from-lead/{leadId}` — zapytanie ofertowe
zamienia się w rezerwację wraz z wyceną (`LeadQuoteSyncService`), a lead pozostaje z nią powiązany.

### Edycja, anulowanie, usuwanie

- `PUT /api/v1/appointments/{id}` — pełna edycja (termin, usługi, pojazd, klient).
- `PATCH /api/v1/appointments/{id}` — zmiana statusu.
- `POST /api/v1/appointments/{id}/restore` — przywrócenie anulowanej.
- `DELETE /api/v1/appointments/{id}` — usunięcie miękkie (uprawnienie `VISITS_CREATE`).
- `DELETE /api/v1/appointments/{id}/permanent` — usunięcie twarde (uprawnienie `VISITS_DELETE`).
- `PATCH …/sms-preferences` — włączenie/wyłączenie SMS przypominającego dla tej konkretnej rezerwacji.
- `PATCH …/title` — własny tytuł widoczny w kalendarzu.

### Kalendarz

`GET /api/v1/calendar/events` zwraca jednorodny strumień zdarzeń: rezerwacje, wizyty,
urlopy pracowników, zdarzenia własne (`/api/v1/calendar/events-custom`) oraz trasy Door‑to‑Door
(`GET /api/v1/calendar/door-to-door`). Frontend renderuje je w FullCalendar 6 (widoki miesiąc /
tydzień / dzień, drag & drop przez `@fullcalendar/interaction`).

**Kolory rezerwacji** to osobny słownik per studio (`/api/v1/appointment-colors`) z operacjami
tworzenia, edycji, archiwizacji, usuwania i ustawienia domyślnego koloru.

### Dostępność pracowników

Dostępność modelowana jest przez **urlopy i nieobecności** (`EmployeeLeave`) —
`/api/v1/employees/{id}/leaves` oraz kalendarz urlopowy zespołu. Nieobecności nakładają się
na kalendarz rezerwacji jako osobna warstwa zdarzeń. Osobno działa **ewidencja czasu pracy**
(`/api/v1/my/worktime`, `/api/v1/worktime/team`) — wpisy dzienne, miesięczne okresy
rozliczeniowe ze statusem, automatyczne liczenie nadgodzin (ponad 480 min/dzień).

### Przyjęcie pojazdu (check‑in)

Dwie ścieżki:

1. `POST /api/checkin/reservation-to-visit` — z istniejącej rezerwacji.
2. `POST /api/checkin/walk-in` — klient „z ulicy", bez wcześniejszej rezerwacji.

Przy przyjęciu rejestrowane są: przebieg, wydanie kluczy, wydanie dokumentów, uwagi z oględzin,
notatki techniczne, zdjęcia oraz **mapa uszkodzeń** — punkty nanoszone na schemat pojazdu
(`DamagePoint`, `DamageMarkingService`, obraz mapy generowany i zapisywany w S3).

**Mobilne wsparcie check‑inu przez QR**: `POST /api/checkin/{appointmentId}/upload-token` generuje
token (TTL 3 h w Redisie); telefon pracownika otwiera `/m/upload`, robi zdjęcia i zaznacza uszkodzenia,
a desktop dostaje aktualizacje przez WebSocket (`CheckinPhotoUploadedMessageListener`,
`CheckinDamageUpdatedMessageListener`).

**Sesje zdjęciowe** (`/api/photo-sessions`): sesja ważna 2 h, maks. 20 zdjęć, upload bezpośrednio
do S3 przez presigned URL, porzucone sesje sprząta `PhotoUploadSessionCleanupJob`.

### Karta wizyty (`Visit`)

Karta wizyty gromadzi:

- niezmienne snapshoty pojazdu (marka, model, rejestracja, VIN, rocznik, kolor),
- listę pozycji usługowych z własnym cyklem akceptacji,
- zdjęcia i mapę uszkodzeń,
- komentarze (`/api/visits/{id}/comments` — dodawanie, edycja, usuwanie),
- notatkę techniczną z **pełną historią zmian** (`/technical-note/history`),
- dokumenty i protokoły,
- planowaną i faktyczną datę zakończenia oraz datę odbioru,
- dziennik zdarzeń (journal).

**Zmiana zakresu usług w trakcie wizyty** (`PATCH /api/visits/{id}/services/`) to osobny protokół:
`ServicesChangePlanner` wylicza operacje `ADD` / `EDIT` / `DELETE`, a każda pozycja ma status
`PENDING → APPROVED | REJECTED | CONFIRMED` z zapamiętanym snapshotem poprzednio potwierdzonej ceny.
Do sum wizyty wliczane są tylko pozycje potwierdzone (pozycja `PENDING/ADD` nie podbija kwoty,
pozycja `PENDING/EDIT` liczy się po starej cenie). Akceptacja może przyjść od pracownika
(`/services/{id}/approve`, `/reject`) albo **od klienta SMS‑em zwrotnym** (patrz 2.2).

### Zakończenie wizyty

- `POST /api/visits/{id}/mark-ready-for-pickup` — auto gotowe (wyzwala SMS/e‑mail „do odbioru").
- `POST /api/visits/{id}/complete` — wydanie auta; tu powstają dokumenty finansowe i opcjonalnie
  faktura KSeF (patrz 2.6).
- `POST /api/visits/{id}/reject` — odrzucenie.
- `POST /api/visits/{id}/archive` — archiwizacja.
- `DELETE /api/visits/{id}/cancel` — anulowanie wizyty w wersji roboczej.

### Karta wizyty dla klienta i upsell

`/api/v1/visits/{id}/card-link` generuje publiczny, tokenizowany link
(`/vc/{token}`, `PublicVisitCardController`), który klient otwiera bez logowania. Widzi tam status
swojego auta i zaproponowane usługi dodatkowe. Kliknięcie „Chcę" (`POST /{token}/upsell/request`)
tworzy prośbę o zgodę; potwierdzenie klienta uruchamia `UpsellConsentConfirmedListener`,
który dopisuje usługi do rezerwacji/wizyty. Odwiedziny karty są rejestrowane
(`VisitCardViewRecorder`), a status wysyłki linku śledzi `VisitCardSendStatusService`.

### Door‑to‑Door

Osobny moduł obsługi odbioru i dowozu auta (`/api/visits/{id}/door-to-door`) z adresem odbioru,
adresem dostawy, notatkami i statusem: `SCHEDULED → IN_PICKUP → PICKED_UP → IN_DELIVERY → DELIVERED`.
Trasy widoczne są jako osobna warstwa kalendarza.

---

## 2.2 Komunikacja (SMS i e‑mail)

### Zasada nadrzędna

Konfiguracja jest **per studio**, wszystkie reguły są **domyślnie wyłączone**, a pusty szablon
oznacza brak wysyłki (`sendable = enabled && messageTemplate.isNotBlank()`). System nigdy nie podstawia
tekstu zastępczego — jeśli studio nie napisało treści, wiadomość nie wychodzi.

Każda wysyłka przechodzi przez `OutboundCommunicationGateway`, który sprawdza kategorię
(`OutboundMessageCategory`) i wymagane uprawnienie abonamentowe:

| Kategoria | Wymagana zdolność |
|---|---|
| `TRANSACTIONAL` | `COMM_SEND_TRANSACTIONAL` (moduł SMS_EMAIL) |
| `CAMPAIGN` | `COMM_SEND_CAMPAIGN` (moduł CAMPAIGNS) |
| `SIGNATURE_ONBOARDING` | `SIGNATURE_LOCAL` (moduł e‑podpisów) |

Każda wiadomość ląduje w `communication_log` (kanał, typ, treść, status, czas), widocznym
na karcie klienta i karcie wizyty (`GetCustomerCommunicationHandler`, `GetVisitCommunicationHandler`).

### Automatyzacje SMS — kiedy dokładnie wychodzi wiadomość

`SmsAutomationScheduler` uruchamia się **co minutę** (`0 * * * * *`) i dopasowuje zdarzenia
w oknie ±60 sekund wokół wyliczonego czasu.

**Reguły czasowe** (`SmsAutomationRule` — mają offset w minutach):

| Trigger | Punkt odniesienia | Domyślny offset | Domyślny szablon |
|---|---|---|---|
| `PRE_VISIT` | *przed* startem rezerwacji | 60 min | „Przypominamy o wizycie dnia {{data}} o godz. {{godzina}}. Do zobaczenia, {{imie}}!" |
| `POST_VISIT` | *po* odbiorze auta (wizyta `COMPLETED`) | 30 min | „Dziękujemy za wizytę, {{imie}}! Mamy nadzieję, że jesteś zadowolony z usługi." |
| `DELAYED_REMINDER` | *po* odbiorze auta | 90 dni (129 600 min) | „Cześć {{imie}}! Minęły 3 miesiące od Twojej ostatniej wizyty. Czas na kolejny detailing? Zapraszamy!" |

Istotna decyzja projektowa: **tylko `PRE_VISIT` czyta rezerwacje**. `POST_VISIT` i `DELAYED_REMINDER`
czytają wyłącznie **zakończone wizyty**, bo minięcie godziny rezerwacji nie dowodzi, że klient przyjechał.
Rezerwacja bez check‑inu nigdy nie dostanie podziękowania ani przypomnienia po miesiącach.

**Reguły zdarzeniowe** (`SmsNotificationRule` — wysyłane natychmiast, bez offsetu):

| Trigger | Moment wysyłki | Domyślny szablon |
|---|---|---|
| `bookingConfirmation` | utworzenie rezerwacji | „Drogi/a {{imie}}, potwierdzamy rezerwację na {{data}} o godz. {{godzina}}. Czekamy na Ciebie!" |
| `rescheduleConfirmation` | zmiana terminu rezerwacji | „…termin Twojej wizyty został zmieniony na {{data}} o godz. {{godzina}}…" |
| `visitReadyForPickup` | przejście wizyty w `READY_FOR_PICKUP` | „…Twój pojazd {{pojazd}} {{rejestracja}} jest gotowy do odbioru. Zapraszamy!" |
| `visitCardLink` | wysłanie linku do karty wizyty | „Karta Twojej wizyty {{numer_wizyty}} ({{pojazd}} {{rejestracja}}) jest dostępna tutaj: {{link}}" |
| `reservationCardLink` | wysłanie linku do karty rezerwacji | „Szczegóły Twojej rezerwacji na {{data}} o godz. {{godzina}} znajdziesz tutaj: {{link}}" |
| `upsellConsent` | propozycja usług dodatkowych | „Odpisz TAK, żeby do rezerwacji dodać usługi: {{uslugi}}. Łącznie {{kwota}} PLN brutto." |
| `signatureRequest` | prośba o podpis na urządzeniu klienta | „Dokument „{{dokument}}" czeka na Twój podpis. Otwórz link… {{link}}" |

**Przypomnienie „na żądanie"**: jeśli studio ma wyłączoną regułę `PRE_VISIT`, ale pracownik zaznaczył
`sendReminderSms` na konkretnej rezerwacji, scheduler i tak wyśle przypomnienie — ze sztywnym
offsetem 60 minut. Opt‑in pojedynczej rezerwacji nadpisuje przełącznik reguły, ale **nie** jej treść:
bez skonfigurowanego szablonu nie ma czego wysłać.

**Przypomnienie ręczne per wizyta** (`/api/visits/{id}/sms-reminder`): pracownik planuje pojedynczy SMS
na wskazaną godzinę (`ScheduledSmsReminder`: `PENDING → SENT | FAILED | CANCELLED`). Treść można
wygenerować modelem językowym (`SmsContentGeneratorService`) albo napisać ręcznie. Numer telefonu
jest zamrażany w chwili planowania — późniejsza zmiana danych klienta nie wpłynie na zaplanowaną wysyłkę.

**Deduplikacja**: tabela `sms_send_log` ma unikalny indeks na `(appointment_id, trigger_type)`.
Raz wysłany SMS danego typu dla danej rezerwacji nigdy nie wyjdzie drugi raz, nawet jeśli
scheduler trafi w okno ponownie.

### SMS dwukierunkowy (2‑way)

Gdy pracownik zmienia zakres usług i zaznaczy „powiadom klienta", wychodzi SMS z prośbą o odpowiedź
**„TAK"**. SMSAPI wysyła odpowiedź klienta webhookiem na `POST /api/sms/inbound`.
`SmsConsentService` koreluje odpowiedź po numerze telefonu z rekordem `SmsConsentRequest`
(`PENDING → CONFIRMED`, starsze żądania dla tej samej wizyty → `SUPERSEDED`) i **automatycznie
zatwierdza wszystkie oczekujące pozycje usługowe** tej wizyty. To jest formalna zgoda klienta
na rozszerzenie zakresu prac.

### Nazwa nadawcy SMS

Nie ma globalnej nazwy nadawcy. Nagłówek pochodzi z `sms_automation_configs.sms_sender_name`
danego studia i jest używany **dopiero po** `sms_api_name_confirmed = true`. Studio bez potwierdzonego
nagłówka wysyła w trybie ECO (z numeru SMSAPI, bez nazwy). Proces potwierdzenia obejmuje
wygenerowanie **upoważnienia nadawcy SMS** (szablon `upowaznienie_nadawcy_sms.html` / PDF),
podpisanie go elektronicznie w aplikacji lub wgranie skanu, po czym `SmsAuthorizationNotifier`
wysyła dokument do weryfikacji u operatora.

### Kredyty SMS

`SmsCreditService` prowadzi saldo per studio z operacjami: `tryDeductCredit` (pobranie przed wysyłką),
`refundCredit` (zwrot przy błędzie dostawcy), `grantStarterCredits` (pakiet startowy),
`purchaseCredits` (zakup pakietu). Pakiety kupowane są przez Przelewy24.
Brak środków blokuje wysyłkę — to twarda bramka, nie ostrzeżenie.

### Automatyzacje e‑mail

`EmailAutomationConfig` — pięć reguł zdarzeniowych, każda z osobnym szablonem tematu i treści:

| Reguła | Moment wysyłki |
|---|---|
| `visitWelcome` | przyjęcie pojazdu (check‑in) — potwierdzenie z numerem wizyty |
| `visitReadyForPickup` | oznaczenie auta jako gotowego do odbioru |
| `visitCardLink` | wysłanie linku do karty wizyty |
| `reservationCardLink` | wysłanie linku do karty rezerwacji |
| `batchOrderClose` | zamknięcie miesiąca dla kontrahenta (raport w załączniku) |

E‑maile transakcyjne wychodzą przez JavaMail (SMTP z STARTTLS). Do wiadomości `visitWelcome`
może zostać dołączony wygenerowany protokół przyjęcia pojazdu (PDF).

### Szablony i zmienne dynamiczne

`MessageTemplateKind` jest **zamkniętym katalogiem** — każdy rodzaj wiadomości deklaruje,
których zmiennych wolno użyć. Szablon z nieznaną zmienną jest odrzucany **przy zapisie**
(`ValidationException` z listą dostępnych zmiennych), więc `MessageTemplateRenderer` nigdy nie dostaje
szablonu, którego nie umiałby wypełnić.

Dostępne zestawy zmiennych:

| Zestaw | Zmienne |
|---|---|
| Klient | `{{imie}}`, `{{nazwisko}}`, `{{imie_nazwisko}}` (tylko e‑mail) |
| Termin | `{{data}}`, `{{godzina}}` |
| Pojazd | `{{pojazd}}`, `{{rejestracja}}` |
| Wizyta | `{{numer_wizyty}}` |
| Link | `{{link}}` |
| Upsell | `{{uslugi}}`, `{{kwota}}` |
| Podpis | `{{dokument}}` |
| Zlecenia zbiorcze | `{{kontrahent}}`, `{{okres}}`, `{{kwota_brutto}}`, `{{liczba_wpisow}}` |
| Kampanie | `{{marka}}`, `{{model}}`, `{{ostatnia_usluga}}`, `{{data_ostatniej_wizyty}}`, `{{dni_od_wizyty}}` |

Świadomie **nie ma** zmiennych opisujących samo studio (nazwa, telefon, adres, godziny otwarcia) —
te dane studio zna i wpisuje w szablon jako zwykły tekst.

Cztery wiadomości są celowo poza katalogiem i mają treść zaszytą w kodzie, bo dyktuje ją przepływ,
a nie marketing: dwa SMS‑y o zmianie zakresu usług (z „Odpisz TAK" i wariant tylko informacyjny),
e‑mail resetu hasła oraz e‑mail zaproszenia pracownika.

### Kampanie marketingowe

Osobny moduł (`campaigns`) z dwoma rodzajami kampanii dzielącymi jeden model:
**jednorazowe** (`DRAFT → SCHEDULED → SENDING → COMPLETED`, plus `CANCELLED`/`FAILED`) oraz
**automatyczne** (`DRAFT → ACTIVE ⇄ PAUSED → ARCHIVED`). Kanał: `SMS`, `EMAIL` lub `BOTH`.

**Definicja grupy odbiorców** (`AudienceCriteria`, zapisywana jako JSONB) pozwala filtrować po:
liczbie wizyt (min/max), dacie ostatniej wizyty (starsza/nowsza niż N dni), sumie przychodu brutto,
użytych usługach (`anyOf` / `noneOf`), dacie ostatniego użycia usługi, marce i modelu pojazdu,
roczniku, typie klienta (indywidualny/firma), dacie utworzenia klienta, a także ręcznym dodaniem
i wykluczeniem konkretnych osób. Flaga `includeUnnamedCustomers` decyduje o klientach przyjętych
„na numer telefonu" (kreator ustawia ją na `false` — wiadomość „Cześć !" szkodzi bardziej niż pomaga).

**Wyzwalacz kampanii automatycznej** (`TriggerConfig`): lista usług + liczba dni po usłudze
+ godzina wysyłki + opcja „tylko jeśli od tego czasu nie było wizyty".

**Zabezpieczenia wbudowane w silnik** (`CampaignEngine`, ustawienia domyślne):

- **Godziny ciszy** 20:00–08:00 — wysyłka wpadająca w okno jest przesuwana na jego koniec
  (okno może przechodzić przez północ).
- **Limit częstotliwości** — domyślnie 7 dni między wiadomościami do tej samej osoby.
- **Zgody marketingowe** — sprawdzane ponownie w chwili wysyłki, nie tylko przy budowaniu listy.
- **Opt‑out** (`CampaignOptOutService`) ze źródłem: `SMS_STOP`, `EMAIL_LINK`, `MANUAL`.
- **Stopki** SMS i e‑mail konfigurowane per studio.
- **Kredyty SMS** — kreator pokazuje z góry, ilu odbiorców odpadnie i ile kredytów pochłonie kampania.

---

## 2.3 Skrzynka poczty i social media

### Centralna skrzynka odbiorcza (moduł `comms`)

**Zakres rzeczywisty: e‑mail (IMAP/SMTP).** W kodzie nie ma integracji z wiadomościami prywatnymi
Instagrama ani żadnego innego komunikatora — Instagram występuje wyłącznie jako moduł monitoringu
konkurencji i generowania postów (patrz 2.9). Kanały społecznościowe wpięte do skrzynki to na dziś
kanał pocztowy plus formularze WWW wpadające na skrzynkę jako e‑mail.

**Podłączanie skrzynki** (`/api/v1/mailbox`):
`POST /accounts/detect` wykrywa dostawcę (`MailAutodiscoverService`) i typ połączenia
(`GOOGLE_API`, `MS_GRAPH`, `IMAP_SMTP`) oraz metodę uwierzytelnienia (`OAUTH2`, `PASSWORD`,
`APP_PASSWORD`), żeby onboarding od razu skierował użytkownika na ekran zgody zamiast pytać o hasło.
Hasła skrzynek są szyfrowane **AES‑GCM** kluczem `MAILBOX_ENCRYPTION_KEY`; bez ustawionego klucza
podłączenie skrzynki kończy się błędem, a nie zapisem słabo chronionych danych.
Konto ma status `ACTIVE | AUTH_FAILED | DISABLED`.

**Silnik synchronizacji**:
- `ImapIdleWatcher` — nasłuch IMAP IDLE (poczta pojawia się bez odpytywania),
- `ImapSyncEngine` + `CommsIngestService` — pobranie i zapis,
- `MimeEmailParser` — parsowanie MIME, załączniki wraz z obrazami inline (`cid:`),
- `EmailHtmlSanitizer` — sanityzacja HTML przed wyświetleniem,
- `EmailTextCleaner` (jsoup) — usuwanie cytowanej historii i stopek przed zapisem i przed wywołaniem LLM,
- `CommsOutboxProcessor` — idempotentna kolejka operacji zwrotnych do IMAP:
  `MARK_SEEN` (ustawienie flagi `\Seen`) i `APPEND_SENT` (dopisanie wysłanej wiadomości do folderu Wysłane).

**Stan przeczytania** jest dwukierunkowy: `CommReadSource` rozróżnia, czy wiadomość została otwarta
w CRM‑ie, czy flaga `\Seen` przyszła z serwera (telefon, webmail). Odczyt w telefonie gasi
nieprzeczytane w CRM‑ie i odwrotnie.

**Funkcje skrzynki** (`/api/v1/comms`):
wątki z paginacją i wyszukiwaniem, szczegóły wątku, oznaczanie wątku/wiadomości jako przeczytanych,
wysyłka i odpowiedź, pobieranie i podgląd inline załączników, etykiety (tworzenie, usuwanie,
przypisanie do wątku), archiwizacja wątku, wątki powiązane z tym samym kontaktem,
**podpisy użytkowników** (`/signature` — osobisty podpis pod wiadomościami)
oraz **korekta treści przez AI** (`POST /proofread`, `MailProofreadService`).

**Detekcja poczty automatycznej** (`AutomatedMailDetector`) na podstawie nagłówków list/auto‑reply
odsiewa newslettery i autorespondery od realnych zapytań.

### Powiązanie konwersacji z klientem

To jest oś modułu. `GetContactCardHandler` (`GET /comms/contact-card?email=…`) buduje **kartę kontaktu**
z adresu e‑mail nadawcy: dopina klienta z bazy CRM, jego pojazdy, historię wizyt i wartość.
`GET /comms/threads/{id}/contact-badges` pokazuje w wątku plakietki mówiące, kim jest rozmówca
(klient / lead / nieznany). `GET /comms/threads/{id}/related` zbiera pozostałe wątki tej samej osoby.

**Notatki kontaktowe** (`/comms/notes`, `ContactNoteService`) prowadzone są per adres e‑mail,
z pełną historią zdarzeń (`/comms/notes/history`).
**Insighty kontaktu** (`GetContactInsightsHandler`) podsumowują korespondencję.

**Lead z wątku**: `MarkThreadAsLeadHandler` zamienia rozmowę w leada. Lead e‑mailowy **nie kopiuje
wiadomości** — wskazuje na `threadId`, więc historia leada *jest* wątkiem (bez duplikacji i synchronizacji).

**Formularze WWW jako e‑mail**: `FormMailExtractionService` i `FormMailAutoLeadListener` rozpoznają
wiadomości pochodzące z formularzy kontaktowych na stronie, wyciągają z nich pola i **automatycznie
tworzą leada**. Konfiguracja źródeł formularzy: `/api/public/lead-forms` i sekcja „Formularze" w ustawieniach.
Alternatywnie działa webhook bezpośredni: `/api/v1/leads/intake-webhooks` + `LeadIntakeService`.

**Połączenia przychodzące** (`/api/v1/inbound/calls`) to równoległy kanał: rejestracja połączenia,
przyjęcie (`AcceptCallHandler` z walidatorami „połączenie istnieje" / „nie zostało już obsłużone"),
odrzucenie, aktualizacja. Wpina się w tę samą kartotekę klienta.

**Click‑to‑call**: kliknięcie numeru na desktopie wysyła web push na sparowany telefon pracownika
(`RequestCallHandler`, VAPID), który od razu wybiera numer.

### CardDAV — kontakty w telefonie

Osobny serwer CardDAV (`/.well-known/carddav`, `/api/v1/carddav/{tenantId}`) udostępnia bazę klientów
studia jako książkę adresową telefonu. Eksportowani są aktywni klienci z numerem telefonu
(`VCardFormatter`), autoryzacja przez **hasła aplikacyjne** (`carddav_app_passwords`, migracja V98),
konfiguracja przez profil `.mobileconfig` (`MobileConfigBuilder`) i ekran
„Synchronizacja kontaktów" w ustawieniach. Dzięki temu telefon pracownika pokazuje nazwisko klienta
przy przychodzącym połączeniu.

---

## 2.4 Karta klienta, historia i zdjęcia

### Kartoteka klienta

`GET /api/v1/customers/{id}/detail` zwraca pełną kartę:

- dane osobowe i firmowe (osobne endpointy `PATCH /company` i `DELETE /company` — dane firmowe
  można dopiąć i odpiąć bez ruszania osoby),
- pojazdy klienta (relacja M:N przez `vehicle_owners` — jedno auto może mieć wielu właścicieli,
  jedna osoba wiele aut),
- **historię wizyt** z wykonanymi usługami,
- **podsumowanie przychodu** (`GET /revenue-summary`) — ile ten klient zostawił w studiu,
- historię komunikacji (SMS, e‑mail, wątki poczty, połączenia),
- notatki (`/api/v1/customers/{id}/notes`),
- dokumenty (`/api/v1/customers/{id}/documents`),
- zgody i ich status (`/api/v1/customers/{id}/consents`),
- `POST /api/v1/customers/{id}/sms` — wysyłka SMS ad hoc z karty.

Dane osobowe są chronione dwupoziomowo: uprawnienie `CUSTOMERS_VIEW` jest **uprawnieniem
do danych osobowych**. Widoki warsztatowe (kalendarz, wizyty) działają bez niego, ale pola
oznaczone `@Pii` są maskowane `"***"` na granicy serializacji (nagłówek `X-Pii-Access: masked`).
Widoki osobowe (kartoteka, dokumenty, faktury) bez tego uprawnienia zwracają 403 zamiast maskować.

### Kartoteka pojazdu

`/api/v1/vehicles/{id}` — dane pojazdu, właściciele (`assign-owner` / `remove-owner`),
historia wizyt pojazdu, rezerwacje, komentarze, notatki (`/notes`), dokumenty, zdjęcia (`/photos`),
galeria pojazdu. Dodatkowo:

- `LookupVehicleByPlateHandler` — wyszukiwanie po numerze rejestracyjnym,
- `VehicleCatalogMatcher` + `VehicleMatchingAiConfig` — dopasowanie wpisanej marki/modelu
  do słownika katalogowego (z pomocą LLM); w bazie nigdy nie ląduje surowy tekst od klienta,
- `VehicleSegmentService` — segmentacja pojazdów (migracja V82),
- `VehicleMetadataService` (`/api/v1/vehicle-metadata`) — słowniki marek i modeli.

### Moduł zdjęć

Zdjęcia żyją w trzech powiązanych miejscach:

1. **Zdjęcia wizyty** — `GET/POST /api/visits/{id}/photos`, `DELETE …/photos/{photoId}`
   (usuwanie wymaga osobnego uprawnienia `VISITS_MEDIA_DELETE`, bo zdjęcia bywają dowodem w sporze).
2. **Zdjęcia pojazdu** — `/api/v1/vehicles/{id}/photos` plus zbiorcza galeria pojazdu.
3. **Zdjęcia wpisów zleceń zbiorczych** — `/api/batch-orders/entries/{id}/photos`.

Przechowywanie: **AWS S3**, upload bezpośredni przez presigned URL (backend nie przepuszcza bajtów
przez siebie), limit 15 MB na plik. Przy zapisie normalizowana jest orientacja EXIF
(`metadata-extractor`) i generowane miniatury (`thumbnailator`, `PhotoThumbnailService`,
`ThumbnailBackfillJob` uzupełnia miniatury historycznych zdjęć).

**Galeria studia** (`GET /api/v1/gallery`) to przekrojowy widok wszystkich zdjęć z filtrowaniem.
**Tagi zdjęć** (`PUT /api/v1/photos/{id}/tags`, `GET /api/v1/photo-tags/suggestions`) pozwalają
opisać ujęcie i podpowiadają tagi już używane w studiu (`GalleryTagService`).

Historia zdjęć jest częścią dziennika audytu: akcje `PHOTO_ADDED` (waga niska)
i `PHOTO_DELETED` (waga wysoka) są zapisywane z informacją kto i kiedy.

---

## 2.5 Dokumenty, zgody i RODO

### Protokoły przyjęcia i wydania pojazdu

**Szablony** (`/api/v1/protocol-templates`) w dwóch formatach:

- **PDF** z formularzem AcroForm — pełny pipeline: automatyczne wypełnienie, podpis na tablecie, pieczętowanie;
- **HTML** z placeholderami `data-field` — wypełniany podstawieniem po stronie serwera, przeznaczony
  do podglądu i druku (podpis tabletowy wymaga PDF).

Szablon przechodzi **weryfikację pól wymaganych** (`ProtocolTemplateVerificationService`):
`PENDING → VERIFIED | REJECTED`. Odrzucony szablon nie wejdzie do obiegu wizytowego.

System dostarcza szablony domyślne (`protokol_przyjecia_pojazdu`, `protokol_wydania_pojazdu`,
`zgody_marketingowe`, `oswiadczenie_rodo`, `upowaznienie_nadawcy_sms`), a
`DefaultProtocolTemplateProvisioner` / `…BackfillRunner` / `…RefreshRunner` dbają, żeby każde studio
je miało i żeby odzyskało szablon check‑inu, gdyby go skasowało.

**Mapowanie pól** (`ProtocolFieldMapping`, `CrmDataResolver`) łączy pola formularza z danymi CRM.
`GET /api/v1/protocol-crm-data-keys` zwraca listę dostępnych kluczy danych.

**Reguły protokołów** (`ProtocolRule`, `/api/v1/protocol-rules`) decydują, który szablon i na jakim
etapie (`ProtocolStage`) ma zostać wygenerowany. `POST /api/visits/{id}/protocols/generate`
generuje komplet dokumentów dla wizyty; `POST …/protocols/{id}/visual-condition` dopisuje ocenę
stanu wizualnego (migracja V89).

### Podpis elektroniczny (eIDAS)

Moduł `signing` realizuje podpis odporny na zarzut „mój podpis skopiowano do innej umowy".
Pełny opis znajduje się w `docs/EIDAS_TABLET_SIGNING.md`; kluczowe mechanizmy:

1. **WYSIWYS** — przy kliknięciu „Poproś o podpis" backend liczy SHA‑256 nad dokładnymi bajtami
   wypełnionego PDF‑a z S3 i zapisuje go w `SignatureRequest.documentSha256`.
   Przy wydaniu dokumentu na tablet bajty są hashowane **ponownie** (nagłówek `X-Document-Sha256`) —
   jeśli obiekt w S3 zmienił się od utworzenia żądania, dokument nie zostanie wyświetlony.
   Przy submicie wymagana jest **potrójna zgodność**: hash z tabletu = hash żądania = hash bajtów
   pobranych ponownie z magazynu, porównywana stałoczasowo (`MessageDigest.isEqual`).
2. **Anty‑replay** — jednorazowy challenge w Redisie zużywany atomowo (`GETDEL`).
   Przechwycony pakiet nie da się odtworzyć.
3. **Podpis bez tła** — rysowany na w pełni przezroczystym canvasie, eksportowany jako PNG z kanałem alfa.
4. **RAM‑only** — obraz podpisu istnieje wyłącznie w pamięci; do S3 trafia **tylko zaplombowany PDF**.
   Tablet po wysyłce (sukces i błąd) zeruje bufor PDF, niszczy dokument pdf.js i czyści canvas.
5. **Karta Podpisu** (`AuditTrailPageGenerator`) — strona ze ścieżką audytu dołączana do dokumentu.
6. **Kwalifikowana pieczęć PAdES** (CMS/CAdES, BouncyCastle) + **znacznik czasu RFC 3161**.

**Parowanie tabletu**: pracownik generuje w ustawieniach 6‑cyfrowy kod (ważny 5 minut, jednorazowy),
tablet wpisuje go w `POST /api/tablet/pair` i zapisuje token. Parowanie nie wygasa z upływem czasu —
kończy je wyłącznie odłączenie urządzenia w ustawieniach (migracja V70).
Sesja podpisu żyje 15 minut. Cykl statusów żądania obejmuje m.in. `DISPLAYED` (dokument pokazany klientowi).

**Podpis na urządzeniu klienta**: `/api/public/signing` + link SMS‑em (`SMS_SIGNATURE_REQUEST`) —
wymaga zdolności `SIGNATURE_REMOTE_REQUEST`, czyli e‑podpisów **i** modułu komunikacji.

**Podpisy pracowników**: `/api/public/user-signature` + `UserSignatureLinkService` — pracownik
podpisuje się raz przez link onboardingowy, podpis trafia potem na dokumenty studia.

### Zgody i RODO

`ConsentDefinition` to zgoda **trwała**, podpisywana raz na klienta (w odróżnieniu od protokołu
generowanego per wizyta), ważna do odwołania lub do publikacji wersji wymagającej ponownego podpisu.

Kluczowe pole: `marketingChannels: Set<MarketingChannel>` (`EMAIL`, `SMS`) — deklaruje, których
kanałów zgoda dotyczy. **Najwyżej jedna aktywna zgoda studia może obejmować dany kanał.**

**Wersjonowanie**: `ConsentTemplate` z flagą `requiresResign`. `AddConsentVersionHandler` publikuje
nową wersję. Zgoda jest „ważna", gdy klient podpisał aktualny szablon **albo** dowolny starszy,
jeśli nowa wersja nie wymaga ponownego podpisu.

**Bramka wysyłki** (`MarketingConsentChecker.canSend`):
- studio nie ma żadnej aktywnej zgody obejmującej kanał → wysyłka dozwolona;
- ma co najmniej jedną → klient musi mieć ważną zgodę na przynajmniej jedną z nich;
- brak zgody → wysyłka zablokowana i zalogowana z kontekstem (WARN).

**Odwołanie zgody**: `RevokeConsentHandler` — `revoked_at` zamiast kasowania rekordu (ślad audytowy).
Podpisane zgody przechowywane są w S3 (`S3ConsentStorageService`);
migracja V90 usunęła z systemu niepodpisane dokumenty zgód, a V87 uzupełniła załączniki historyczne.

Studio dostaje domyślną zgodę marketingową automatycznie
(`DefaultMarketingConsentProvisioner`, `DefaultMarketingConsentBackfillRunner`).

**Retencja danych**: dane Instagrama są kasowane po 24 miesiącach (snapshoty) i 12 miesiącach
(insighty) — zadanie cron `0 0 4 1 * *`, wprost opisane w konfiguracji jako wymóg RODO.

### Dokumenty klienta i wizyty

`/api/v1/customers/{id}/documents` oraz `/api/visits/{id}/documents` — repozytorium plików
przypiętych do osoby i do zlecenia, ze wspólnym `DocumentStorageService` (S3).
Usuwanie dokumentów wymaga uprawnienia `VISITS_DELETE`.

---

## 2.6 Finanse, kasa i fakturowanie

### Dokumenty przychodowe i kosztowe

`FinancialDocument` (`/api/v1/finance/documents`) to rekord ewidencyjny CRM‑u — nie formalna faktura.
Wymiary dokumentu:

| Wymiar | Wartości |
|---|---|
| `DocumentType` | `RECEIPT` (PAR — Paragon), `INVOICE` (FAK — Faktura), `OTHER` (DOK — Dokument) |
| `DocumentDirection` | `INCOME` (Przychód), `EXPENSE` (Koszt) |
| `DocumentStatus` | `PAID`, `PENDING`, `OVERDUE` |
| `PaymentMethod` | `CASH`, `CARD`, `TRANSFER`, `BLIK_NA_NUMER`, `BLIK_TERMINAL`, `OTHER` |
| `DocumentSource` | `VISIT` (z wizyty), `MANUAL` (ręcznie) |

Dwie reguły wynikające z metody płatności: `TRANSFER` domyślnie tworzy dokument w statusie `PENDING`
(reszta — `PAID`), a tylko `CASH` wpływa na saldo kasy (`affectsCashRegister()`).

Wszystkie kwoty w groszach, z inwariantem `totalNet + totalVat == totalGross` sprawdzanym
w konstruktorze domeny. Usuwanie jest miękkie (`deletedAt`), z możliwością przywrócenia
(`POST /documents/{id}/restore`).

### Kasa

`CashRegister` — dokładnie jedna kasa na studio, tworzona automatycznie przy pierwszej operacji
gotówkowej. Saldo (`Money`, zawsze ≥ 0) jest wypadkową wpływów gotówkowych, wypływów gotówkowych
i korekt ręcznych. Pełna historia w `CashOperation`.

- `GET /api/v1/finance/cash` — stan kasy,
- `GET /api/v1/finance/cash/history` — historia operacji,
- `POST /api/v1/finance/cash/adjust` — wpłata / wypłata / korekta ręczna.

Operacje kasowe wymagają uprawnienia `FINANCE_MANAGE_CASH_REGISTER` i trafiają do dziennika audytu
w module `CASH_REGISTER`.

### Raporty finansowe

- `GET /api/v1/finance/summary` — podsumowanie przychodów i kosztów w okresie,
- `GET /api/v1/finance/payment-method-report` — rozbicie po metodach płatności,
- `GET /api/v1/finance/income-documents` — rejestr dokumentów przychodowych z flagą
  `hide_from_statistics` (migracja V77 — dokument można wyłączyć ze statystyk, nie kasując go),
- `/api/v1/finance/duplicates` — wykrywanie duplikatów dokumentów (`DocumentDuplicateDetector`,
  `DocumentDuplicateLink`, migracja V81),
- `/api/v1/cost-categories` — kategorie kosztów, przypisania kosztów do kategorii
  oraz **reguły automatycznego przypisania po dostawcy** (`SupplierAutoRuleEntity`).

### Fakturowanie i integracja z KSeF

Moduł KSeF działa **dwukierunkowo**.

**A. Faktury przychodowe (wystawiane w CRM → wysyłane do KSeF)**

`POST /api/v1/ksef/revenue/invoices`. `Fa3XmlBuilder` buduje XML w schemacie **FA(3)**,
`RevenueInvoiceNumberGenerator` nadaje numer, `KsefInvoiceSender` wysyła.

Obsługiwane stawki VAT z dokładnym odwzorowaniem schematu FA(3): 23 %, 8 %, 5 %, 0 % krajowe
(kod P_12 `"0 KR"` — schemat nie dopuszcza samego „0"), `zw`. Agregaty trafiają do właściwych
pól sekcji `Fa` (23 % → P_13_1/P_14_1, 8 % → P_13_2/P_14_2, 5 % → P_13_3/P_14_3,
0 % krajowe → P_13_6_1, zw → P_13_7). VAT liczony metodą „w stu" wg art. 106e ust. 7 ustawy o VAT,
zaokrąglenie HALF_UP.

**Cykl życia wysyłki** (`KsefRevenueStatus`):

```
PENDING → SENDING → SUBMITTED → ACCEPTED
              ↘ REJECTED        (błąd walidacji KSeF — trwały, wymaga poprawy danych)
    ↘ QUEUED_RETRY              (niedostępność KSeF — tryb offline24, dosyłka schedulerem)
```

Obok cyklu stoi `NOT_SENT`: faktura istnieje, ma numer i XML, ale użytkownik świadomie jej nie wysłał.
Scheduler jej nie dotknie — „nie wysłano" to decyzja, nie awaria. Faktury pobrane z KSeF
(`source = EXTERNAL`) są zawsze `ACCEPTED`, bo z definicji tam istnieją.
Ponowienie jest możliwe ze statusów `PENDING`, `QUEUED_RETRY`, `REJECTED`, `NOT_SENT` —
nigdy z `ACCEPTED` ani `SENDING` (idempotencja).

`KsefRevenueRetryScheduler` obsługuje tryb offline24 — dosyłkę najpóźniej następnego dnia roboczego.

Pozostałe operacje: **faktury korygujące** (`POST /invoices/{id}/corrections`, `RodzajFaktury = KOR`),
pobranie XML i **UPO**, kody QR (`KsefQrCodeUrlBuilder`, host `qr.ksef.mf.gov.pl`),
statusy płatności, notatki, statystyki roczne.

**Wykrywanie podwójnego fakturowania** (`RevenueDuplicateDetector`): para faktur CRM + EXTERNAL
o tym samym NIP nabywcy, tej samej kwocie brutto i bliskiej dacie. System **nigdy nie scala ani nie
ukrywa faktur automatycznie** — obie są prawnie wiążące. Decyduje użytkownik:
`CONFIRMED_DUPLICATE` (wykluczona ze statystyk, nadmiarową trzeba skorygować do zera) lub `DISMISSED`.

**B. Faktury kosztowe (pobierane z KSeF)**

`KsefSyncScheduler` co 15 minut (pierwsze uruchomienie 60 s po starcie) pobiera faktury zakupowe
(`KsefInvoiceXmlFetcher`, `KsefInvoiceXmlParser`, kursor synchronizacji `KsefSyncCursorEntity`).
Zaimportowane koszty można: wykluczyć ze statystyk / przywrócić, oznaczyć status płatności,
opatrzyć notatką. Można też dodać koszt ręcznie (`POST /api/v1/ksef/expenses`).

**Uwierzytelnienie i limity**: `/api/v1/ksef/credentials` (zapis, odczyt, usunięcie, weryfikacja
tokenu przez `KsefTokenVerifier` — migracja V60), sesja cache'owana (`KsefSessionCache`).
`MeteredKsefClient` + `KsefApiMetrics` pilnują wykorzystania okna limitów
(16 zapytań/min, 64/godz. — najostrzejsze udokumentowane limity API).

**C. Faktura przy zakończeniu wizyty**

`CompleteVisitInvoiceOrchestrator` obsługuje zamknięcie wizyty z fakturą:
pozycje faktury mogą różnić się od usług wizyty (użytkownik może zmienić nazwy i kwoty),
cena podawana jest w trybie netto **albo** brutto (jedno z dwóch pól, kwota użytkownika jest
źródłem prawdy). Jeśli suma pozycji faktury jest mniejsza niż kwota wizyty, system wymaga
metody płatności dla **dokumentu na resztę** (np. paragonu) i tworzy go automatycznie.
Flaga `sendToKsef` decyduje o natychmiastowej wysyłce; `null` oznacza domyślną odpowiedź studia
(`StudioSettings.ksefAutoSendDefault`, migracja V79).
Wizyty bezpłatne (suma zero) nie generują dokumentów przychodowych.

### Integracja z GUS (BIR)

`GET /api/v1/gus/company?nip=…` pobiera dane kontrahenta z rejestru REGON przez SOAP
(usługa BIR). Zwracane dane: NIP, REGON, nazwa, nazwa skrócona, forma prawna, adres
(ulica, nr budynku, nr lokalu, miasto, kod pocztowy, kraj), telefon, e‑mail, WWW, numer KRS,
data rozpoczęcia / zakończenia / zawieszenia działalności, typ podmiotu
(`LEGAL_PERSON` / `NATURAL_PERSON` / `LOCAL_UNIT_LEGAL` / `LOCAL_UNIT_NATURAL`) oraz status aktywności.

Warstwa odpornościowa:
- sesja GUS odświeżana co **55 minut** (wygasa po 60),
- **cache Redis 24 h** (dane firm rzadko się zmieniają),
- **retry** 3 próby z opóźnieniem początkowym 1 s,
- **circuit breaker**: próg błędów 50 %, okno 10 wywołań, przerwa 60 s,
- timeouty: połączenie 5 s, odczyt 15 s.

Dane z GUS zasilają kartotekę klienta firmowego, dane kontrahentów zleceń zbiorczych
i dane nabywcy na fakturze.

### Płatności

Przelewy24 (`/api/v1/payments/p24`, `CheckoutController`, `Przelewy24WebhookController`) obsługuje
zakup abonamentu i pakietów SMS. Bez skonfigurowanych credentiali system automatycznie przechodzi
w tryb mock (zamówienia realizowane natychmiast, bez wywołania P24).

---

## 2.7 Konta pracowników i moduł zadań

### Uwierzytelnienie

- `POST /api/v1/auth/signup` — rejestracja studia, z walidatorami e‑maila, hasła
  (`PasswordPolicy`), nazwy studia i akceptacji regulaminu.
- `POST /api/v1/auth/login` — logowanie; sesja stanowa w Redisie, ciasteczko HttpOnly/SameSite=Strict.
  `UserPrincipal` w sesji niesie `userId`, `studioId` i rolę.
- **Reset hasła** — token ważny 30 minut, cooldown 60 s między żądaniami dla tego samego adresu,
  link budowany na `FRONTEND_BASE_URL`.
- **PIN** (`/api/v1/pin`) — szybkie przełączanie użytkownika na współdzielonym stanowisku
  (`SwitchUserViaPinHandler`) bez pełnego wylogowania.

### Pracownicy

`/api/v1/employees` — dane kadrowe (imię, nazwisko, telefon, e‑mail) oddzielone od konta logowania.
Operacje na kontach: `ProvisionEmployeeAccountHandler` (założenie konta i wysłanie zaproszenia),
`ChangeEmployeeAccountPasswordHandler`, `BlockEmployeeAccountHandler`, `DeleteEmployeeAccountHandler`.
Osobno: urlopy i nieobecności (`/leaves` + kalendarz urlopowy) oraz ewidencja czasu pracy.

### Role i uprawnienia (RBAC)

Katalog uprawnień jest **zaszyty w kodzie** (`Permission.kt`) — administrator nie dodaje ani nie usuwa
pozycji, tylko włącza je w rolach niestandardowych. Katalog jest skonsolidowany do **25 uprawnień**
według zasady: checkbox istnieje tylko wtedy, gdy istnieje realna rola potrzebująca go bez sąsiednich.

Model to **graf zależności**: drzewo (dziecko wymaga całej ścieżki przodków) **plus jawne implikacje**
(`implies` — kody wymagane dodatkowo, także z innych modułów). Backend domyka zapisany zbiór do punktu
stałego, więc rola nigdy nie jest niespójna — „tworzenie rezerwacji bez podglądu klientów"
jest w tym modelu **niewyrażalne**.

Główny łańcuch modułu wizyt odwzorowuje przepływ recepcji:

```
VISITS_VIEW → CUSTOMERS_VIEW → VISITS_SERVICE_PRICES_VIEW → VISITS_CREATE
                                                                 ├── VISITS_DELETE
                                                                 ├── VISITS_MEDIA_DELETE
                                                                 └── CUSTOMERS_DELETE
```

Katalog uprawnień:

| Moduł | Uprawnienia |
|---|---|
| Wizyty i kalendarz | `VISITS_VIEW`, `CUSTOMERS_VIEW`, `VISITS_SERVICE_PRICES_VIEW`, `VISITS_CREATE`, `VISITS_DELETE`, `VISITS_MEDIA_DELETE`, `CUSTOMERS_DELETE`, `BATCH_ORDERS` |
| Finanse | `FINANCE_INVOICES`, `FINANCE_MANAGE_CASH_REGISTER`, `FINANCE_VIEW_REPORTS`, `FINANCE_EARNINGS_NOTIFICATIONS` |
| Pracownicy | `EMPLOYEES_MANAGE`, `EMPLOYEES_PAYROLL` |
| Komunikacja | `COMMUNICATION_SEND` |
| Marketing | `MARKETING_MANAGE` |
| Statystyki | `STATISTICS_VIEW` |
| Leady | `LEADS_MANAGE` |
| Zadania | `TASKS_VIEW`, `TASKS_MANAGE` |
| Audyt | `AUDIT_VIEW` |

Decyzje warte odnotowania:
- `BATCH_ORDERS` to **drugi korzeń bez rodzica** — stanowisko obsługi kontrahentów B2B nie potrzebuje
  kalendarza studia ani kartoteki klientów detalicznych, i odwrotnie.
- `FINANCE_EARNINGS_NOTIFICATIONS` jest osobnym korzeniem, a nie dzieckiem raportów: właściciel może
  chcieć pushy z kwotą bez oddawania komukolwiek raportów, a księgowa raportów bez budzika.
- `AUDIT_VIEW` nie może opierać się na uprawnieniu żadnego modułu, bo feed obejmuje zdarzenia
  kadrowo‑płacowe i bezpieczeństwa; właściciel omija sprawdzenie z definicji.
- Kalendarz i pojazdy **nie są** osobnymi obszarami uprawnień — zdarzenie kalendarza *jest* wizytą
  lub rezerwacją, a pojazdy jadą na uprawnieniach wizyt i klientów.
- Kody wycofane w restrukturyzacjach v4/v5 są mapowane przy odczycie (`legacyAliases`),
  bez migracji SQL. `EMPLOYEES_VIEW` celowo nie ma następcy.

Egzekwowanie: adnotacje `@RequiresPermission` / `@RequiresOwner` + `PermissionAuthorizationAspect`,
z cache'em migawek uprawnień (`PermissionSnapshotCache`). Właściciel (OWNER) omija sprawdzenia.

### Entitlementy — druga, niezależna bramka

Obok uprawnień działa warstwa abonamentowa (`@RequiresFeature`, `@RequiresCapability`):

- **Plany**: `BASIC` (Kalendarz, Wizyty, Klienci, Pojazdy, Dokumenty, Galeria) i `FULL` (wszystko).
- **Dodatki** (`AddOnKey`): Asystent AI przy obsłudze leadów, Monitoring konkurencji na Instagramie,
  Automatyzacja kontaktu (SMS i E‑mail), Kampanie marketingowe, Podpisy elektroniczne,
  Kontrola nad finansami, Statystyki.
- **Zdolności** (`CapabilityKey`) opisują reguły międzymodułowe, np. `SIGNATURE_REMOTE_REQUEST`
  (prośba o podpis na urządzeniu klienta) wymaga e‑podpisów **i** modułu komunikacji, bo link jedzie SMS‑em;
  `COMM_SMS_CREDITS` wymaga *któregokolwiek* z modułów zużywających SMS.

Zarządzanie planem: `PlanManagementService`, `ProrationService` (proporcjonalne rozliczenie),
`PlanDowngradeScheduler` (obniżenie planu na koniec okresu), `SubscriptionLifecycleScheduler`,
`SubscriptionReconciliationJob`.

### Moduł zadań

`Task` — tytuł, opis (`meta`), status wykonania, autor, wykonawca, znaczniki czasu utworzenia,
wykonania i usunięcia (usuwanie miękkie).

**Widoczność zadania** (`TaskVisibilityType`) — to jest mechanizm przypisania:

| Typ | Kto widzi |
|---|---|
| `ALL` | wszyscy w studiu |
| `USERS` | wskazane osoby (`visibleToUserIds`) |
| `ROLE` | wszyscy z określoną rolą (`visibleToRoleId`) |

`TaskVisibility.isVisible` to jedno źródło prawdy, wspólne dla listy zespołowej i widoku „moje zadania":
właściciel widzi wszystko → autor widzi swoje → dalej decyduje targetowanie.

Endpointy:
- `/api/v1/tasks` — lista, `GET /visibility-options` (kogo można wskazać), `GET /archive`
  (archiwum wykonanych), tworzenie, edycja (`PATCH`), usuwanie;
- `POST /api/v1/tasks/voice` — **utworzenie zadania z nagrania głosowego**
  (multipart audio → Whisper `whisper-1`, język polski → tytuł zadania);
- `/api/v1/my/tasks` — widok pracownika: moje zadania, `GET /summary` (licznik nieprzeczytanych),
  `POST /mark-read`, `PATCH /{taskId}/done`.

Nieprzeczytane zadania śledzi `TaskReadEntity` (per użytkownik), co zasila dzwonek powiadomień.

### Dziennik aktywności (audyt)

`/api/v1/audit` — jeden feed wszystkich zdarzeń w firmie: kto, co, kiedy i na jaką kwotę.
Zdarzenia mają moduł (`AuditModule`: Klienci, Pojazdy, Wizyty, Rezerwacje, Usługi, Leady, Protokoły,
Zgody, Połączenia przychodzące, Kolory rezerwacji, Studio, Użytkownicy, Finanse, Kasa, Pracownicy,
Zadania, Bezpieczeństwo, Door to Door…), akcję (`AuditAction` — CRUD, zmiany statusu, operacje
na zdjęciach, dokumentach, komentarzach, notatkach, usługach, protokołach) i **wagę**
(`LOW` / `NORMAL` / `HIGH` / `CRITICAL` — np. usunięcie wizyty jest `CRITICAL`).

Feed jest renderowany zdaniami w języku polskim (`AuditFeedRenderer`, np. „Zmieniono zakres usług"),
z paginacją kursorową (`AuditFeedCursorCodec`) i kontekstem doklejanym przez `AuditContextResolver`.
`AuditFieldCatalog` opisuje zmiany pól. Historia pojedynczego obiektu (wizyty, klienta, pojazdu)
korzysta z tego samego dziennika.

---

## 2.8 Zlecenia zbiorcze

Moduł B2B (`/api/batch-orders`) dla studiów obsługujących floty i podwykonawstwo.
Jest celowo odcięty od kartoteki detalicznej — całość stoi na jednym uprawnieniu `BATCH_ORDERS`.

**Kontrahenci** (`BatchContractor`): nazwa, NIP, adres, osoba kontaktowa, e‑mail, telefon, notatki,
flaga aktywności. CRUD pod `/contractors`.

**Wpisy zleceń** (`BatchOrderEntry`): data usługi, marka, model, numer rejestracyjny, VIN,
**lista pozycji usługowych** (nazwa, netto, brutto, stawka VAT), notatki, flaga zamknięcia
i powiązanie z historią zamknięcia. CRUD pod `/contractors/{id}/entries` i `/entries/{entryId}`.

**Własny katalog usług zbiorczych** (`/services`) — odrębny od cennika detalicznego,
z wyszukiwaniem (migracja V68).

**Zdjęcia wpisu** — `/entries/{id}/photos/upload-url` (presigned S3), lista, usuwanie.

**Odczyt VIN ze zdjęcia**: `POST /vin/extract` przyjmuje zdjęcie tabliczki/szyby,
`VinExtractionService` wysyła je do modelu multimodalnego, a wynik jest twardo walidowany —
po odfiltrowaniu znaków spoza `[A-HJ-NPR-Z0-9]` musi mieć dokładnie 17 znaków, inaczej zwracany jest `null`.

**Wyszukiwanie pojazdów**: `/vehicles/search` (po kartotece) i `/vehicles/search-entry`
(po historii wpisów zbiorczych) — podpowiadanie przy wpisywaniu kolejnych zleceń tej samej floty.

**Raport i zamknięcie miesiąca**:
- `GET /contractors/{id}/report` — raport za okres (generowany dokument),
- `POST /contractors/{id}/close-month` — zamknięcie okresu w jednym z dwóch trybów:
  `ALL` (wszystkie wpisy z zakresu dat) lub `NEW_ONLY` (tylko jeszcze niezamknięte);
  wynik: liczba wpisów, suma netto, suma brutto, opcjonalna wysyłka e‑maila do kontrahenta
  (szablon `EMAIL_BATCH_ORDER_CLOSE` ze zmiennymi `{{kontrahent}}`, `{{okres}}`, `{{kwota_brutto}}`,
  `{{liczba_wpisow}}`) z raportem w załączniku; adres można nadpisać jednorazowo,
- `GET /contractors/{id}/close-history` — historia zamknięć,
- `GET /close-history/{historyId}/snapshot` — **migawka raportu** dokładnie w postaci, w jakiej
  została wysłana (dowód rozliczenia).

---

## 2.9 Śledzenie konkurencji

Moduł monitoringu rynku obejmuje dwa obszary: **Instagram konkurencji** (wdrożony, aktywny)
oraz **trendy wyszukiwania** (kod przygotowany, poza aktywnym drzewem źródeł).

### Monitoring profili Instagram

**Dodawanie profili** (`/api/v1/instagram`): studio dodaje profil do obserwacji
(`AddInstagramProfileHandler`), profil przechodzi cykl `zatwierdzenie / odrzucenie`
(`ApproveInstagramProfileHandler`, `RejectInstagramProfileHandler`), a jeden z profili można oznaczyć
jako **własny** (`POST /{id}/mark-self`) — to on jest punktem odniesienia dla porównań.
`POST /resync-failed` ponawia pobranie danych dla profili z błędem, z cooldownem 10 minut
per studio (ochrona dziennego budżetu RapidAPI).

**Pobieranie danych**:
- **sync tygodniowy (głęboki)** — cron `0 0 3 * * SUN`, do 8 stron historii (backfill do 31 stron),
- **sync dzienny (lekki)** — cron `0 30 6 * * *`,
- limity: 4 zapytania/s, twardy budżet 2000 wywołań na dobę (`RapidApiCallGate`),
- dostawca przełączalny: `LOOTER` (`instagram-looter2`) lub `IG_SCRAPER5` (legacy).

Dane zapisywane są jako **snapshoty w czasie**: `InstagramPostSnapshot` (posty),
`InstagramProfileMetricsSnapshot` (metryki profilu), `InstagramProfileStatsWeekly` (statystyki tygodniowe).

**Analityka** (`/api/v1/instagram` — kontroler analityczny):

| Endpoint | Zawartość |
|---|---|
| `/overview` | przegląd; każda metryka jako `MetricTriple` = wartość + delta + benchmark (front nie pokazuje liczby bez kontekstu) |
| `/benchmark` | porównanie profilu własnego z obserwowanymi |
| `/benchmark/week-detail` | szczegóły tygodnia |
| `/pulse` | **Puls konkurencji** — co się wydarzyło w oknie tygodnia |
| `/content` | analiza treści |
| `/content/heatmap` | mapa cieplna publikacji (dzień × godzina) |
| `/hashtags` | analiza hashtagów |
| `/suggestions` | sugestie per profil |
| `/digest` | tygodniowy werdykt |

**Puls konkurencji** (`CompetitorPulseService`) liczony jest **w całości w kodzie, bez modelu AI** —
bez limitów i bez cache. Norma profilu wyznaczana jest z **26 tygodni** historii. Typy zdarzeń:
`YOUR_POST`, `YOUR_SILENCE`, `ACCELERATION`, `SLOWDOWN`, `STANDOUT_POST`, `NEW_TOPIC`,
`FOLLOWER_SPIKE`, `FOLLOWER_DROP`.

**Werdykt tygodnia** (`WeeklyDigestService`) — dokładnie **jeden wpis na profil**
(`DigestVerdict`: `SILENT`, `STANDOUT`, `ACCELERATED`, …), świadomie zamiast listy zdarzeń,
która przy jednym ruchliwym koncie zalewała ekran. Narrację może wygenerować LLM
(`instagram.report.ai.enabled=true`) z deterministycznym szablonem jako fallbackiem.

**Silnik insightów** (`InsightEngine`) — deterministyczne detektory z jawnymi progami, zamieniające
dane w zdania „co się stało → dlaczego to ważne → co możesz zrobić". Zabezpieczenia przed szumem:
deduplikacja po `(studio, dedup_key)`, **twardy limit 5 nowych insightów na studio na tydzień**
(kandydaci sortowani wg ważności), a hipotezy (np. „podejrzenie kupionych obserwujących")
zawsze oznaczone w treści jako przypuszczenie.

**Klasyfikacja tematów** (`TopicClassificationService`, `InstagramPostTopic`) pozwala śledzić,
o czym publikuje konkurencja i wykrywać nowe tematy.

### Generowanie postów przez AI

`/api/v1/instagram/ai/generate` — `InstagramPostGeneratorService` tworzy propozycję posta.
Mechanizm **few‑shot** oparty na wektorach: `InstagramPostIndexingService` osadza posty
(`text-embedding-3-small`, 1536 wymiarów) w pgvector (`instagram_post_vectors`, HNSW, cosine),
a `InstagramInspirationService` dobiera przykłady. Reakcje studia na posty
(`ReactToInstagramPostHandler`, `StudioInstagramPostReaction`) uczą system, co się podoba —
zmiana reakcji publikuje `InstagramPostReactionChangedEvent` i przeindeksowuje przykłady.
Endpointy diagnostyczne (`/ab-test`, `/negative-impact-test`, `/debug-generate`) są domyślnie wyłączone
(`instagram.ai.debug-endpoints.enabled=false`).

### Trendy wyszukiwania (Growth Engine)

Frontend ma gotowy moduł `growth-engine` (widok trendów, sezonowość, historia fraz, podział
na województwa) wołający `/trends/keywords`, `/trends/keywords/{k}/history`, `/trends/voivodeships/{k}`.
Odpowiadający mu kod backendu (`SearchVolumeClient`, `DataForSeoConfig`, `KeywordSyncScheduler`,
`TrendsReadController` — integracja **DataForSEO**) znajduje się w `src/main/resources/trends/`,
czyli **poza kompilowanym drzewem źródeł**. Funkcjonalnie: śledzenie wolumenu wyszukiwań fraz
detailingowych z podziałem na lokalizacje. **Status: przygotowane, nieaktywne w bieżącym buildzie.**

---

## 2.10 Pozostałe funkcjonalności

### Leady i lejek sprzedaży

`/api/v1/leads` — pełny moduł zapytań ofertowych.

**Źródła leada** (`LeadSource`): wątek e‑mail, formularz WWW (auto‑detekcja z poczty lub webhook),
połączenie telefoniczne, nagranie głosowe z aplikacji mobilnej, wpis ręczny.

**Kategoria zapytania** (`LeadCategory` — oś „o co pytają"): Powłoka ceramiczna, Folia PPF / oklejanie,
Korekta lakieru, Detailing wnętrza, Mycie i pielęgnacja, Pełny detailing, Inne.

**Powody utraty** (`LeadLostReason`) — zamknięty słownik, bo tylko taki da się agregować.
Kluczowe rozróżnienie: flaga `countsAsLoss` oddziela stracone pieniądze od zapytań, które nigdy
nie były nasze.

| Liczone jako strata | Nieliczone jako strata |
|---|---|
| Za drogo, Brak wolnego terminu, Klient przestał odpowiadać, Wybrał konkurencję, Za daleko od studia, Tylko sprawdzał cenę, Stan auta wyklucza usługę, Sprzedał albo zmienił auto, Inny powód | Sami odmówiliśmy, Poza zakresem usług, Odłożył decyzję na później, Spam / nie było zapytaniem |

Uzasadnienie w kodzie: wrzucenie odmów własnych do sumy strat kazałoby właścicielowi ścigać przychód,
którego świadomie nie chciał, i psułoby statystykę tym mocniej, im lepiej kwalifikuje leady.

**Pozostałe mechanizmy leadów**: wycena pozycjami usługowymi z ceną zamrażaną w chwili przypisania
(`LeadServiceItem`, `estimatedValue` w groszach), tagi z katalogiem (`LeadTagCatalog`, migracje V71/V74),
notatki (V85), **automatyczne rozpoznawanie pojazdu z korespondencji**
(`LeadVehicleExtractionService`, status `PENDING`/`DONE`, migracja V72 — w bazie ląduje wyłącznie
wartość ze słownika pojazdów, nigdy surowy tekst klienta), przypisanie opiekuna, alert o zastoju
(`stagnantAlertSentAt`), pomiar **czasu pierwszej odpowiedzi** (`firstResponseAt`, zasilany zdarzeniem
`CommOutboundSentEvent` przez `LeadFirstResponseListener`), stan konwersacji
(`LeadConversationStateService`) oraz analityka lejka (`/api/v1/leads/analytics`).

### Katalog usług i pakiety

`/api/v1/services` — cennik studia z walidacją nazwy, ceny i stawki VAT.
**Zasada „always‑new‑ID"**: edycja usługi archiwizuje starą wersję (`is_active = false`) i tworzy nowy
rekord, żeby historyczne wizyty nadal wskazywały na właściwe dane cenowe.
Osobno obsługiwane są **pakiety usług** (`CreatePackageHandler`, `ServicePackageItem`).

### Statystyki

`/api/v1/statistics`:
- `/overview` — przegląd,
- `/breakdown` — rozbicie przychodu i liczby wizyt,
- `/categories/{id}` i `/services/{id}` — statystyki kategorii i pojedynczej usługi,
- `/periods/{period}/visits` — wizyty w okresie,
- `/unassigned-services` — usługi nieprzypisane do żadnej kategorii (kontrola kompletności danych).

**Kategorie usług** (`/api/v1/service-categories`) to warstwa raportowa nakładana na cennik:
przypisywanie usług hurtowo i pojedynczo, `ManualServiceRegistry` obsługuje usługi wpisywane ręcznie
(spoza cennika), żeby i one trafiały do statystyk. Granulacja raportów: `Granularity`.

### Pulpit (dashboard)

`/api/v1/dashboard` — podsumowanie dnia: rezerwacje (`GetDashboardReservationSummaryHandler`),
przychód (`GetDashboardRevenueSummaryHandler`), wskaźniki operacyjne.
**Podpowiedzi** (`/api/v1/dashboard/hints`) sugerują niedokończone konfiguracje; użytkownik może je
odrzucić na stałe (`DashboardHintDismissalEntity`, migracja V97).
`WebSocketEventBridge` odświeża pulpit w czasie rzeczywistym.

### Powiadomienia push (PWA)

`/api/v1/push` + `/api/v1/pwa` (manifest). Web Push zgodny z RFC 8292 (VAPID, własna implementacja
`WebPushCrypto` / `WebPushSender`). Zastosowania:
- **click‑to‑call** — kliknięcie numeru w CRM wywołuje telefon pracownika,
- **powiadomienie o zarobku** po zamkniętej wizycie — wysyłane wyłącznie do osób z uprawnieniem
  `FINANCE_EARNINGS_NOTIFICATIONS`, i to sprawdzane **per odbiorca**, nie per wykonawca akcji.

Wysyłka jest best‑effort i nigdy nie rzuca wyjątkiem — awaria usługi push nie może zamienić
zamkniętej wizyty w błąd. Rotacja kluczy VAPID unieważnia wszystkie subskrypcje
(telefony muszą sparować się od nowa), więc zmienia się je tylko przy wycieku.

### Aplikacja mobilna pracownika

Zestaw ekranów uruchamianych na telefonie po zeskanowaniu QR lub z „skrótów mobilnych":
- `/m/upload` — zdjęcia i mapa uszkodzeń przy check‑inie,
- `/m/voice` — **notatki i leady głosowe** (`/api/mobile/voice`): nagranie → Whisper (`whisper-1`, PL)
  → lead albo notatka,
- `/m/sig/:token` — podpis dokumentu,
- `/call-device` — urządzenie do click‑to‑call.

Dostęp przez `MobileTokenService` (tokeny krótkożyjące), bez pełnego logowania.

### Ustawienia studia

Jeden ekran `/settings` z sekcjami: dane firmy, usługi, role i uprawnienia, zespół, kolory rezerwacji,
dokumenty i zgody, faktury (KSeF), karta wizyty, numeracja wizyt (`VisitNumberGenerator`,
konfiguracja z migracji V58/V59 — w tym losowa część numeru), etykiety skrzynki, formularze leadów,
kredyty SMS, tablety do podpisu, mój podpis, synchronizacja kontaktów (CardDAV), bezpieczeństwo.

### Konto demo

`/api/v1/demo` — `DemoAccountService` + `DemoDataInitializer` zakładają konto demonstracyjne
z wygenerowanymi danymi; `DemoCleanupJob` je sprząta.

### Zgłoszenie problemu

`/api/v1/support/report-problem` — zgłoszenie błędu z poziomu aplikacji, wysyłane e‑mailem
na adres z `REPORT_PROBLEM_EMAIL`.

### Bezpieczeństwo aplikacji

- `RateLimitFilter` — ograniczenie liczby żądań,
- `SecurityHeadersFilter` — nagłówki bezpieczeństwa,
- `CorrelationIdFilter` — identyfikator korelacji w logach,
- `TenantIsolationAuditService` — audyt naruszeń izolacji tenantów,
- `PiiMaskingModule` — maskowanie danych osobowych na granicy serializacji.

### Obserwowalność i analityka produktowa

**Warstwa techniczna** — Actuator + Micrometer + Prometheus: histogram `crm.api.request.duration`
z kubełkami SLO (50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2,5 s, 5 s, 10 s) i kwantylami p50/p95/p99,
`crm.api.response.size` (wykrywanie anomalii bezpieczeństwa), metryki KSeF, komunikacji, magazynu,
cyklu życia wizyt. Dashboardy Grafany: przegląd platformy, użycie, audyt API, KSeF.
Grafana łączy się z bazą kontem `grafana_ro` mającym `SELECT` wyłącznie na tabelach `metric_*`
i dwukolumnowym katalogu studiów — nie odczyta klienta, wizyty, faktury ani stack trace'a.

**Warstwa produktowa** (moduł `metrics`, migracje V65–V67) — analityka z przypisaniem do tenanta
i długim horyzontem:
- **czas pracy w systemie**: heartbeat co 60 s, pojedynczy heartbeat dopisuje najwyżej 90 s
  (laptop zamknięty na noc dopisze 90 s, nie 16 godzin), sesja zamykana po 300 s ciszy
  **wstecznie, na ostatnim heartbeacie**; sesje krótsze niż 30 s lub bez interakcji się nie liczą;
- **audyt API / martwe endpointy**: endpoint uznawany za martwy po 90 dniach, uśpiony po 30;
  raport jest oznaczany jako niewiarygodny, dopóki audyt nie zbiera danych przez 30 dni
  (raport kwartalny wygląda identycznie jak martwy po trzech dniach obserwacji);
- **śledzenie błędów**: fingerprint z 5 ramek stosu, limit 8000 znaków stack trace'a,
  rate limit błędów frontendu 20/min (`/api/v1/metrics/errors`);
- **kolejka zapisu**: pojemność 20 000, batch 500, flush co 5 s — ścieżka gorąca nigdy nie blokuje;
- **retencja**: zdarzenia 120 dni, sesje 400 dni, błędy 90 dni, użycie API 400 dni;
  agregaty dzienne zostają na stałe;
- **konsola platformy** `/api/internal/metrics/**` chroniona nagłówkiem `X-Platform-Key`;
  pusty klucz = konsola zamknięta (HTTP 503).

---

## Podsumowanie — mapa modułów

| # | Obszar | Główne moduły backendu |
|---|---|---|
| 1 | Rezerwacje i wizyty | `appointment`, `visit`, `calendar`, `calendarevent`, `checkin`, `visitcard`, `doortodoor`, `appointmentcolor`, `photosession` |
| 2 | Komunikacja | `smscampaigns`, `email`, `communication`, `campaigns`, `smscredits` |
| 3 | Skrzynka i kontakty | `comms`, `mailbox`, `inbound`, `carddav`, `leads` |
| 4 | Klienci, pojazdy, zdjęcia | `customer`, `vehicle`, `gallery`, `phototags` |
| 5 | Dokumenty, zgody, RODO | `protocol`, `signing`, `customer/consent`, `customer/documents` |
| 6 | Finanse i faktury | `finance`, `costs`, `ksef`, `ksef/revenue`, `gus`, `payments` |
| 7 | Pracownicy i zadania | `auth`, `user`, `employee`, `role`, `task`, `worktime`, `pin`, `subscription` |
| 8 | Zlecenia zbiorcze | `batchorder` |
| 9 | Śledzenie konkurencji | `instagram` (+ `resources/trends` — nieaktywne) |
| 10 | Pozostałe | `dashboard`, `statistics`, `audit`, `metrics`, `push`, `voice`, `studio`, `demo`, `support`, `observability`, `security`, `health`, `service` |
