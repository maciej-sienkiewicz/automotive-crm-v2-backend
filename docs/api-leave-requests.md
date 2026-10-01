# Kontrakt API: wnioski urlopowe (LeaveRequest)

Źródło projektu: `docs/projekt_modul_pracownicy_wnioski_urlopowe.html` (PRJ/2026/09/01).
Ten plik jest kontraktem front ↔ backend. Zmiana pola = zmiana w obu repozytoriach.

Zakres tego wydania: samoobsługa pracownika, rozpatrywanie z podpisem, odwołanie
zatwierdzonego. **Poza zakresem** (PR 4 / faza 2): wniosek w imieniu pracownika
i podpis zdalny (tablet/SMS, `AWAITING_EMPLOYEE_SIGNATURE`), wymiar urlopu.

## Zasady

- Każdy wniosek nosi **dwa podpisy**: pracownika (przy złożeniu) i osoby rozpatrującej
  (przy decyzji — także odmownej). Bez obrazu podpisu (albo `useSavedSignature` u
  rozpatrującego) decyzja/złożenie zwraca 400.
- WYSIWYS: klient podpisuje dokładnie bajty PDF, których SHA-256 dostał z backendu;
  każda sesja podpisu ma jednorazowy `challenge`. Niezgodny hash lub zużyty challenge → 409.
- Rozpatruje: właściciel studia albo osoba z `EMPLOYEES_LEAVES_APPROVE`. Nikt nie
  rozpatruje własnego wniosku (403). Uprawnienie sprawdzane ponownie w chwili decyzji.
- Dwie decyzje naraz: wygrywa pierwsza, druga dostaje 409.
- Daty: `YYYY-MM-DD`, czasy: ISO-8601 z offsetem.
- Obrazy podpisu: PNG w base64 **bez** prefiksu `data:` (jak `public-signing`).

## Typy

```ts
type LeaveType = 'ANNUAL' | 'UNPAID' | 'SPECIAL' | 'PARENTAL' | 'CARE'; // SICK nie jest wnioskiem
type LeaveRequestStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'CANCELLED' | 'EXPIRED';
type SignatureMethod = 'DEVICE_DRAWN' | 'SAVED_SIGNATURE' | 'IN_PERSON';
type LeaveRequestOrigin = 'SELF_SERVICE' | 'ON_BEHALF';

interface LeaveRequestSummary {
  id: string;
  number: string;                 // "WU/2026/0012"
  employeeId: string;
  employeeName: string;
  leaveType: LeaveType;
  onDemand: boolean;              // tylko przy ANNUAL
  startDate: string;
  endDate: string;
  workingDays: number;
  status: LeaveRequestStatus;
  reason: string | null;
  origin: LeaveRequestOrigin;
  createdByName: string | null;   // kto wprowadził wniosek (przy ON_BEHALF: administrator)
  createdAt: string;
  employeeSignedAt: string | null;
  decidedAt: string | null;
  decidedByName: string | null;
  decisionNote: string | null;    // uzasadnienie decyzji (wymagane przy odmowie)
  cancelReason: string | null;
}

interface OverlappingAbsence {
  employeeId: string;
  employeeName: string;
  startDate: string;
  endDate: string;
  kind: 'LEAVE' | 'PENDING_REQUEST'; // LEAVE = wpis w employee_leaves (w tym L4), PENDING_REQUEST = wniosek oczekujący
}

interface LeaveRequestDetail extends LeaveRequestSummary {
  employeeSignatureMethod: SignatureMethod | null;
  decisionSignatureMethod: SignatureMethod | null;
  overlappingAbsences: OverlappingAbsence[]; // inne osoby nieobecne w tym terminie
  canDecide: boolean;                        // dla bieżącego użytkownika
  decisionBlockedReason: string | null;      // np. "Własnego wniosku urlopowego nie można rozpatrzyć"
  canCancel: boolean;                        // APPROVED, przed startDate, bieżący może rozpatrywać
}

interface SigningSession { documentSha256: string; challenge: string; }
```

## Samoobsługa — `/api/v1/my/leave-requests`

Bez uprawnienia. Pracownik = rekord `employees` powiązany z zalogowanym kontem
(`findByStudioIdAndUserId`). Brak rekordu → 404 `"Twoje konto nie jest powiązane z pracownikiem"`.

