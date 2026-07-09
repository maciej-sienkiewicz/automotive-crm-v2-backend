# Kontrakt API — Uprawnienia v2: drzewo + skonsolidowany katalog + dwa poziomy widoków

Dokument dla zespołu frontendu. Zastępuje `api-contract-pii-masking.md`.

## Model w jednym akapicie

Katalog uprawnień to **drzewo** (`GET /api/v1/roles/permissions` zwraca zagnieżdżone
`nodes[].children[]` — dziecko wymaga całej ścieżki przodków; edytor ról kaskadowo
zaznacza/odznacza wzdłuż gałęzi). Katalog został **skonsolidowany do 23 uprawnień**
(zasada: checkbox istnieje tylko, gdy istnieje realna rola potrzebująca go bez sąsiednich).
Dane osobowe działają **dwupoziomowo**: `CUSTOMERS_VIEW` jest jednocześnie uprawnieniem
„dane osobowe" — widoki warsztatowe (wizyty, kalendarz) działają bez niego z polami `@Pii`
zamaskowanymi `"***"` na granicy serializacji (nagłówek `X-Pii-Access: granted|masked`),
a widoki osobowe (baza klientów, dokumenty, faktury, komunikacja) wymagają go twardo — 403.

## 1. Katalog (drzewo, 23 pozycje)

```text
WIZYTY I KALENDARZ (feature: VISITS)
└─ VISITS_VIEW                     Podgląd wizyt i kalendarza (komentarze, notatki,
   │                               podgląd i dodawanie zdjęć w cenie; klient maskowany
   │                               bez CUSTOMERS_VIEW)
   ├─ VISITS_CREATE                Tworzenie i edycja wizyt oraz rezerwacji (z pojazdem;
   │                               implikuje CUSTOMERS_MANAGE, SERVICES_VIEW i podgląd cen)
   ├─ VISITS_CHANGE_STATUS         Zmiana statusu wizyty
   ├─ VISITS_DELETE                Usuwanie wizyty (destrukcyjne — celowo osobno)
   ├─ [Usługi] VISITS_SERVICE_PRICES_VIEW   Podgląd cen usług w wizycie
   │           └─ VISITS_SERVICE_PRICES_EDIT  Edycja cen (rabaty)
   ├─ [Multimedia] VISITS_MEDIA_DELETE      Usuwanie zdjęć (feature: GALLERY;
   │                               zdjęcia to materiał dowodowy — celowo osobno)
   └─ [Dokumenty] VISITS_DOCUMENTS_MANAGE   Dokumenty i protokoły: podgląd, generowanie,
                                   podpis (feature: DOCUMENTS; implikuje CUSTOMERS_VIEW)

KLIENCI I POJAZDY (feature: CUSTOMERS)
└─ CUSTOMERS_VIEW                  Podgląd klientów = pełne dane osobowe, pojazdy,
   │                               historia komunikacji
   ├─ CUSTOMERS_MANAGE             Dodawanie i edycja klientów
   └─ CUSTOMERS_DELETE             Usuwanie klientów i pojazdów

FINANSE (feature: FINANCE)
├─ FINANCE_INVOICES                Faktury: podgląd i wystawianie (implikuje CUSTOMERS_VIEW
│  │                               i VISITS_VIEW)
│  └─ FINANCE_MANAGE_CASH_REGISTER Zarządzanie kasą fiskalną
└─ FINANCE_VIEW_REPORTS            Podgląd raportów finansowych

PRACOWNICY (feature: EMPLOYEES)
├─ EMPLOYEES_MANAGE                Kadry + konta logowania
└─ EMPLOYEES_PAYROLL               Płace (podgląd i zarządzanie)

KOMUNIKACJA (feature: SMS_EMAIL)
└─ COMMUNICATION_SEND              Wysyłanie SMS i e-maili (implikuje CUSTOMERS_VIEW)

STATYSTYKI  └─ STATISTICS_VIEW
LEADY       └─ LEADS_MANAGE        Praca z leadami (lead = kolejka pracy)
ZADANIA     └─ TASKS_VIEW ── TASKS_MANAGE (tworzenie i przypisywanie)
USŁUGI (cennik) └─ SERVICES_VIEW ── SERVICES_MANAGE
```

Zniknęły moduły **CALENDAR** (event kalendarza JEST wizytą/rezerwacją), **VEHICLES**
(pojazd czyta się przez wizyty/klientów, zapisuje przez `VISITS_CREATE`, usuwa przez
`CUSTOMERS_DELETE`), **GALLERY** i **DOCUMENTS** jako osobne moduły (żyją w drzewie wizyt
z własnym `featureKey`).

### Implikacje między-modułowe (runtime, nie w drzewie)

Drzewo trzyma się jednego modułu; powiązania między modułami są doliczane przy
wyznaczaniu efektywnych uprawnień (`/auth/me` zwraca już rozwinięty zbiór):

| Uprawnienie | Implikuje |
|---|---|
| `VISITS_CREATE` | `CUSTOMERS_MANAGE` (+`CUSTOMERS_VIEW`), `SERVICES_VIEW`, `VISITS_SERVICE_PRICES_VIEW` |
| `VISITS_DOCUMENTS_MANAGE` | `CUSTOMERS_VIEW` (dokument zawiera pełne dane klienta) |
| `FINANCE_INVOICES` | `CUSTOMERS_VIEW`, `VISITS_VIEW` (faktura = dane kontrahenta, powstaje z wizyty) |
| `COMMUNICATION_SEND` | `CUSTOMERS_VIEW` (wysyłka na prawdziwy numer/adres) |
| dowolne FINANCE / STATISTICS | `SERVICES_VIEW` (raporty odwołują się do cennika) |

