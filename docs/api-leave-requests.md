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
type SignatureMethod = 'DEVICE_DRAWN' | 'SAVED_SIGNATURE';
type ApprovalBasis = 'OWNER' | 'PERMISSION';

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
  substituteEmployeeId: string | null;
  substituteName: string | null;
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
  decidedByBasis: ApprovalBasis | null;
  decidedByRoleName: string | null;
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
| POST | `/` | `{ leaveType, onDemand, startDate, endDate, reason?, substituteEmployeeId? }` | `{ request: LeaveRequestDetail, session: SigningSession }` — status `DRAFT`, PDF wygenerowany |
| POST | `/{id}/signing-session` | — | `SigningSession` (nowy challenge dla DRAFT) |
| GET | `/{id}/document` | — | `application/pdf` — dokładnie te bajty, których hash jest w sesji |
| POST | `/{id}/submit` | `{ signatureImageBase64, documentSha256, challenge, declarationAccepted: true }` | `LeaveRequestDetail` (`PENDING`) |
| POST | `/{id}/withdraw` | — | `LeaveRequestDetail` (`WITHDRAWN`; z DRAFT lub PENDING) |
| GET | `/{id}/file` | — | `application/pdf` — final, a przed decyzją wersja podpisana przez pracownika |

Walidacja (400, komunikat po polsku, pole `field` gdy dotyczy pola):
`endDate < startDate`; `startDate` w przeszłości (poza `onDemand` na dziś);
`workingDays == 0`; nakładanie się z własnym wnioskiem PENDING/APPROVED lub wpisem
w `employee_leaves`; `onDemand` tylko przy ANNUAL i łącznie ≤ 4 dni w roku
kalendarzowym; `reason` wymagany przy `SPECIAL`; `substituteEmployeeId` ≠ wnioskodawca.

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
