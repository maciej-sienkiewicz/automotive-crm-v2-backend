# API — Rezerwacje Cykliczne (Recurring Appointments)

> Wersja: 1.0 | Base URL: `/api/v1/appointments`
> Wszystkie endpointy wymagają uwierzytelnienia. Tworzenie/edycja/usuwanie — role `OWNER` lub `MANAGER`.

---

## Spis treści

1. [Koncepcja](#1-koncepcja)
2. [Zmiana w istniejących endpointach](#2-zmiana-w-istniejących-endpointach)
3. [POST /recurring — utwórz serię](#3-post-recurring--utwórz-serię)
4. [GET /series/{seriesId} — podgląd serii](#4-get-seriessseriesid--podgląd-serii)
5. [PUT /{id}?scope= — edycja z zakresem](#5-put-idscope--edycja-z-zakresem)
6. [DELETE /{id}?scope= — usuwanie z zakresem](#6-delete-idscope--usuwanie-z-zakresem)
7. [GET / — lista wizyt (zmiany)](#7-get---lista-wizyt-zmiany)
8. [Typy danych referencyjne](#8-typy-danych-referencyjne)
9. [Kody błędów](#9-kody-błędów)
10. [Przykłady UX flows](#10-przykłady-ux-flows)

---

## 1. Koncepcja

### Jak działa model

- Każda **seria cykliczna** to rekord w bazie z regułą (np. „co 2 tygodnie w poniedziałek").
- Przy tworzeniu serii system **generuje wszystkie wystąpienia z góry** jako osobne rekordy w tabeli `appointments`. Każde wystąpienie to pełnoprawna wizyta — ma własne `id`, własne usługi, własny status.
- Wystąpienia są powiązane z serią przez `recurrenceInfo.seriesId` i numerowane `recurrenceInfo.recurrenceIndex` (0, 1, 2, …).
- **`isDetached: true`** — oznacza, że dane wystąpienie było indywidualnie edytowane i **nie podlega** seryjnym edycjom (`scope=ALL` / `scope=THIS_AND_FUTURE`). Warto dać użytkownikowi wizualny sygnał (np. ikona „odłączone od serii").

### Limity
- Maksymalnie **104 wystąpienia** na serię (2 lata cotygodniowo).
- `dayOfMonth` maks. **28** (unika problemów z luty).

---

## 2. Zmiana w istniejących endpointach

### `PUT /{id}` — nowy opcjonalny param `?scope`

```
PUT /api/v1/appointments/{id}?scope=THIS
PUT /api/v1/appointments/{id}?scope=THIS_AND_FUTURE
PUT /api/v1/appointments/{id}?scope=ALL
```

Domyślnie (brak `scope`) zachowuje się jak dotychczas — edytuje tylko tę jedną wizytę.

### `DELETE /{id}` — nowy opcjonalny param `?scope`

```
DELETE /api/v1/appointments/{id}?scope=THIS
DELETE /api/v1/appointments/{id}?scope=THIS_AND_FUTURE
DELETE /api/v1/appointments/{id}?scope=ALL
```

Domyślnie (brak `scope`) zachowuje się jak dotychczas — usuwa tylko tę jedną wizytę.

> **Uwaga:** Jeśli `scope=THIS_AND_FUTURE` lub `scope=ALL` zostanie wysłane dla wizyty, która **nie należy do serii** — serwer zwróci `400 Bad Request`.

---

## 3. POST /recurring — utwórz serię

```
POST /api/v1/appointments/recurring
Content-Type: application/json
```

### Request body

Identyczny kształt jak `POST /api/v1/appointments`, plus **obowiązkowy** blok `recurrence`.

```jsonc
{
  // === Identyczne pola jak przy zwykłej rezerwacji ===
  "customer": { /* patrz: CustomerIdentityRequest */ },
  "vehicle":  { /* patrz: VehicleIdentityRequest  */ },
  "services": [ /* patrz: ServiceLineItemRequest  */ ],
  "schedule": {
    "isAllDay": false,
    "startDateTime": "2026-06-09T10:00:00Z",  // ISO-8601 UTC
    "endDateTime":   "2026-06-09T12:00:00Z"
  },
  "appointmentTitle": "Detailing floty - Jan Kowalski",
  "appointmentColorId": "uuid-koloru",
  "note": "Klucze w recepcji",
  "sendConfirmationSms": false,
  "sendReminderSms": true,

  // === Blok cykliczności (wymagany) ===
  "recurrence": { /* patrz niżej */ }
}
```

### Blok `recurrence` — typ WEEKLY

```jsonc
{
  "type": "WEEKLY",
  "intervalWeeks": 2,           // co ile tygodni (1–52), wymagane
  "daysOfWeek": ["MONDAY", "WEDNESDAY"],  // min. 1 dzień, wymagane
  "endType": "COUNT",           // "COUNT" | "DATE" | "OPEN"
  "maxOccurrences": 12          // wymagane gdy endType=COUNT (1–104)
}
```

### Blok `recurrence` — typ MONTHLY

```jsonc
{
  "type": "MONTHLY",
  "dayOfMonth": 1,              // 1–28, wymagane
  "endType": "DATE",
  "endDate": "2026-12-31"       // YYYY-MM-DD, wymagane gdy endType=DATE
}
```

### Blok `recurrence` — endType OPEN (bez końca)

```jsonc
{
  "type": "WEEKLY",
  "intervalWeeks": 1,
  "daysOfWeek": ["FRIDAY"],
  "endType": "OPEN"
  // Generuje 104 wystąpienia; system automatycznie dogeneruje kolejne
}
```

### Możliwe wartości `daysOfWeek`

```
MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY | SATURDAY | SUNDAY
```

### Response `201 Created`

```jsonc
{
  "seriesId": "550e8400-e29b-41d4-a716-446655440000",  // UUID serii
  "occurrenceCount": 12,          // ile wizyt zostało utworzonych
  "firstAppointmentId": "uuid",   // ID pierwszej wizyty (index 0)
  "customerId": "uuid",
  "vehicleId": "uuid"             // null jeśli brak pojazdu
}
```

### Walidacja — błędy `400`

| Warunek | Komunikat |
|---|---|
| `type=WEEKLY` bez `daysOfWeek` | `daysOfWeek jest wymagane dla reguły tygodniowej` |
| `intervalWeeks` poza zakresem 1–52 | `intervalWeeks musi być między 1 a 52` |
| `type=MONTHLY` bez `dayOfMonth` | `dayOfMonth jest wymagane` |
| `dayOfMonth` poza zakresem 1–28 | `dayOfMonth musi być między 1 a 28` |
| `endType=COUNT` bez `maxOccurrences` | `maxOccurrences jest wymagane dla endType=COUNT` |
| `maxOccurrences` > 104 | `maxOccurrences musi być między 1 a 104` |
| `endType=DATE` bez `endDate` | `endDate jest wymagane dla endType=DATE` |
| Konflikt terminu (nakładanie z inną wizytą) | Lista konfliktujących dat w `errors[]` |

---

## 4. GET /series/{seriesId} — podgląd serii

```
GET /api/v1/appointments/series/{seriesId}
```

Zwraca metadane reguły cykliczności. Przydatne przy renderowaniu dialogu edycji serii.

### Response `200 OK`

```jsonc
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "WEEKLY",               // "WEEKLY" | "MONTHLY"
  "intervalWeeks": 2,             // null dla MONTHLY
  "daysOfWeek": ["MONDAY", "WEDNESDAY"],  // null dla MONTHLY, posortowane
  "dayOfMonth": null,             // null dla WEEKLY
  "endType": "COUNT",             // "COUNT" | "DATE" | "OPEN"
  "endDate": null,                // "2026-12-31" gdy endType=DATE, null inaczej
  "maxOccurrences": 12,           // null gdy endType!=COUNT
  "isOpenEnded": false,           // true gdy endType=OPEN
  "totalOccurrences": 11,         // ile wizyt pozostało (nieusunięte)
  "createdAt": "2026-06-02T08:00:00Z"
}
```

---

## 5. PUT /{id}?scope= — edycja z zakresem

```
PUT /api/v1/appointments/{id}?scope=THIS
PUT /api/v1/appointments/{id}?scope=THIS_AND_FUTURE
PUT /api/v1/appointments/{id}?scope=ALL
```

### Semantyka zakresów

| Scope | Co się zmienia | Efekt uboczny |
|---|---|---|
| `THIS` (domyślny) | Tylko ta jedna wizyta | Wizyta staje się `isDetached: true` |
| `THIS_AND_FUTURE` | Ta i wszystkie przyszłe (według `recurrenceIndex`) | Pomija: `isDetached=true` i `CONVERTED` |
| `ALL` | Wszystkie wizyty w serii | Pomija: `isDetached=true` i `CONVERTED` |

### ⚠️ Ważne ograniczenie — zmiana dat

`THIS_AND_FUTURE` i `ALL` **nie zmieniają dat/godzin** poszczególnych wystąpień — daty zostały wygenerowane przy tworzeniu serii. Zmiana harmonogramu wymaga:
1. `DELETE ?scope=THIS_AND_FUTURE` — usuń przyszłe
2. `POST /recurring` — utwórz nową serię od nowej daty

Zmiana daty/godziny **jednej** wizyty jest możliwa przez `PUT /{id}` (bez scope lub `scope=THIS`).

### Request body

Identyczny jak przy edycji zwykłej wizyty (`CreateAppointmentRequest`).

### Response `200 OK`

Dla `scope=THIS`: identyczny jak przy edycji zwykłej wizyty:
```jsonc
{
  "id": "uuid",
  "customerId": "uuid",
  "vehicleId": "uuid",
  "totalNet": 50000,
  "totalGross": 61500,
  "totalVat": 11500
}
```

Dla `scope=THIS_AND_FUTURE` i `scope=ALL` — **TODO dla backendowego sprintu 2**: aktualnie zwraca ten sam kształt. W następnej iteracji planowane jest zwracanie:
```jsonc
{
  "updatedCount": 9,
  "skippedDetachedCount": 1,
  "skippedConvertedCount": 0
}
```

> Na razie: jeśli chcesz wiedzieć ile zostało zaktualizowane, możesz po edycji pobrać `GET /series/{seriesId}`.

---

## 6. DELETE /{id}?scope= — usuwanie z zakresem

```
DELETE /api/v1/appointments/{id}?scope=THIS
DELETE /api/v1/appointments/{id}?scope=THIS_AND_FUTURE
DELETE /api/v1/appointments/{id}?scope=ALL
```

### Semantyka zakresów

| Scope | Co zostaje usunięte |
|---|---|
| `THIS` (domyślny) | Tylko ta jedna wizyta (soft-delete) |
| `THIS_AND_FUTURE` | Ta i wszystkie z `recurrenceIndex >=` tego wystąpienia, w tym `isDetached=true`. Wizyty z wcześniejszymi datami zostają. |
| `ALL` | Wszystkie wizyty w serii |

### Gwarancje

- Wizyty ze statusem **`CONVERTED`** (przekonwertowane na wizytę warsztatową) **nigdy nie są usuwane** przez operacje serii — są pomijane.
- Soft-delete — wizyty nie są fizycznie usuwane z bazy, jedynie nie pojawiają się w wynikach.

### Response `204 No Content`

Brak body.

---

## 7. GET / — lista wizyt (zmiany)

```
GET /api/v1/appointments?page=1&limit=20
```

### Nowe pole w każdym elemencie listy: `recurrenceInfo`

Każda wizyta należąca do serii ma teraz wypełniony obiekt `recurrenceInfo`. Dla zwykłych (nie-cyklicznych) wizyt pole ma wartość `null`.

```jsonc
{
  "id": "uuid",
  "customerId": "uuid",
  // ... wszystkie istniejące pola ...
  "recurrenceInfo": {
    "seriesId": "550e8400-e29b-41d4-a716-446655440000",
    "recurrenceIndex": 2,       // 0-based; 0 = pierwsza wizyta serii
    "totalInSeries": 12,        // łączna liczba nieusunięciętych wizyt w serii
    "isDetached": false         // true = była edytowana indywidualnie
  }
}
```

### Zastosowanie `recurrenceInfo` w UI

| Pole | Do czego używać |
|---|---|
| `seriesId` | Przycisk „pokaż całą serię" → filtr `?seriesId=…` |
| `recurrenceIndex` + `totalInSeries` | Etykieta „3 z 12", „i jeszcze 9 więcej" |
| `isDetached` | Ikona/tooltip „zmodyfikowana indywidualnie" |
| `recurrenceInfo !== null` | Rozróżnienie cykliczna vs jednorazowa |

### Filtrowanie wizyt cyklicznych

Można filtrować listę po serii lub ograniczyć tylko do cyklicznych — **te filtry nie są jeszcze zaimplementowane** w bieżącej wersji backendu (sprint 2). Tymczasowo można po stronie frontu filtrować po `recurrenceInfo !== null`.

Planowane parametry:
```
GET /api/v1/appointments?recurringOnly=true
GET /api/v1/appointments?seriesId={uuid}
```

### Sugestia dotycząca wyświetlania w tabeli

Aby uniknąć „zalania" tabeli dziesiątkami przyszłych wizyt z jednej serii, zalecamy:

1. **Domyślnie** pokazuj wizyty do przodu max 8–12 tygodni (filtr `scheduledDate` zakresowy).
2. Grupuj wizyty z tej samej serii wizualnie (np. kolorowy pasek z boku, nagłówek grupy).
3. Przy kliknięciu „pokaż całą serię" ładuj `?seriesId={id}` bez limitu datowego.

---

## 8. Typy danych referencyjne

### RecurrenceType

```typescript
type RecurrenceType = "WEEKLY" | "MONTHLY"
```

### RecurrenceEndType

```typescript
type RecurrenceEndType = "COUNT" | "DATE" | "OPEN"
```

### RecurrenceEditScope

```typescript
type RecurrenceEditScope = "THIS" | "THIS_AND_FUTURE" | "ALL"
```

### RecurrenceInfo (w AppointmentListItem)

```typescript
interface RecurrenceInfo {
  seriesId: string           // UUID
  recurrenceIndex: number    // 0-based position in series
  totalInSeries: number      // total non-deleted occurrences
  isDetached: boolean        // was individually edited
}
```

### RecurrenceRuleRequest (przy tworzeniu)

```typescript
interface RecurrenceRuleRequest {
  type: "WEEKLY" | "MONTHLY"

  // WEEKLY only:
  intervalWeeks?: number          // 1–52
  daysOfWeek?: DayOfWeek[]        // min 1 element

  // MONTHLY only:
  dayOfMonth?: number             // 1–28

  // End condition:
  endType: "COUNT" | "DATE" | "OPEN"
  maxOccurrences?: number         // required when endType=COUNT, 1–104
  endDate?: string                // "YYYY-MM-DD", required when endType=DATE
}

type DayOfWeek =
  | "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY"
  | "FRIDAY" | "SATURDAY" | "SUNDAY"
```

### CreateRecurringAppointmentResponse

```typescript
interface CreateRecurringAppointmentResponse {
  seriesId: string           // UUID serii
  occurrenceCount: number    // ile wizyt zostało utworzonych
  firstAppointmentId: string // UUID pierwszej wizyty (index 0)
  customerId: string
  vehicleId: string | null
}
```

### RecurrenceSeriesResponse (GET /series/{id})

```typescript
interface RecurrenceSeriesResponse {
  id: string
  type: "WEEKLY" | "MONTHLY"
  intervalWeeks: number | null
  daysOfWeek: DayOfWeek[] | null   // posortowane (MON→SUN)
  dayOfMonth: number | null
  endType: "COUNT" | "DATE" | "OPEN"
  endDate: string | null           // "YYYY-MM-DD"
  maxOccurrences: number | null
  isOpenEnded: boolean
  totalOccurrences: number         // nieusunięte wystąpienia
  createdAt: string                // ISO-8601
}
```

---

## 9. Kody błędów

| HTTP | Kiedy |
|---|---|
| `400 Bad Request` | Nieprawidłowe parametry reguły cykliczności; wysłanie `scope=THIS_AND_FUTURE` dla nie-cyklicznej wizyty |
| `403 Forbidden` | Rola `DETAILER` próbuje tworzyć/edytować/usuwać |
| `404 Not Found` | `appointmentId` lub `seriesId` nie istnieje lub nie należy do Twojego studio |
| `409 Conflict` | Nakładanie się terminów przy tworzeniu serii (body zawiera listę konfliktujących dat) |

---

## 10. Przykłady UX flows

### Flow 1: Klient co 2 tygodnie w poniedziałek, 12 razy

```json
POST /api/v1/appointments/recurring
{
  "customer": { "mode": "EXISTING", "id": "uuid-klienta" },
  "vehicle":  { "mode": "EXISTING", "id": "uuid-pojazdu" },
  "services": [{
    "id": "temp-1",
    "serviceId": "uuid-uslugi",
    "serviceName": "Detailing pełny",
    "basePriceNet": 50000,
    "vatRate": 23,
    "adjustment": { "type": "PERCENT", "value": 0 },
    "note": null
  }],
  "schedule": {
    "isAllDay": false,
    "startDateTime": "2026-06-15T09:00:00Z",
    "endDateTime":   "2026-06-15T12:00:00Z"
  },
  "appointmentTitle": "Detailing cykliczny - Kowalski",
  "appointmentColorId": "uuid-koloru",
  "note": null,
  "sendConfirmationSms": true,
  "sendReminderSms": true,
  "recurrence": {
    "type": "WEEKLY",
    "intervalWeeks": 2,
    "daysOfWeek": ["MONDAY"],
    "endType": "COUNT",
    "maxOccurrences": 12
  }
}
```

**Response:**
```json
{
  "seriesId": "aaa-bbb-ccc",
  "occurrenceCount": 12,
  "firstAppointmentId": "111-222-333",
  "customerId": "uuid-klienta",
  "vehicleId": "uuid-pojazdu"
}
```

---

### Flow 2: Klient zawsze 1. każdego miesiąca, bez końca

```json
POST /api/v1/appointments/recurring
{
  ...
  "recurrence": {
    "type": "MONTHLY",
    "dayOfMonth": 1,
    "endType": "OPEN"
  }
}
```

---

### Flow 3: Zmiana tytułu dla wszystkich przyszłych wizyt serii

```json
PUT /api/v1/appointments/111-222-333?scope=THIS_AND_FUTURE
{
  "customer": { "mode": "EXISTING", "id": "uuid-klienta" },
  "vehicle":  { "mode": "EXISTING", "id": "uuid-pojazdu" },
  "services": [ /* bez zmian */ ],
  "schedule": { /* bez zmian */ },
  "appointmentTitle": "Detailing fleet - Nowa Firma Sp. z o.o.",
  "appointmentColorId": "uuid-koloru",
  "note": null,
  "sendReminderSms": true
}
```

---

### Flow 4: Usunięcie cyklu od konkretnej wizyty (klient rezygnuje)

```
DELETE /api/v1/appointments/111-222-333?scope=THIS_AND_FUTURE
```

Wizyty wcześniej niż ta (indeks < 2) zostają w kalendarzu. Odpowiedź: `204 No Content`.

---

### Flow 5: Dodanie usługi do całej serii

```json
PUT /api/v1/appointments/111-222-333?scope=ALL
{
  "customer": { "mode": "EXISTING", "id": "uuid-klienta" },
  "vehicle":  { "mode": "EXISTING", "id": "uuid-pojazdu" },
  "services": [
    { /* istniejąca usługa */ },
    {
      "id": "new-service-temp",
      "serviceId": null,
      "serviceName": "Zabezpieczenie lakieru",
      "basePriceNet": 20000,
      "vatRate": 23,
      "adjustment": { "type": "PERCENT", "value": 0 },
      "note": null
    }
  ],
  "schedule": { /* bez zmian */ },
  "appointmentTitle": "Detailing cykliczny - Kowalski",
  "appointmentColorId": "uuid-koloru",
  "note": null,
  "sendReminderSms": true
}
```

> Wizyty z `isDetached: true` i `status: CONVERTED` zostaną pominięte.

---

### Flow 6: Wyświetlanie „i X więcej" w tabeli

```
GET /api/v1/appointments?page=1&limit=20
```

Dla każdej wizyty z `recurrenceInfo !== null`:
```typescript
const info = appointment.recurrenceInfo
const label = `${info.recurrenceIndex + 1} z ${info.totalInSeries}`
// → "3 z 12"
```

Przy kliknięciu „pokaż całą serię":
```
GET /api/v1/appointments?seriesId={info.seriesId}   // sprint 2
```
