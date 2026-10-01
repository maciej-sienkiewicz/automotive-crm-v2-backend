# Kontrakt API: listy miesięczne (karty czasu pracy + lista obecności)

Kontrakt front ↔ backend dla przebudowy zakładki „Listy miesięczne” (`/employees/worktime`).
Zmiana pola = zmiana w obu repozytoriach.

## Po co ta zmiana

Dziś ten sam miesiąc ma dwa niezależne „zatwierdzenia”: kartę czasu pracy (per pracownik,
na jego karcie, jednym kliknięciem) i listę obecności (PDF dla wielu osób, z podpisem).
Lista może powstać i zostać podpisana z niezłożonych kart i nie wie, gdy karta się zmieni.
Menedżer nie ma miejsca, w którym widzi, kto złożył, kto nie i co czeka na niego.

Nowy model: **jeden przepływ na miesiąc** — karty zbierane → karty zatwierdzane →
lista obecności podpisana. Lista powstaje wyłącznie z zatwierdzonych kart (wyjątki są
świadome i wypisane na dokumencie), a odblokowanie karty po podpisie unieważnia listę.

## Pojęcia

- `period`: `YYYY-MM`.
- **Dzień roboczy**: pon–pt, który nie jest świętem ustawowym (te same `PolishHolidays` co
  w urlopach, łącznie z Wigilią od 2025).
- **Norma** pracownika w miesiącu: `(dni robocze − dni roboczych urlopu/L4) × 480 min`.
- **Brakujący dzień**: dzień roboczy ≤ dziś (dla miesięcy przeszłych: wszystkie), bez wpisu,
  bez urlopu/L4.
- Statusy karty (bez zmian w bazie): `DRAFT`, `SUBMITTED`, `APPROVED`, `RETURNED`, plus
  wyliczany `NOT_STARTED` (brak wiersza okresu i brak wpisów).

```ts
type CardStatus = 'NOT_STARTED' | 'DRAFT' | 'SUBMITTED' | 'RETURNED' | 'APPROVED';
type MonthStage = 'COLLECTING' | 'REVIEWING' | 'READY_TO_SIGN' | 'SIGNED' | 'NEEDS_RESIGN';
// COLLECTING    – są karty niezłożone (NOT_STARTED/DRAFT/RETURNED) i żadna nie czeka na decyzję
// REVIEWING     – co najmniej jedna karta SUBMITTED
// READY_TO_SIGN – wszystkie karty APPROVED, brak ważnej podpisanej listy
// SIGNED        – istnieje podpisana lista, aktualna
// NEEDS_RESIGN  – podpisana lista jest nieaktualna (karta odblokowana lub zatwierdzona po podpisie)

interface MonthCardRow {
  userId: string;
  employeeId: string | null;
  name: string;
  status: CardStatus;
  totalMinutes: number;
  expectedMinutes: number;
  missingWorkingDays: number;
  overtimeMinutes: number;          // suma nadwyżek ponad 480 min/dzień
  leaveWorkingDays: number;         // dni robocze urlopu/L4 w miesiącu
  submittedAt: string | null;
  approvedAt: string | null;
  approvedByName: string | null;
  returnNote: string | null;        // tylko gdy status RETURNED
  canDecide: boolean;               // false np. dla własnej karty (zasada czterech oczu)
  remindedAt: string | null;        // ostatnie przypomnienie w tym miesiącu
}

interface MonthSheet {
  id: string;
  status: 'GENERATED' | 'APPROVED';
  outdated: boolean;                // true → stage NEEDS_RESIGN
  generatedAt: string;
  approvedAt: string | null;
  approvedByName: string | null;
  excludedNames: string[];          // osoby świadomie pominięte (niezatwierdzone karty)
}

interface MonthOverview {
  period: string;                   // "2026-09"
  label: string;                    // "Wrzesień 2026"
  workingDays: number;
  stage: MonthStage;
  counts: { total: number; notSubmitted: number; submitted: number; returned: number; approved: number };
  employees: MonthCardRow[];        // osoby z rolą liczącą czas pracy (aktywne w tym miesiącu), sort: nazwisko
  sheet: MonthSheet | null;         // najnowsza lista za ten miesiąc
  sheetHistory: MonthSheet[];       // wcześniejsze (nieaktualne) listy za ten miesiąc, najnowsze pierwsze
}

interface CardDay {
  date: string;                     // YYYY-MM-DD
  minutes: number | null;           // null = brak wpisu
  note: string | null;
  isWorkingDay: boolean;
  holidayName: string | null;
  leave: null | { type: string; label: string };   // np. { type: 'SICK', label: 'L4' }
  missing: boolean;
}

interface CardDetail extends MonthCardRow {
  period: string;
  label: string;
  days: CardDay[];                  // wszystkie dni miesiąca
  returnedAt: string | null;
  returnedByName: string | null;
}
```