| Metoda | Ścieżka | Body | Odpowiedź |
|---|---|---|---|
| GET | `/` | — | `{ requests: LeaveRequestSummary[], summary: { year: number, usedWorkingDays: number, pendingCount: number } }` (bez DRAFT, najnowsze pierwsze) |
| GET | `/preview?startDate&endDate` | — | `{ workingDays: number, holidays: { date: string, name: string }[] }` |
| POST | `/` | `{ leaveType, onDemand, startDate, endDate, reason? }` | `{ request: LeaveRequestDetail, session: SigningSession }` — status `DRAFT`, PDF wygenerowany |
| POST | `/{id}/signing-session` | — | `SigningSession` (nowy challenge dla DRAFT) |
| GET | `/{id}/document` | — | `application/pdf` — dokładnie te bajty, których hash jest w sesji |
| POST | `/{id}/submit` | `{ signatureImageBase64, documentSha256, challenge, declarationAccepted: true }` | `LeaveRequestDetail` (`PENDING`) |
| POST | `/{id}/withdraw` | — | `LeaveRequestDetail` (`WITHDRAWN`; z DRAFT lub PENDING) |
| GET | `/{id}/file` | — | `application/pdf` — final, a przed decyzją wersja podpisana przez pracownika |

Walidacja (400, komunikat po polsku, pole `field` gdy dotyczy pola):
`endDate < startDate`; `startDate` w przeszłości (poza `onDemand` na dziś);
`workingDays == 0`; nakładanie się z własnym wnioskiem PENDING/APPROVED lub wpisem
w `employee_leaves`; `onDemand` tylko przy ANNUAL i łącznie ≤ 4 dni w roku
kalendarzowym; `reason` wymagany przy `SPECIAL`.

## Rozpatrywanie — `/api/v1/leave-requests`

`@RequiresPermission(EMPLOYEES_LEAVES_APPROVE)` (właściciel przechodzi zawsze).

| Metoda | Ścieżka | Body | Odpowiedź |
|---|---|---|---|
| GET | `/?status=PENDING\|DECIDED\|ALL&employeeId=` | — | `{ items: LeaveRequestSummary[], pendingCount: number }` (bez DRAFT; DECIDED = APPROVED, REJECTED, CANCELLED, EXPIRED, WITHDRAWN) |
| GET | `/pending-count` | — | `{ count: number }` |
| GET | `/{id}` | — | `LeaveRequestDetail` |
| POST | `/{id}/decision-session` | — | `SigningSession` dla wersji podpisanej przez pracownika |
| GET | `/{id}/document` | — | `application/pdf` — wersja podpisana przez pracownika (to, co podpisuje rozpatrujący) |
| POST | `/{id}/approve` | `{ signatureImageBase64?: string, useSavedSignature: boolean, documentSha256, challenge, note?: string }` | `LeaveRequestDetail` (`APPROVED`) |
| POST | `/{id}/reject` | jw., `note` wymagane | `LeaveRequestDetail` (`REJECTED`) |
| POST | `/{id}/cancel` | `{ reason: string }` | `LeaveRequestDetail` (`CANCELLED`) |
| GET | `/{id}/file` | — | `application/pdf` |

`useSavedSignature: true` bez zapisanego podpisu w profilu → 400
`"Nie masz zapisanego podpisu"`. Zapisany podpis sprawdza front przez istniejące
`GET /api/v1/profile/signature`.

## Zmiany w istniejących kontraktach

- `Permission.EMPLOYEES_LEAVES_APPROVE` w katalogu (`GET /api/v1/roles/permissions`) i we
  frontowym `core/permissions/catalog.ts`.
- `UserData` (logowanie, `/auth/me`) dostaje `employeeId: string | null` — front pokazuje
  pozycję „Urlop” tylko, gdy nie jest `null`.
- `GET /api/v1/employees/leaves/calendar` — bez zmian (zatwierdzony wniosek tworzy wpis
  w `employee_leaves`).
- Push: `PushNotificationType.LEAVE_REQUEST_SUBMITTED` (do rozpatrujących, url
  `/employees/leave-requests?request={id}`), `LEAVE_REQUEST_DECIDED` (do pracownika, url `/me/leave`).
- Podpowiedź na Tablicy dla rozpatrujących: „N wniosków urlopowych czeka”, url
  `/employees/leave-requests`. Podpowiedź WORKTIME_MISSING zmienia url z
  `/settings?tab=team` na `/employees/worktime`.

## Doprecyzowania z implementacji backendu

Uzupełnienia i jedno ograniczenie wynikające z implementacji. Pola i ścieżki bez zmian.

- **Błąd walidacji (400)**: ciało jak wszędzie (`{ error, message, timestamp }`) plus
  `field: string | null` — nazwa pola żądania (`leaveType`, `onDemand`, `startDate`,
  `endDate`, `reason`, `substituteEmployeeId`, `declarationAccepted`, `signatureImageBase64`,
  `useSavedSignature`, `note`, `documentSha256`, `challenge`), gdy błąd dotyczy jednego pola.
