> **UWAGA — dokument historyczny (v3).** Katalog przeszedł restrukturyzacje v4/v5 —
> część opisanych tu kodów (`VISITS_CHANGE_STATUS`, `CUSTOMERS_MANAGE`,
> `VISITS_DOCUMENTS_MANAGE`, `VISITS_SERVICE_PRICES_EDIT`) istnieje już tylko jako aliasy
> legacy. **Źródłem prawdy jest kod:** `role/domain/Permission.kt` (katalog),
> `role/domain/PermissionHierarchy.kt` (graf zależności) oraz
> `GET /api/v1/roles/permissions` (kontrakt runtime). Zasady modelu (graf, domykanie,
> dwa poziomy widoków PII) pozostają aktualne.

# Kontrakt API — Uprawnienia v3: graf zależności + skonsolidowany katalog + dwa poziomy widoków

Dokument dla zespołu frontendu. Zastępuje `api-contract-pii-masking.md`.

## Model w jednym akapicie

Katalog uprawnień to **graf zależności**: drzewo (`GET /api/v1/roles/permissions` zwraca
zagnieżdżone `nodes[].children[]` — dziecko wymaga całej ścieżki przodków) **plus jawne
implikacje** (`nodes[].implies[]` — kody wymagane dodatkowo, także z innych gałęzi
i modułów). Edytor ról kaskadowo zaznacza/odznacza wzdłuż obu rodzajów krawędzi, a backend
domyka zapisany zbiór do punktu stałego — rola nigdy nie jest niespójna (np. „tworzenie
rezerwacji bez podglądu klientów" jest niewyrażalne). Katalog jest **skonsolidowany do
25 uprawnień** (zasada: checkbox istnieje tylko, gdy istnieje realna rola potrzebująca go
bez sąsiednich). Dane osobowe działają **dwupoziomowo**: `CUSTOMERS_VIEW` jest jednocześnie
uprawnieniem „dane osobowe" — widoki warsztatowe (wizyty, kalendarz) działają bez niego
z polami `@Pii` zamaskowanymi `"***"` na granicy serializacji (nagłówek
`X-Pii-Access: granted|masked`), a widoki osobowe (baza klientów, dokumenty, faktury,
komunikacja) wymagają go twardo — 403.

## 1. Katalog (drzewo + implikacje)

Moduł **KLIENCI I POJAZDY zniknął jako osobna karta** — jego uprawnienia żyją teraz jako
sekcja „Klienci i pojazdy" wewnątrz „Wizyty i kalendarz" (baza klientów istnieje w tym
produkcie po to, by obsługiwać wizyty). Zachowują własną bramkę subskrypcyjną
(`featureKey: CUSTOMERS` na węzłach).

```text
WIZYTY I KALENDARZ (feature: VISITS)
├─ VISITS_VIEW                     Podgląd wizyt i kalendarza (komentarze, notatki,
│  │                               podgląd i dodawanie zdjęć w cenie; klient maskowany
│  │                               bez CUSTOMERS_VIEW)
│  ├─ VISITS_CREATE                Tworzenie i edycja wizyt oraz rezerwacji
│  │                               implies: CUSTOMERS_MANAGE, VISITS_SERVICE_PRICES_EDIT,
│  │                                        VISITS_DOCUMENTS_MANAGE, SERVICES_VIEW
│  ├─ VISITS_CHANGE_STATUS         Zmiana statusu wizyty
│  ├─ VISITS_DELETE                Usuwanie wizyty (destrukcyjne — celowo osobno)
│  ├─ [Usługi] VISITS_SERVICE_PRICES_VIEW   Podgląd cen usług w wizycie
│  │           └─ VISITS_SERVICE_PRICES_EDIT  Edycja cen (rabaty)
│  ├─ [Multimedia] VISITS_MEDIA_DELETE      Usuwanie zdjęć (feature: GALLERY;
│  │                               zdjęcia to materiał dowodowy — celowo osobno)
│  └─ [Dokumenty] VISITS_DOCUMENTS_MANAGE   Dokumenty i protokoły: podgląd, generowanie,
│                                  podpis (feature: DOCUMENTS; implies: CUSTOMERS_VIEW)
└─ [Klienci i pojazdy] CUSTOMERS_VIEW   Podgląd klientów = pełne dane osobowe, pojazdy,
   │                               historia komunikacji (feature: CUSTOMERS; osobny
   │                               korzeń — fakturowanie/komunikacja wymagają danych
   │                               klienta bez wciągania kalendarza)
   ├─ CUSTOMERS_MANAGE             Dodawanie i edycja klientów (feature: CUSTOMERS)
   └─ CUSTOMERS_DELETE             Usuwanie klientów i pojazdów (feature: CUSTOMERS)

FINANSE (feature: FINANCE)
├─ FINANCE_INVOICES                Faktury: podgląd i wystawianie
│  │                               implies: CUSTOMERS_VIEW, VISITS_VIEW, SERVICES_VIEW
│  └─ FINANCE_MANAGE_CASH_REGISTER Zarządzanie kasą fiskalną
└─ FINANCE_VIEW_REPORTS            Podgląd raportów finansowych (implies: SERVICES_VIEW)

PRACOWNICY
├─ EMPLOYEES_MANAGE                Kadry + konta logowania
└─ EMPLOYEES_PAYROLL               Płace (podgląd i zarządzanie)

KOMUNIKACJA (feature: SMS_EMAIL)
└─ COMMUNICATION_SEND              Wysyłanie SMS i e-maili (implies: CUSTOMERS_VIEW)

MARKETING (feature: CAMPAIGNS) └─ MARKETING_MANAGE   Marketing i social media
STATYSTYKI (feature: STATISTICS) └─ STATISTICS_VIEW  (implies: SERVICES_VIEW)
LEADY       └─ LEADS_MANAGE        Praca z leadami (lead = kolejka pracy)
ZADANIA     └─ TASKS_VIEW ── TASKS_MANAGE (tworzenie i przypisywanie)
USŁUGI (cennik) └─ SERVICES_VIEW ── SERVICES_MANAGE
HISTORIA AKTYWNOŚCI └─ AUDIT_VIEW  Podgląd historii aktywności firmy
```

Zniknęły moduły **CUSTOMERS** (sekcja w wizytach, patrz wyżej), **CALENDAR** (event
kalendarza JEST wizytą/rezerwacją), **VEHICLES** (pojazd czyta się przez wizyty/klientów,
zapisuje przez `VISITS_CREATE`, usuwa przez `CUSTOMERS_DELETE`), **GALLERY** i **DOCUMENTS**
jako osobne moduły (żyją w drzewie wizyt z własnym `featureKey`).

### Implikacje (`implies[]`) — teraz część katalogu, nie runtime

Każdy węzeł katalogu niesie pole `implies: string[]` — kody wymagane dodatkowo poza
łańcuchem przodków (mogą wskazywać inną gałąź lub inny moduł). Edytor ról **kaskadowo
zaznacza** implikowane uprawnienia (wraz z ich przodkami i dalszymi implikacjami)
i **odznacza zależne** przy odznaczaniu wymaganego. Backend i tak domyka zbiór przy
zapisie i odczycie (`PermissionHierarchy.close`), więc role zapisane przed tą zmianą
otrzymują implikacje automatycznie przy odczycie — bez migracji SQL.

| Uprawnienie | implies |
|---|---|
| `VISITS_CREATE` | `CUSTOMERS_MANAGE` (+`CUSTOMERS_VIEW`), `VISITS_SERVICE_PRICES_EDIT` (+`VIEW`), `VISITS_DOCUMENTS_MANAGE`, `SERVICES_VIEW` — tworzenie rezerwacji to jeden przepływ recepcji: dane klienta, wycena z rabatami, dokumenty/protokoły, cennik |
| `VISITS_DOCUMENTS_MANAGE` | `CUSTOMERS_VIEW` (dokument zawiera pełne dane klienta) |
| `FINANCE_INVOICES` | `CUSTOMERS_VIEW`, `VISITS_VIEW`, `SERVICES_VIEW` (faktura = dane kontrahenta, powstaje z wizyty, odwołuje się do cennika) |
| `FINANCE_VIEW_REPORTS` | `SERVICES_VIEW` |
| `COMMUNICATION_SEND` | `CUSTOMERS_VIEW` (wysyłka na prawdziwy numer/adres) |
| `STATISTICS_VIEW` | `SERVICES_VIEW` |

`GET /api/v1/auth/me` nadal zwraca efektywny (domknięty i przefiltrowany przez
subskrypcję) zbiór — dla frontu nic się nie zmienia poza tym, że rola widziana
w edytorze pokrywa się 1:1 z efektywnymi uprawnieniami.

### Kody usunięte

`POST/PUT /api/v1/roles` przyjmuje wyłącznie aktualne kody (`400` dla starych); zapisane
role są tłumaczone w locie.
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