W edytorze ról checkboxy spoza drzewa **nie** są zaznaczane kaskadowo — implikacje są
doliczane serwerowo; UI może je pokazywać jako „w pakiecie" (informacyjnie).

### Kody usunięte

`POST/PUT /api/v1/roles` przyjmuje wyłącznie aktualne kody (`400` dla starych); zapisane
role są tłumaczone w locie, opcjonalny skrypt czyszczący: `migrate-role-permissions-v2.sql`.
Najważniejsze mapowania: `CUSTOMERS_VIEW_PERSONAL_DATA→CUSTOMERS_VIEW`,
`CUSTOMERS_CREATE/EDIT→CUSTOMERS_MANAGE`, `CALENDAR_VIEW→VISITS_VIEW`,
`CALENDAR_MANAGE→VISITS_CREATE`, `VEHICLES_VIEW→VISITS_VIEW`,
`VEHICLES_CREATE/EDIT→VISITS_CREATE`, `VEHICLES_DELETE→CUSTOMERS_DELETE`,
`VISITS_VIEW_DETAILS→VISITS_VIEW`, `VISITS_EDIT→VISITS_CREATE`,
`VISITS_SERVICES_VIEW→VISITS_VIEW`, `VISITS_SERVICES_MANAGE→VISITS_CREATE`,
`VISITS_COMMENTS_*/VISITS_NOTES_ADD/VISITS_MEDIA_VIEW/UPLOAD→VISITS_VIEW`,
`VISITS_DOCUMENTS_VIEW/CREATE/SIGN→VISITS_DOCUMENTS_MANAGE`,
`COMMUNICATION_VIEW_LOGS→CUSTOMERS_VIEW`, `COMMUNICATION_SEND_SMS/EMAIL→COMMUNICATION_SEND`,
`FINANCE_VIEW_INVOICES/CREATE_INVOICE→FINANCE_INVOICES`,
`EMPLOYEES_MANAGE_ACCOUNTS→EMPLOYEES_MANAGE`,
`EMPLOYEES_VIEW/MANAGE_PAYROLL→EMPLOYEES_PAYROLL`, `EMPLOYEES_VIEW→(usunięte)`,
`TASKS_ASSIGN→TASKS_MANAGE`, `LEADS_VIEW→LEADS_MANAGE`.

## 2. Dane osobowe: maskowanie vs 403

Maskowanie jest **centralne i nieobchodzalne** (pola `@Pii` maskowane w Jacksonie przy
serializacji, decyzja per request, nagłówek `X-Pii-Access`). Klucz decyzji to teraz
`CUSTOMERS_VIEW` (dawniej `CUSTOMERS_VIEW_PERSONAL_DATA`).

**Widoki warsztatowe** — dostępne bez `CUSTOMERS_VIEW`, pola osobowe `"***"`:
lista/szczegół wizyty, kalendarz, pojazdy, przypisany klient leada. Pojazd, usługi,
harmonogram, statusy i (przy uprawnieniu) ceny — zawsze widoczne. `companyName` nie jest
maskowane. Frontend blurruje po `X-Pii-Access: masked`.

**Widoki osobowe** — twardy gate (403 `{ "message": "Brak uprawnienia: …" }`), zero maskowania:

| Endpointy | Wymagane uprawnienie |
|---|---|
| `/api/v1/customers/**` (odczyty, dokumenty klienta — odczyt) | `CUSTOMERS_VIEW` |
| `/api/v1/customers` zapisy + dokumenty klienta (zapis/kasowanie) | `CUSTOMERS_MANAGE` |
| `POST /api/v1/customers/{id}/vehicles` | `VISITS_CREATE` |
| `GET .../communication` (wizyta i klient) | `CUSTOMERS_VIEW` |
| `/api/v1/finance/documents/**` | `FINANCE_INVOICES` |
| `/api/v1/finance/cash/**` | `FINANCE_MANAGE_CASH_REGISTER` |
| `/api/v1/finance/summary`, `/payment-method-report` | `FINANCE_VIEW_REPORTS` |
| protokoły wizyt + `/api/visits/{id}/documents`, `/api/documents/**` | `VISITS_DOCUMENTS_MANAGE` |
| `/api/v1/leads/**` | `LEADS_MANAGE` |

**Widoki operacyjne** — gate'y na akcje:

| Endpointy | Uprawnienie |
|---|---|
| `/api/visits/**` (odczyty, foto GET/POST) | `VISITS_VIEW` (klasa) |
| edycje wizyty (`services/`, `title`, `estimated-completion-date`), `/api/v1/appointments` zapisy, pojazdy zapisy | `VISITS_CREATE` |
| `confirm`, `cancel`, przejścia statusów (`mark-ready-for-pickup`, `complete`, `reject`, `archive`) | `VISITS_CHANGE_STATUS` |
| `DELETE /api/visits/{id}`, `DELETE /api/v1/appointments/{id}/permanent` | `VISITS_DELETE` |
| `DELETE .../photos/{photoId}` | `VISITS_MEDIA_DELETE` |
| `DELETE /api/v1/vehicles/{id}` | `CUSTOMERS_DELETE` |
| `/api/v1/services` odczyt / zapisy | `SERVICES_VIEW` / `SERVICES_MANAGE` |

Broadcasty WebSocket (wspólne topici studia) są zawsze maskowane
(`PiiAccessContext.withMasked`); tablet podpisów zawsze odmaskowany (klient potwierdza
własne dane). Właściciel studia omija wszystkie checki. `/auth/me` zwraca efektywną,
rozwiniętą listę uprawnień do budowy menu.