- **Limit długości (nowe ograniczenie)**: `reason` przy tworzeniu wniosku i `note` przy
  decyzji — najwyżej **250 znaków** (400 z `field`). Tyle mieści się w polu na wniosku PDF,
  którego układ jest stały, bo podpisy stempluje się w stałe miejsca. `reason` przy
  odwołaniu (`/cancel`) — najwyżej 1000 znaków.
- `startDate` = dziś jest dozwolone wyłącznie przy `onDemand: true`; zwykły wniosek
  najwcześniej od jutra (400, `field: "startDate"`).
- `POST /api/v1/my/leave-requests` odpowiada **201**.
- `GET /api/v1/my/leave-requests/preview`: `holidays` zawiera tylko święta wypadające
  w dni powszednie zakresu (te, które faktycznie skróciły urlop).
- `summary.usedWorkingDays`: dni robocze urlopu wypoczynkowego (także na żądanie
  i wpisanego ręcznie) w bieżącym roku, liczone z `employee_leaves`.
- `GET /api/v1/leave-requests` bez `status` = `PENDING`. `pendingCount` = wszystkie
  oczekujące w studiu (licznik zakładki). `GET /pending-count` = oczekujące, które
  **bieżący użytkownik może rozpatrzyć** (bez jego własnych) — licznik przy „Pracownicy".
- Oba `GET …/{id}/document` zwracają nagłówek `X-Document-Sha256` (dodany do
  `Access-Control-Expose-Headers`). Rozpatrujący dostaje 404 dla szkiców (`DRAFT`).
- Kody: brak powiązanego pracownika → 404; cudzy wniosek → 404; własny wniosek
  u rozpatrującego → 403 `"Własnego wniosku urlopowego nie można rozpatrzyć"`; brak
  uprawnienia w chwili decyzji → 403 `"Brak uprawnienia: Akceptacja wniosków urlopowych"`;
  wniosek już rozpatrzony → 409 `"Wniosek został już rozpatrzony (…)"`; zużyty token →
  409; niezgodny skrót → 409 `"Dokument został odświeżony. Sprawdź go i podpisz ponownie."`.
- Zatwierdzenie odmawia (409), gdy pracownik ma już w tym terminie wpis w
  `employee_leaves` (np. L4 wpisane po złożeniu wniosku).
- Wpisu w `employee_leaves` utworzonego z wniosku nie da się usunąć przez
  `DELETE /api/v1/employees/{id}/leaves/{leaveId}` (400) — zdejmuje go `/cancel` wniosku.
- Push `LEAVE_REQUEST_DECIDED` idzie także po odwołaniu urlopu (`/cancel`), z tytułem
  „Urlop odwołany". Ikona obu powiadomień: `APP`.
- Podpowiedź na Tablicy: `kind: "LEAVE_REQUESTS_PENDING"`, tekst „1 wniosek urlopowy
  czeka." / „3 wnioski urlopowe czekają." / „5 wniosków urlopowych czeka.", akcja
  `NAVIGATE` „Rozpatrz" → `/employees/leave-requests`, klucz
  `LEAVE_REQUESTS_PENDING_{epochSecond najnowszego złożenia}` (drzemka 7 dni, nowy wniosek
  = nowy klucz).

## Zmiany z 01.10.2026 (v2)

Obowiązują ponad wszystkim powyżej.

1. **Osoba zastępująca usunięta całkowicie** — z API (`substituteEmployeeId`,
   `substituteName`), walidacji, PDF i bazy (V172 usuwa kolumnę). Pole przysłane przez
   starego klienta jest ignorowane.
2. **„Podstawa uprawnienia” usunięta** z PDF i z API (`decidedByBasis`,
   `decidedByRoleName` znikają z `LeaveRequestDetail`). Backend nadal zapisuje podstawę
   w bazie i w audycie — to ślad, nie treść dokumentu.
3. **Urlop dodany przez administratora** (`origin: 'ON_BEHALF'`). Dwa podpisy zostają:
   pracownik podpisuje osobiście na urządzeniu studia (metoda `IN_PERSON`, karta podpisów
   zapisuje, kto wprowadził wniosek i na czyim urządzeniu), potem administrator
   zatwierdza zwykłą decyzją z podpisem. Pracownik nie musi mieć konta w systemie.
   Endpointy pod `/api/v1/leave-requests` (`EMPLOYEES_LEAVES_APPROVE`, właściciel zawsze):