## Menedżer — `/api/v1/worktime/team` (`EMPLOYEES_MANAGE`, właściciel zawsze)

| Metoda | Ścieżka | Body | Odpowiedź |
|---|---|---|---|
| GET | `/months/{period}` | — | `MonthOverview` |
| GET | `/months/{period}/cards/{userId}` | — | `CardDetail` |
| POST | `/{userId}/periods/{period}/approve` | — | `MonthCardRow` (istniejący endpoint, nowa odpowiedź). Tylko z `SUBMITTED` (z `RETURNED` → 409). |
| POST | `/{userId}/periods/{period}/return` | `{ note: string }` (wymagane, ≤ 1000) | `MonthCardRow`. Z `SUBMITTED` = zwrot do poprawy; z `APPROVED` = odblokowanie — jeśli karta jest na podpisanej liście, lista staje się `outdated`. |
| POST | `/months/{period}/approve` | `{ userIds: string[] }` | `{ approved: string[], skipped: { userId: string, reason: string }[] }` — zatwierdza z listy tylko `SUBMITTED`, na które wywołujący może zdecydować |
| POST | `/months/{period}/remind` | `{ userIds: string[] }` | `{ reminded: string[], skipped: { userId, reason }[] }` — push „Uzupełnij i złóż kartę czasu pracy za {miesiąc}”; ta sama osoba najwyżej raz na 12 h |
| POST | `/months/{period}/sheet` | `{ allowIncomplete: boolean }` | `MonthSheet` (201). Lista tylko z kart `APPROVED`. Gdy są niezatwierdzone i `allowIncomplete=false` → 409 `{ message, field: null, names: string[] }`. Przy `true` pominięci trafiają do `excludedNames` i do stopki PDF („Bez zatwierdzonej karty: …”). Niepodpisana lista tego miesiąca jest zastępowana (usuwana); podpisana zostaje w `sheetHistory`. |
| GET | `/pending-count` | — | `{ submittedCards: number, sheetsToSign: number }` — karty `SUBMITTED` (na które wywołujący może zdecydować) ze wszystkich miesięcy + miesiące w `READY_TO_SIGN`/`NEEDS_RESIGN` lub z niepodpisaną listą |

Podpisywanie listy bez zmian (istniejące: `GET /attendance-sheet/{id}/file`,
`POST /attendance-sheet/{id}/approve`, `GET /attendance-sheet/signing-options`,
`POST|GET|DELETE /attendance-sheet/{id}/signature-request(s)`). Stary
`POST /attendance-sheet {period, employeeIds}` zostaje dla zgodności, ale front go już nie używa.

## Pracownik — `/api/v1/my/worktime`

- `GET /periods/{period}` dostaje dodatkowe pola: `days: CardDay[]` (jak wyżej),
  `expectedMinutes`, `missingWorkingDays`, `returnNote` (tylko gdy `RETURNED`).
- **Karta `SUBMITTED` jest tylko do odczytu**: `PUT/DELETE /entries/{date}`, `POST /fill-month`,
  `POST /today` → 409 „Karta za {miesiąc} czeka na decyzję przełożonego. Jeśli trzeba coś
  poprawić, poproś o zwrot.” (dotąd blokował tylko `APPROVED`).
