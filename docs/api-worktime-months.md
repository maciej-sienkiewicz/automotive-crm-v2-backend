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