| Metoda | Ścieżka | Body | Odpowiedź |
|---|---|---|---|
| POST | `/` | `{ employeeId, leaveType, onDemand, startDate, endDate, reason? }` | 201 `{ request: LeaveRequestDetail, session: SigningSession }` — `DRAFT`, `origin: ON_BEHALF` |
| GET | `/preview?employeeId&startDate&endDate` | — | jak samoobsługowe `/preview` (dni robocze, święta) |
| POST | `/{id}/employee-signing-session` | — | `SigningSession` (nowy challenge dla szkicu ON_BEHALF) |
| POST | `/{id}/employee-signature` | `{ signatureImageBase64, documentSha256, challenge, declarationAccepted: true }` | `LeaveRequestDetail` (`PENDING`) |
| POST | `/{id}/discard` | — | 204 — porzucenie szkicu ON_BEHALF (status `WITHDRAWN`) |

   - `GET /{id}/document` dla szkicu `ON_BEHALF` utworzonego przez wywołującego zwraca
     dokument bez podpisów (H1); w pozostałych przypadkach jak dotąd (wersja po podpisie
     pracownika).
   - Walidacja jak w samoobsłudze (te same reguły dla pracownika `employeeId`), plus:
     `employeeId` musi istnieć w studiu; nie wolno utworzyć wniosku dla siebie
     (403 „Własny wniosek złóż w zakładce Urlop”).
   - Po `employee-signature` wniosek jest zwykłym `PENDING`: ta sama osoba może od razu
     przejść do `decision-session` → `approve`/`reject` (to nie jest samozatwierdzenie —
     wnioskodawcą jest pracownik). Push `LEAVE_REQUEST_SUBMITTED` nie idzie do autora
     wniosku ON_BEHALF.


### Doprecyzowania v2 z implementacji backendu

Uzupełnienia sekcji v2. Pola, ścieżki i kody z tabeli wyżej — bez zmian.

- **Szkic ON_BEHALF należy do wprowadzającego.** Nie tylko `GET /{id}/document`, ale też
  `employee-signing-session`, `employee-signature` i `discard` działają wyłącznie dla
  osoby, która wniosek utworzyła; inny administrator dostaje 404 (podpis osobisty jest
  opisany na karcie podpisów jako złożony na urządzeniu wprowadzającego). Pracownik nie
  widzi szkicu ON_BEHALF w samoobsłudze (404 na `/my/leave-requests/{id}/…`). Po podpisie
  wniosek jest zwykłym `PENDING` dla wszystkich rozpatrujących i dla pracownika.
- `GET /api/v1/leave-requests/{id}` dla szkicu (także ON_BEHALF) — nadal 404; szczegóły
  szkicu przychodzą w odpowiedzi `POST /`.
- `POST /` (ON_BEHALF): brak `employeeId` → 400 `field: "employeeId"` „Wybierz pracownika”;
  `employeeId` spoza studia albo niebędący UUID → 404 „Nie znaleziono pracownika”; wniosek
  dla siebie → 403 „Własny wniosek złóż w zakładce Urlop”. Kolejność: najpierw pracownik
  (404/403), potem pola (400). Komunikaty kolizji mówią o pracowniku („W tym terminie
  pracownik ma już wniosek…”), a nie „masz już”.
- `GET /preview?employeeId&startDate&endDate`: `employeeId` spoza studia → 404 „Nie znaleziono
  pracownika”. Wynik jak w samoobsłudze.
- `POST /{id}/employee-signature`: błędy jak przy samoobsługowym `/submit` (400 z `field`
  dla `declarationAccepted`, `documentSha256`, `challenge`, `signatureImageBase64`; 409 przy
  niezgodnym skrócie, zużytym tokenie, już podpisanym albo porzuconym szkicu).
  `employeeSignatureMethod` = `IN_PERSON`. Adres IP i przeglądarka na karcie podpisów są
  urządzenia wprowadzającego — karta mówi to wprost.
- `POST /{id}/discard` dla wniosku już podpisanego przez pracownika → 409 (taki wniosek się
  odrzuca decyzją); ponowne porzucenie → 409 „Ten szkic został już porzucony”.
- „Sposób złożenia” na PDF przy ON_BEHALF: „Wprowadzony przez: {createdByName}, podpisany
  osobiście” (przy dłuższym nazwisku w dwóch wierszach).
- `createdByName` przy `SELF_SERVICE` = imię i nazwisko pracownika, który złożył wniosek
  (to on go wprowadził); `null` tylko przy pustym imieniu konta.
- Szkic ON_BEHALF niepodpisany ani nieporzucony usuwa po dobie ten sam job co szkice
  samoobsługowe.
- Pole `field` błędu 400: dochodzi `employeeId`; `substituteEmployeeId` już nie występuje.