- `POST /periods/{period}/fill-month` pomija święta i dni urlopu/L4.
- `POST /periods/{period}/submit` — bez zmian w regułach (braki nie blokują; front ostrzega).
  Notatka zwrotu jest czyszczona przy zatwierdzeniu.

## Powiadomienia i Tablica

- Push do pracownika: `WORKTIME_CARD_RETURNED` (z notatką, url `/worktime?period=YYYY-MM`),
  `WORKTIME_CARD_APPROVED` (url j.w.), `WORKTIME_CARD_REMINDER` (url j.w.).
- Push do `EMPLOYEES_MANAGE` (bez składającego): `WORKTIME_CARD_SUBMITTED`
  (url `/employees/worktime?period=YYYY-MM`).
- Podpowiedź na Tablicy `WORKTIME_CARDS_PENDING` dla właściciela i `EMPLOYEES_MANAGE`:
  „1 karta czasu pracy czeka na zatwierdzenie” / „2–4 karty … czekają” / „5+ kart … czeka”,
  akcja NAVIGATE „Przejrzyj” → `/employees/worktime?period={najstarszy miesiąc z kartą SUBMITTED}`.
- `WORKTIME_MISSING` widzą odtąd także osoby z `EMPLOYEES_MANAGE`, nie tylko właściciel.

## Lista obecności (PDF)

- Powstaje wyłącznie z kart `APPROVED`; kolumny pominiętych osób nie są drukowane, ich
  nazwiska trafiają do stopki.
- Wiersz „RAZEM” sumuje tylko wydrukowane godziny (godziny wpisane w dzień urlopu/L4 nie są
  liczone, bo komórka pokazuje „URLOP”/„L4”).

## Doprecyzowania z implementacji backendu

Nazwy endpointów, pól i typów są dokładnie jak wyżej. Poniżej to, czego kontrakt nie
rozstrzygał, i jak rozstrzyga to backend.

### Kto jest na liście miesiąca

- Konta z rolą liczącą czas pracy (`trackWorkTime`), aktywne, bez właścicieli, **założone
  przed końcem miesiąca** (konto założone w październiku nie „brakuje" we wrześniu),
- plus każdy (poza właścicielem), kto za ten miesiąc ma wiersz karty albo wpisy — także
  gdy dziś czasu już nie liczy lub konto jest nieaktywne.
- Sortowanie: nazwisko, potem imię (porządek polski). `name` = imię i nazwisko z rekordu
  pracownika, a gdy go nie ma — z konta; `employeeId = null` dla konta bez rekordu.

### Statusy, liczniki, etap

- `NOT_STARTED` = brak wpisów **i** karta nigdy niezłożona (wiersz `DRAFT` może istnieć —
  zakłada go np. przypomnienie albo usunięcie wszystkich wpisów).
- `counts.notSubmitted` = `NOT_STARTED` + `DRAFT`; `RETURNED` liczy się tylko w `returned`,
  więc liczniki sumują się do `total`.
- Kolejność rozstrzygania `stage`: (1) jest `SUBMITTED` → `REVIEWING`; (2) najnowsza lista
  podpisana (`APPROVED`) i `outdated` → `NEEDS_RESIGN`; (3) najnowsza lista podpisana →
  `SIGNED` (także gdy ktoś był świadomie pominięty); (4) jest karta niezłożona albo nie ma
  żadnej osoby → `COLLECTING`; (5) inaczej `READY_TO_SIGN`.
- `outdated` może dotyczyć także listy **niepodpisanej** (patrz niżej) — wtedy etap nie jest
  `NEEDS_RESIGN`, tylko wynika z kart (zwykle `READY_TO_SIGN`), a listę trzeba wygenerować
  ponownie.
- `canDecide` = karta nie jest kartą wywołującego **i** status to `SUBMITTED` (zatwierdź /
  zwróć) albo `APPROVED` (odblokuj). Dla pozostałych statusów `false`.

### Pola wiersza i karty

- Daty (`submittedAt`, `approvedAt`, `remindedAt`, `returnedAt`, `generatedAt`) to ISO-8601
  w UTC (`2026-09-30T10:00:00Z`).
- `submittedAt` — ostatnie złożenie (zostaje też przy `RETURNED`/`APPROVED`);
  `approvedAt`/`approvedByName` — tylko gdy status `APPROVED`; `remindedAt` — ostatnie
  przypomnienie (bez względu na status).
- `totalMinutes` i `overtimeMinutes` liczą wszystkie wpisy karty (także wpis w dzień urlopu);
  tylko RAZEM w PDF pomija godziny spod napisu URLOP/L4.
- `CardDay.leave` jest ustawione **tylko w dni robocze** (jak w PDF); `label`: `SICK` → „L4",
  `ANNUAL` → „Urlop", `UNPAID` → „Urlop bezpłatny", `SPECIAL` → „Urlop okolicznościowy",
  `PARENTAL` → „Urlop rodzicielski", `CARE` → „Opieka nad dzieckiem".
- „Dziś" (braki, `POST /today`) liczone w strefie `Europe/Warsaw`.
- `GET /months/{period}/cards/{userId}` dla konta spoza listy, ale z tego studia (np. bez
  roli liczącej czas) zwraca pustą kartę `NOT_STARTED`; konto z innego studia albo
  właściciel → 404.

### Decyzje

- `approve`: z `DRAFT`, `RETURNED` i `APPROVED` → **409** (`ErrorResponse`, polski
  komunikat, np. „Karta jest zwrócona do poprawy. Zatwierdzić można ją dopiero po
  ponownym złożeniu przez pracownika."). Własna karta → 403, karta nieistniejąca → 404.
- `return`: body opcjonalne, ale `note` wymagana — pusta/brak → **400** `ValidationException`
  z `field: "note"`; dłuższa niż 1000 znaków → 400. Z `DRAFT`/`RETURNED` → 409; własna → 403.
  Notatka jest przycinana (trim).
- Lista nieaktualna (`outdated`) powstaje przy: odblokowaniu karty (`APPROVED` → `RETURNED`),
  która jest na liście, i przy zatwierdzeniu karty, której na liście nie ma. Dotyczy każdej
  jeszcze aktualnej listy tego miesiąca — podpisanej **i niepodpisanej**. Niepodpisanej
  nieaktualnej listy nie da się już zatwierdzić ani wysłać do podpisu (409 „Ta lista
  obecności jest nieaktualna: karta czasu pracy zmieniła się po jej wygenerowaniu.
  Wygeneruj listę ponownie."), a czekające prośby o podpis są anulowane.
- `POST /months/{period}/approve` — `skipped[].reason` to zdania po polsku: „Własnej karty
  nie zatwierdzasz (zasada czterech oczu).", „Karta nie została złożona.", „Karta jest już
  zatwierdzona.", „Karta jest zwrócona do poprawy.", „Nieprawidłowy identyfikator osoby.".
- `POST /months/{period}/remind` — `skipped[].reason`: „Ta osoba nie prowadzi karty czasu
  pracy w tym miesiącu.", „To Twoja własna karta.", „Konto tej osoby jest nieaktywne.",
  „Karta jest już złożona.", „Karta jest już zatwierdzona.", „Przypomnienie wysłano mniej
  niż 12 godzin temu.", „Nieprawidłowy identyfikator osoby.". `reminded` znaczy „zapisane
  i wysłane do push" — dostarczenie jest best-effort (osoba bez sparowanego telefonu też
  trafia do `reminded`). Przypomnienie nie zmienia statusu karty (`NOT_STARTED` zostaje).

### Lista obecności

- 409 przy niepełnym miesiącu:
  `{ error: "Niezatwierdzone karty", message, timestamp, code: "WORKTIME_CARDS_NOT_APPROVED", field: null, names: string[] }`
  (`names` po nazwisku). Gdy nie ma **żadnej** zatwierdzonej karty → 409 zwykłym
  `ErrorResponse` (także przy `allowIncomplete: true`).
- Body `POST /months/{period}/sheet` jest opcjonalne (brak = `allowIncomplete: false`).
- Zastępowane są wszystkie niepodpisane listy miesiąca (także utworzone starym
  `POST /attendance-sheet`); usuwane dopiero po zapisaniu nowej. `sheetHistory` = wszystkie
  starsze listy miesiąca, najnowsze pierwsze.
- `MonthSheet.status` to `"GENERATED" | "APPROVED"`, `generatedAt` = chwila wygenerowania.
- Na liście z kart kolumną jest **konto** (także bez rekordu pracownika). Stary
  `AttendanceSheetResponse.employeeCount` dla takiej listy liczy karty.
- PDF: linia „Bez zatwierdzonej karty: …" stoi nad linią stanu kart i łamie się na kolejne
  linie zamiast ucinać nazwiska. RAZEM: godziny w sobotę/niedzielę w środku urlopu są
  wydrukowane (weekend nie dostaje napisu URLOP), więc się liczą.

### Pracownik

- Karta `APPROVED` nadal blokuje zmiany jak dotąd (**403**, bez zmian); `SUBMITTED` → **409**
  z komunikatem z kontraktu, miesiąc małą literą: „Karta za wrzesień 2026 czeka na decyzję
  przełożonego. Jeśli trzeba coś poprawić, poproś o zwrot."
- `returnNote` tylko przy `RETURNED` — także w `GET /periods` (lista) i w
  `GET /worktime/team/{userId}/periods/{period}`, który zwraca te same nowe pola co karta
  pracownika.
- `fill-month` wpisuje 8:00 tylko w dni robocze bez urlopu/L4 i bez istniejącego wpisu
  (jak dotąd także w dni przyszłe miesiąca).
- Ponowne złożenie po zwrocie zapisuje w dzienniku zdarzeń `RETURNED → SUBMITTED`.

### Powiadomienia i Tablica

- `WORKTIME_CARD_SUBMITTED`: tytuł „Karta czasu pracy: {imię nazwisko}", treść „Złożona za
  {miesiąc}. Czeka na Twoją decyzję.", odbiorcy: `EMPLOYEES_MANAGE` (właściciel zawsze),
  bez składającego.
- `WORKTIME_CARD_RETURNED`: „Karta czasu pracy do poprawy" / „Karta za {miesiąc} wróciła do
  poprawy ({kto}). Uwagi: {notatka, do 180 znaków}".
- `WORKTIME_CARD_APPROVED`: „Karta czasu pracy zatwierdzona" / „Karta za {miesiąc} jest
  zatwierdzona. Decyzja: {kto}."
- `WORKTIME_CARD_REMINDER`: „Karta czasu pracy" / „Uzupełnij i złóż kartę czasu pracy za
  {miesiąc}."
- Tag push-a: `worktime-card-{period}` u pracownika (nowsza wiadomość o tej samej karcie
  zastępuje starszą), `worktime-card-{userId}-{period}` u menedżerów.
- `WORKTIME_CARDS_PENDING`: tekst bez kropki na końcu, dokładnie jak w kontrakcie; klucz
  `WORKTIME_CARDS_PENDING_{epochSecond najświeższego złożenia}` (nowa karta odzywa się mimo
  zamknięcia podpowiedzi). Własna karta się nie liczy.
- `WORKTIME_MISSING` dla `EMPLOYEES_MANAGE`: bez własnej karty menedżera. `WORKTIME_UNUSED`
  („Wyłącz funkcję") zostaje tylko dla właściciela — menedżer w tej sytuacji dostaje
  `WORKTIME_MISSING` z nazwiskami.
- `GET /pending-count.sheetsToSign` liczy miesiące do bieżącego włącznie (przyszłe pomija).
