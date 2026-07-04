# API Reference — Moduł Finansowy i KSeF

> **Wersja:** po refaktoryzacji (branch `claude/audit-ksef-module-wJdtN`)
> **Base URL:** `/api/v1`
> **Autoryzacja:** sesja cookie (`SESSION`). Po zalogowaniu via `POST /api/v1/auth/login` przeglądarka automatycznie dołącza cookie do każdego żądania. W środowisku SPA wymagane `credentials: 'include'` (lub `withCredentials: true` w axios).
> **Format dat:** ISO 8601. Daty bez czasu (`2024-03-15`) lub z czasem i strefą (`2024-03-15T10:30:00+01:00`).
> **Kwoty pieniężne (moduł Finance):** liczby całkowite w **groszach** (1 PLN = 100 gr). Moduł KSeF używa `Double` zgodnie ze schematem KSeF.

---

## Spis treści

1. [Kontekst biznesowy](#1-kontekst-biznesowy)
2. [Role i uprawnienia](#2-role-i-uprawnienia)
3. [Obsługa błędów](#3-obsługa-błędów)
4. [Moduł Finance — Dokumenty Przychodowe](#4-moduł-finance--dokumenty-przychodowe)
   - [POST /finance/documents](#post-financedocuments)
   - [GET /finance/documents](#get-financedocuments)
   - [GET /finance/documents/{id}](#get-financedocumentsid)
   - [PATCH /finance/documents/{id}](#patch-financedocumentsid)
   - [PATCH /finance/documents/{id}/status](#patch-financedocumentsidstatus)
   - [DELETE /finance/documents/{id}](#delete-financedocumentsid)
   - [POST /finance/documents/{id}/restore](#post-financedocumentsidrestore)
5. [Moduł Finance — Kasa](#5-moduł-finance--kasa)
   - [GET /finance/cash](#get-financecash)
   - [GET /finance/cash/history](#get-financecashhistory)
   - [POST /finance/cash/adjust](#post-financecashadjust)
6. [Moduł Finance — Raporty](#6-moduł-finance--raporty)
   - [GET /finance/summary](#get-financesummary)
   - [GET /finance/payment-method-report](#get-financepayment-method-report)
7. [Moduł KSeF — Dane dostępowe](#7-moduł-ksef--dane-dostępowe)
   - [POST /ksef/credentials](#post-ksefcredentials)
   - [GET /ksef/credentials](#get-ksefcredentials)
   - [DELETE /ksef/credentials](#delete-ksefcredentials)
8. [Moduł KSeF — Synchronizacja](#8-moduł-ksef--synchronizacja)
   - [GET /ksef/sync/status](#get-ksefsyncstatus)
   - [POST /ksef/sync/trigger](#post-ksefsynctrigger)
9. [Moduł KSeF — Dokumenty Kosztowe](#9-moduł-ksef--dokumenty-kosztowe)
   - [GET /ksef/expenses](#get-ksefexpenses)
   - [POST /ksef/expenses](#post-ksefexpenses)
   - [POST /ksef/expenses/sync](#post-ksefexpensessync)
   - [PATCH /ksef/expenses/{id}/exclude](#patch-ksefexpensesidexclude)
   - [PATCH /ksef/expenses/{id}/restore](#patch-ksefexpensesidrestore)
   - [PATCH /ksef/expenses/{id}/payment-status](#patch-ksefexpensesidpayment-status)
   - [PATCH /ksef/expenses/{id}/note](#patch-ksefexpensesidnote)
   - [DELETE /ksef/expenses/{id}/note](#delete-ksefexpensesidnote)
   - [DELETE /ksef/expenses/{id}](#delete-ksefexpensesid)
10. [Moduł KSeF — Statystyki kosztowe](#10-moduł-ksef--statystyki-kosztowe)
    - [GET /ksef/statistics](#get-ksefstatistics)
11. [Słownik wartości enum](#11-słownik-wartości-enum)
12. [Przepływy UI — gotowe scenariusze](#12-przepływy-ui--gotowe-scenariusze)

---

## 1. Kontekst biznesowy

System zarządza **dwiema oddzielnymi klasami dokumentów finansowych**. Ważne, żeby nie mylic ich ze sobą:

### Dokumenty Przychodowe (`/finance/documents`)

**To NIE są faktury VAT.** To **adnotacje w CRM** — administrator zaznacza, że wizyta zakończyła się wystawieniem dokumentu sprzedaży. Faktyczną fakturę wystawia admin w zewnętrznym programie (np. inFakt, KSeF). CRM przechowuje tylko:
- czy wystawiono paragon / fakturę / inny dokument
- kwoty i metodę płatności
- powiązanie z wizytą i klientem

Każda ukończona wizyta **automatycznie tworzy** dokument przychodowy (przez `CompleteVisitHandler`). Admin może też tworzyć je ręcznie.

### Dokumenty Kosztowe (`/ksef/expenses`)

Koszty działalności studia. **Dwa źródła:**
- `KSEF` — faktury zakupowe automatycznie pobrane z systemu KSeF (Ministerstwo Finansów). Synchronizacja działa co godzinę lub na żądanie.
- `MANUAL` — faktury dodane ręcznie, gdy dostawca nie wystawia faktur w KSeF.

**Kluczowa zasada dla KSeF:** faktur z KSeF **nie wolno usuwać** (integralność audytowa). Można je tylko "ukryć" (exclude) — wypadają ze statystyk, ale zostają w systemie.

---

## 2. Role i uprawnienia

| Rola | Opis |
|------|------|
| `OWNER` | Właściciel studia — pełny dostęp |
| `MANAGER` | Manager — odczyt + akcje operacyjne, bez usuwania i konfiguracji KSeF |
| `EMPLOYEE` | Pracownik — tylko odczyt dokumentów |

Przy próbie wykonania operacji bez uprawnień API zwraca `403 Forbidden`.

---

## 3. Obsługa błędów

Wszystkie błędy zwracają JSON:

```json
{
  "error": "Validation Error",
  "message": "Kwoty dokumentu finansowego nie mogą być ujemne",
  "timestamp": "2024-03-15T10:30:00.000Z"
}
```

| Kod HTTP | Kiedy |
|----------|-------|
| `400 Bad Request` | Błąd walidacji danych wejściowych |
| `401 Unauthorized` | Brak sesji lub sesja wygasła |
| `403 Forbidden` | Za mała rola użytkownika |
| `404 Not Found` | Zasób nie istnieje lub należy do innego studia |
| `409 Conflict` | Próba duplikatu |
| `422 Unprocessable Entity` | Logicznie sprzeczne dane |
| `500 Internal Server Error` | Błąd serwera |

---

## 4. Moduł Finance — Dokumenty Przychodowe

### POST /finance/documents

**Kiedy używać:** Ręczne dodanie adnotacji o wystawionym dokumencie (paragon, faktura) bez powiązanej wizyty, lub korekta/uzupełnienie istniejącej sprzedaży.

> Uwaga: zakończenie wizyty (`POST /visits/{id}/complete`) tworzy dokument automatycznie — nie trzeba tego robić ręcznie.

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{
  "documentType":    "RECEIPT",
  "direction":       "INCOME",
  "paymentMethod":   "CASH",
  "totalNet":        8130,
  "totalVat":        1870,
  "totalGross":      10000,
  "currency":        "PLN",
  "issueDate":       "2024-03-15",
  "dueDate":         null,
  "description":     "Detailing pełny – Toyota Corolla WA 12345",
  "counterpartyName": "Jan Kowalski",
  "counterpartyNip": null,
  "visitId":         "550e8400-e29b-41d4-a716-446655440000",
  "vehicleBrand":    "Toyota",
  "vehicleModel":    "Corolla",
  "customerFirstName": "Jan",
  "customerLastName":  "Kowalski"
}
```

| Pole | Typ | Wymagany | Opis |
|------|-----|----------|------|
| `documentType` | string | ✓ | `RECEIPT` \| `INVOICE` \| `OTHER` |
| `direction` | string | ✓ | `INCOME` \| `EXPENSE` |
| `paymentMethod` | string | ✓ | `CASH` \| `CARD` \| `TRANSFER` \| `OTHER` |
| `totalNet` | integer | ✓ | Kwota netto w groszach |
| `totalVat` | integer | ✓ | Kwota VAT w groszach |
| `totalGross` | integer | ✓ | Kwota brutto w groszach. **Musi być równa `totalNet + totalVat`** |
| `currency` | string | — | Domyślnie `PLN` |
| `issueDate` | date | ✓ | Data wystawienia `YYYY-MM-DD` |
| `dueDate` | date | — | Wymagany gdy `paymentMethod = TRANSFER` |
| `description` | string | — | Opis widoczny na liście i w raporcie kasowym |
| `counterpartyName` | string | — | Nazwa klienta/kontrahenta |
| `counterpartyNip` | string | — | NIP kontrahenta |
| `visitId` | UUID | — | Powiązanie z wizytą |
| `vehicleBrand`, `vehicleModel` | string | — | Snapshot pojazdu |
| `customerFirstName`, `customerLastName` | string | — | Snapshot klienta |

**Reguły biznesowe automatycznie stosowane przez backend:**
- `CASH` / `CARD` → status = `PAID`, `paidAt` = teraz
- `TRANSFER` → status = `PENDING`, `dueDate` wymagany
- `CASH` → wpis w kasie automatycznie zaktualizowany

**Response `201 Created`:**
```json
{
  "id": "a1b2c3d4-...",
  "documentNumber": "PAR/2024/0001",
  "source": "MANUAL",
  "sourceLabel": "Ręcznie",
  "documentType": "RECEIPT",
  "documentTypeLabel": "Paragon",
  "direction": "INCOME",
  "directionLabel": "Przychód",
  "status": "PAID",
  "statusLabel": "Opłacony",
  "paymentMethod": "CASH",
  "paymentMethodLabel": "Gotówka",
  "totalNet": 8130,
  "totalVat": 1870,
  "totalGross": 10000,
  "currency": "PLN",
  "issueDate": "2024-03-15",
  "dueDate": null,
  "paidAt": "2024-03-15T10:30:00Z",
  "description": "Detailing pełny – Toyota Corolla WA 12345",
  "counterpartyName": "Jan Kowalski",
  "counterpartyNip": null,
  "visitId": "550e8400-...",
  "vehicleBrand": "Toyota",
  "vehicleModel": "Corolla",
  "customerFirstName": "Jan",
  "customerLastName": "Kowalski",
  "createdBy": "user-uuid",
  "createdAt": "2024-03-15T10:30:00Z",
  "updatedAt": "2024-03-15T10:30:00Z",
  "deletedAt": null
}
```

> **Kwoty w groszach** — dziel przez 100 do wyświetlenia: `(10000 / 100).toFixed(2)` → `"100.00"`.

---

### GET /finance/documents

**Kiedy używać:** Lista dokumentów przychodowych z filtrowaniem — ekran "Dokumenty finansowe", raporty, wyszukiwarka.

**Uprawnienia:** Wszystkie role

**Query params:**

| Param | Typ | Domyślnie | Opis |
|-------|-----|-----------|------|
| `page` | integer | `1` | Numer strony (od 1) |
| `size` | integer | `20` | Wyniki na stronę (max 100) |
| `documentType` | string | — | `RECEIPT` \| `INVOICE` \| `OTHER` |
| `direction` | string | — | `INCOME` \| `EXPENSE` |
| `status` | string | — | `PAID` \| `PENDING` \| `OVERDUE` |
| `visitId` | UUID | — | Filtr po wizycie |
| `dateFrom` | date | — | Filtr po dacie wystawienia (od) |
| `dateTo` | date | — | Filtr po dacie wystawienia (do) |
| `includeDeleted` | boolean | `false` | Dołączyć soft-deleted dokumenty |

**Przykład:** `GET /api/v1/finance/documents?direction=INCOME&status=PENDING&dateFrom=2024-01-01&dateTo=2024-03-31`

**Response `200 OK`:**
```json
{
  "documents": [ /* lista FinancialDocumentResponse */ ],
  "total": 42,
  "page": 1,
  "pageSize": 20
}
```

---

### GET /finance/documents/{id}

**Kiedy używać:** Szczegółowy widok jednego dokumentu.

**Uprawnienia:** Wszystkie role

**Response `200 OK`:** `FinancialDocumentResponse` (jak wyżej)

---

### PATCH /finance/documents/{id}

**Kiedy używać:** Aktualizacja numeru dokumentu — po wystawieniu faktury w zewnętrznym systemie admin wpisuje jej numer (np. `FAK/2024/0042`). CRM generuje własny numer automatycznie, ale admin może go nadpisać numerem z zewnętrznego programu.

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{ "documentNumber": "FAK/2024/0042" }
```

**Response `200 OK`:** zaktualizowany `FinancialDocumentResponse`

---

### PATCH /finance/documents/{id}/status

**Kiedy używać:** Zmiana statusu płatności faktury przelewem — np. po zaksięgowaniu przelewu admin oznacza fakturę jako `PAID`.

**Uprawnienia:** MANAGER, OWNER

**Dozwolone przejścia statusów:**
- `PENDING → PAID` ✓
- `PENDING → OVERDUE` ✓
- `OVERDUE → PAID` ✓
- `PAID → *` ✗ (zablokowane — by cofnąć, usuń dokument i utwórz nowy)

**Request:**
```json
{ "status": "PAID" }
```

**Response `200 OK`:** zaktualizowany `FinancialDocumentResponse`

---

### DELETE /finance/documents/{id}

**Kiedy używać:** Soft-delete błędnie utworzonego dokumentu. Dokument znika z list (chyba że `includeDeleted=true`), ale pozostaje w bazie danych.

**Uprawnienia:** Tylko OWNER

**Response `204 No Content`**

---

### POST /finance/documents/{id}/restore

**Kiedy używać:** Przywrócenie omyłkowo usuniętego dokumentu.

**Uprawnienia:** Tylko OWNER

**Response `200 OK`:** przywrócony `FinancialDocumentResponse`

---

## 5. Moduł Finance — Kasa

Kasa rejestruje **gotówkę fizycznie w kasie** studia. Saldo zmienia się automatycznie przy każdej płatności gotówkowej (dokument CASH). Admin może ręcznie korygować stan (np. wpłata na początku dnia, wypłata do banku).

### GET /finance/cash

**Kiedy używać:** Widget "Stan kasy" na dashboardzie.

**Uprawnienia:** Wszystkie role

**Response `200 OK`:**
```json
{
  "id": "uuid",
  "balance": 125000,
  "currency": "PLN",
  "updatedAt": "2024-03-15T14:22:00Z"
}
```

> `balance = 125000` → 1250,00 PLN

---

### GET /finance/cash/history

**Kiedy używać:** Historia operacji kasowych — ekran "Kasa", podgląd przepływów gotówki.

**Uprawnienia:** Wszystkie role

**Query params:** `page` (domyślnie 1), `size` (domyślnie 30, max 100)

**Response `200 OK`:**
```json
{
  "operations": [
    {
      "id": "uuid",
      "amount": 10000,
      "balanceBefore": 115000,
      "balanceAfter": 125000,
      "operationType": "PAYMENT_IN",
      "operationTypeLabel": "Wpłata",
      "comment": "Detailing pełny – Toyota Corolla WA 12345",
      "financialDocumentId": "uuid",
      "createdBy": "user-uuid",
      "createdAt": "2024-03-15T14:22:00Z"
    }
  ],
  "total": 87,
  "page": 1,
  "pageSize": 30
}
```

| `operationType` | Kiedy powstaje |
|-----------------|----------------|
| `PAYMENT_IN` | Automatycznie — dokument INCOME + CASH |
| `PAYMENT_OUT` | Automatycznie — dokument EXPENSE + CASH |
| `MANUAL_ADJUSTMENT` | Ręczna korekta przez managera/właściciela |

`amount` jest **podpisany**: dodatni = wpłata, ujemny = wypłata.

---

### POST /finance/cash/adjust

**Kiedy używać:** Manualna korekta kasy — wpłata float przed zmianą, wypłata do banku, korekta rozbieżności po remanencie kasowym.

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{
  "amount": -50000,
  "comment": "Wypłata do banku – depozyt codzienny"
}
```

> `amount` podpisany: `-50000` = wypłata 500 PLN z kasy. Komentarz wymagany dla korekt manualnych.

**Response `200 OK`:** zaktualizowany `CashRegisterResponse`

---

## 6. Moduł Finance — Raporty

### GET /finance/summary

**Kiedy używać:** Dashboard finansowy — kafelki KPI (przychód, koszty, zysk, zaległości).

**Uprawnienia:** Wszystkie role

**Query params:** `dateFrom`, `dateTo` (oba opcjonalne — bez filtrów zwraca dane całościowe)

**Przykład:** `GET /api/v1/finance/summary?dateFrom=2024-01-01&dateTo=2024-03-31`

**Response `200 OK`:**
```json
{
  "dateFrom": "2024-01-01",
  "dateTo": "2024-03-31",
  "totalRevenue": 4800000,
  "totalCosts": 1200000,
  "profit": 3600000,
  "pendingReceivables": 300000,
  "pendingPayables": 80000,
  "overdueReceivables": 2,
  "overduePayables": 0
}
```

| Pole | Znaczenie |
|------|-----------|
| `totalRevenue` | Suma brutto INCOME + PAID (grosze) |
| `totalCosts` | Suma brutto EXPENSE + PAID (grosze) |
| `profit` | `totalRevenue - totalCosts` |
| `pendingReceivables` | INCOME + PENDING (nierozliczone należności) |
| `pendingPayables` | EXPENSE + PENDING (nierozliczone zobowiązania) |
| `overdueReceivables` | **Liczba** przeterminowanych należności |
| `overduePayables` | **Liczba** przeterminowanych zobowiązań |

---

### GET /finance/payment-method-report

**Kiedy używać:** Raport popularności metod płatności — analiza, czy klienci płacą głównie gotówką czy kartą, trend w czasie.

**Uprawnienia:** Wszystkie role

**Query params:**

| Param | Typ | Domyślnie | Opis |
|-------|-----|-----------|------|
| `granularity` | string | `MONTHLY` | `MONTHLY` \| `WEEKLY` \| `DAILY` |
| `dateFrom` | date | — | Zakres od |
| `dateTo` | date | — | Zakres do |
| `documentType` | string | — | Filtr po typie dokumentu |

**Response `200 OK`:**
```json
{
  "granularity": "MONTHLY",
  "dateFrom": "2024-01-01",
  "dateTo": "2024-03-31",
  "documentType": null,
  "periods": [
    {
      "periodLabel": "2024-01",
      "dateFrom": "2024-01-01",
      "dateTo": "2024-01-31",
      "cash":     { "count": 45, "totalNet": 3650000, "totalGross": 4500000 },
      "card":     { "count": 30, "totalNet": 2030000, "totalGross": 2500000 },
      "transfer": { "count": 10, "totalNet": 810000,  "totalGross": 1000000 }
    }
  ],
  "totals": {
    "cash":     { "count": 120, "totalNet": 9750000, "totalGross": 12000000 },
    "card":     { "count": 85,  "totalNet": 6500000, "totalGross": 8000000 },
    "transfer": { "count": 25,  "totalNet": 2030000, "totalGross": 2500000 }
  }
}
```

---

## 7. Moduł KSeF — Dane dostępowe

Przed korzystaniem z synchronizacji KSeF administrator **właściciel** musi podać dane dostępowe: NIP firmy i token API z systemu KSeF MF.

### POST /ksef/credentials

**Kiedy używać:** Pierwsza konfiguracja lub aktualizacja danych KSeF.

**Uprawnienia:** Tylko OWNER

**Request:**
```json
{
  "nip": "1234567890",
  "ksefToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1Ni..."
}
```

**Response `201 Created`:**
```json
{
  "nip": "1234567890",
  "tokenMasked": "eyJ0****...N1Ni",
  "createdAt": "2024-03-15T10:00:00Z",
  "updatedAt": "2024-03-15T10:00:00Z"
}
```

> Token jest maskowany po zapisaniu — `eyJ0****N1Ni`. Frontend powinien wyświetlać tę wartość i nie przechowywać tokenu.

---

### GET /ksef/credentials

**Kiedy używać:** Sprawdzenie czy KSeF jest skonfigurowany (settings ekran). Jeśli `404` — pokaż przycisk "Skonfiguruj KSeF".

**Uprawnienia:** Tylko OWNER

**Response `200 OK`:** jak wyżej | `404 Not Found` gdy brak konfiguracji

---

### DELETE /ksef/credentials

**Kiedy używać:** Odłączenie integracji KSeF.

**Uprawnienia:** Tylko OWNER

**Response `204 No Content`**

---

## 8. Moduł KSeF — Synchronizacja

Synchronizacja pobiera faktury zakupowe z systemu KSeF MF i zapisuje je lokalnie. **Działa automatycznie co godzinę** — ręczne wyzwolenie potrzebne tylko w sytuacjach awaryjnych.

### GET /ksef/sync/status

**Kiedy używać:** Widget statusu synchronizacji w nagłówku ekranu "Koszty KSeF" — pokazuje kiedy ostatnio synkronizowano i czy jest błąd.

**Uprawnienia:** MANAGER, OWNER

**Response `200 OK`:**
```json
{
  "syncStatus": "SUCCESS",
  "lastExpenseSync": "2024-03-15T09:00:00+01:00",
  "lastError": null,
  "updatedAt": "2024-03-15T09:00:05+01:00"
}
```

| `syncStatus` | Opis |
|--------------|------|
| `NEVER_SYNCED` | Nigdy nie synchronizowano |
| `RUNNING` | Synchronizacja trwa (rzadko widoczne — sync jest szybkie) |
| `SUCCESS` | Ostatnia sync zakończona powodzeniem |
| `FAILED` | Ostatnia sync nie powiodła się — `lastError` zawiera szczegóły |

---

### POST /ksef/sync/trigger

**Kiedy używać:** Ręczne wyzwolenie synchronizacji — np. gdy admin wie, że właśnie dostał fakturę od dostawcy i chce ją zobaczyć bez czekania godziny.

**Uprawnienia:** MANAGER, OWNER

**Request:** Pusty body `{}`

**Response `200 OK`:** `KsefSyncStatusResponse` (jak wyżej, aktualny stan po sync)

> Endpoint jest **synchroniczny** — odpowiedź przychodzi po zakończeniu synchronizacji (zwykle kilka sekund).

---

## 9. Moduł KSeF — Dokumenty Kosztowe

Zunifikowany widok kosztów — zarówno pobranych automatycznie z KSeF (`source: KSEF`) jak i dodanych ręcznie (`source: MANUAL`).

### GET /ksef/expenses

**Kiedy używać:** Główna lista dokumentów kosztowych — ekran "Koszty".

**Uprawnienia:** MANAGER, OWNER (oraz EMPLOYEE do odczytu)

**Query params:**

| Param | Typ | Domyślnie | Opis |
|-------|-----|-----------|------|
| `page` | integer | `1` | Numer strony (od 1) |
| `size` | integer | `20` | Wyniki na stronę (max 100) |
| `source` | string | — | `KSEF` \| `MANUAL` — filtr po źródle |
| `paymentStatus` | string | — | `PAID` \| `PENDING` — filtr po statusie płatności |
| `dateFrom` | ISO datetime | — | Filtr po dacie sprzedaży (od) |
| `dateTo` | ISO datetime | — | Filtr po dacie sprzedaży (do) |
| `includeExcluded` | boolean | `false` | `true` — pokaż też ukryte dokumenty |

**Przykład:** `GET /api/v1/ksef/expenses?paymentStatus=PENDING&dateFrom=2024-01-01T00:00:00Z`

**Response `200 OK`:**
```json
{
  "expenses": [
    {
      "id": "uuid",
      "source": "KSEF",
      "ksefNumber": "PL1234567890-20240315-ABCD1234",
      "documentNumber": "FV/2024/0042",
      "saleDate": "2024-03-15T00:00:00+01:00",
      "sellerName": "Chemia Detailingowa Sp. z o.o.",
      "sellerNip": "9876543210",
      "netAmount": 1626.01,
      "grossAmount": 2000.0,
      "vatAmount": 373.99,
      "currency": "PLN",
      "paymentMethod": "PRZELEW",
      "paymentMethodLabel": "Przelew",
      "paymentStatus": "PENDING",
      "status": "ACTIVE",
      "isCorrection": false,
      "fetchedAt": "2024-03-15T09:00:05Z",
      "note": "Zapłacone kartą firmową, do rozliczenia z klientem X"
    }
  ],
  "total": 37,
  "page": 1,
  "pageSize": 20
}
```

**Opis pól `ExpenseResponse`:**

| Pole | Typ | Opis |
|------|-----|------|
| `id` | string (UUID) | Wewnętrzne ID — używane do wszystkich operacji PATCH/DELETE |
| `source` | string | `KSEF` lub `MANUAL` |
| `ksefNumber` | string \| null | Unikalny numer w systemie KSeF. `null` dla MANUAL |
| `documentNumber` | string \| null | Numer faktury nadany przez wystawcę (np. `FV/2024/42`) |
| `saleDate` | ISO datetime \| null | Data sprzedaży |
| `sellerName` | string \| null | Nazwa sprzedawcy |
| `sellerNip` | string \| null | NIP sprzedawcy |
| `netAmount` | number \| null | Wartość netto (PLN, zmiennoprzecinkowa) |
| `grossAmount` | number \| null | Wartość brutto |
| `vatAmount` | number \| null | Kwota VAT |
| `currency` | string | Waluta (domyślnie `PLN`) |
| `paymentMethod` | string \| null | Kod formy płatności z KSeF (np. `PRZELEW`) |
| `paymentMethodLabel` | string \| null | Czytelna etykieta (np. `Przelew`) |
| `paymentStatus` | string | `PAID` lub `PENDING` |
| `status` | string | `ACTIVE` \| `CORRECTED` \| `CANCELLED` \| `EXCLUDED` |
| `isCorrection` | boolean | `true` jeśli to faktura korygująca |
| `fetchedAt` | ISO datetime | Kiedy dokument trafił do CRM |
| `note` | string \| null | Notatka dodana przez admina. `null` gdy brak notatki |

**Znaczenie `status`:**
- `ACTIVE` — aktywna faktura (widoczna i liczona)
- `CORRECTED` — istnieje nowsza korekta do tej faktury
- `CANCELLED` — anulowana przez wystawcę
- `EXCLUDED` — ukryta przez admina (nie wlicza się do statystyk)

---

### POST /ksef/expenses

**Kiedy używać:** Ręczne dodanie faktury kosztowej od dostawcy, który **nie wystawia faktur w KSeF** (np. małe firmy, zagraniczne usługi, faktury gotówkowe).

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{
  "saleDate":      "2024-03-15T12:00:00+01:00",
  "documentNumber": "FV/2024/0777",
  "sellerName":    "AutoChemia Jan Nowak",
  "sellerNip":     "5555555555",
  "netAmount":     243.90,
  "grossAmount":   300.00,
  "paymentMethod": "GOTOWKA"
}
```

| Pole | Typ | Wymagany | Opis |
|------|-----|----------|------|
| `saleDate` | ISO datetime | — | Data sprzedaży/faktury |
| `documentNumber` | string | — | Numer faktury od dostawcy |
| `sellerName` | string | — | Nazwa sprzedawcy |
| `sellerNip` | string | — | NIP sprzedawcy |
| `netAmount` | number | — | Wartość netto PLN |
| `grossAmount` | number | — | Wartość brutto PLN. **Nie może być ujemna** |
| `paymentMethod` | string | — | Patrz [słownik PaymentForm](#paymentform) |

Dokument tworzony z `paymentStatus = PENDING` — do ręcznej zmiany po opłaceniu.

**Response `201 Created`:** `ExpenseResponse`

---

### POST /ksef/expenses/sync

**Kiedy używać:** Ręczne pobranie faktur z KSeF za podany zakres dat — alternatywa dla automatycznej sync, gdy potrzeba zaimportować starsze dokumenty lub wybrany przedział.

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{
  "dateFrom": "2024-01-01T00:00:00+01:00",
  "dateTo":   "2024-03-31T23:59:59+01:00"
}
```

**Response `200 OK`:**
```json
{
  "fetched": 23,
  "skipped": 5
}
```

`skipped` — liczba faktur pominiętych (już istniały w bazie, zduplikowane numery KSeF).

---

### PATCH /ksef/expenses/{id}/exclude

**Kiedy używać:** Ukrycie faktury ze statystyk bez usuwania — np. duplikat, faktura niezwiązana z działalnością studia, testowa faktura od dostawcy.

> **Ważne:** Faktury KSeF są nieusuwalne z powodów audytowych. "Ukrycie" to jedyna możliwa operacja "usunięcia".

**Uprawnienia:** MANAGER, OWNER

**Response `204 No Content`**

Błędy:
- `400` jeśli faktura ma status `CANCELLED`

---

### PATCH /ksef/expenses/{id}/restore

**Kiedy używać:** Przywrócenie omyłkowo ukrytej faktury.

**Uprawnienia:** MANAGER, OWNER

**Response `204 No Content`**

---

### PATCH /ksef/expenses/{id}/payment-status

**Kiedy używać:** Oznaczenie faktury jako zapłaconej (lub cofnięcie do PENDING przy korekcie).

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{ "paymentStatus": "PAID" }
```

Dozwolone wartości: `PAID` | `PENDING`

**Response `200 OK`:** zaktualizowany `ExpenseResponse`

---

### PATCH /ksef/expenses/{id}/note

**Kiedy używać:** Dodanie lub edycja notatki na dokumencie kosztowym (dowolny tekst — kontekst dla admina/księgowości).

**Uprawnienia:** MANAGER, OWNER

**Request:**
```json
{ "note": "Zapłacone kartą firmową, do rozliczenia z klientem X" }
```

`note` nie może być pusta ani składać się wyłącznie z białych znaków.

**Response `200 OK`:** zaktualizowany `ExpenseResponse`

Błędy:
- `400` gdy `note` jest puste

---

### DELETE /ksef/expenses/{id}/note

**Kiedy używać:** Usunięcie notatki z dokumentu kosztowego.

**Uprawnienia:** MANAGER, OWNER

**Response `204 No Content`**

---

### DELETE /ksef/expenses/{id}

**Kiedy używać:** Usunięcie **wyłącznie** faktur dodanych ręcznie (`source = MANUAL`) — np. wprowadzono błędną fakturę.

> Faktury z KSeF (`source = KSEF`) nie mogą być usunięte — API zwróci `400`. Użyj `PATCH .../exclude` zamiast tego.

**Uprawnienia:** Tylko OWNER

**Response `204 No Content`**

Błędy:
- `400` gdy próba usunięcia faktury KSeF

---

## 10. Moduł KSeF — Statystyki kosztowe

### GET /ksef/statistics

**Kiedy używać:** Roczny panel kosztów — wizualizacje miesięczne (wykres słupkowy kosztów), karta z rocznymi sumami, informacja o stanie synchronizacji.

**Uprawnienia:** Wszystkie role

**Query params:** `year` — rok (integer, np. `2024`)

**Przykład:** `GET /api/v1/ksef/statistics?year=2024`

**Response `200 OK`:**
```json
{
  "year": 2024,
  "totals": {
    "costsGross": 48000.00,
    "costsNet":   39024.39,
    "costsVat":   8975.61,
    "expenseCount": 87,
    "correctionCount": 3
  },
  "monthly": [
    {
      "month": "2024-01",
      "costsGross": 4200.00,
      "costsNet": 3414.63,
      "costsVat": 785.37,
      "expenseCount": 8,
      "correctionCount": 0
    },
    {
      "month": "2024-02",
      "costsGross": 3800.00,
      "costsNet": 3089.43,
      "costsVat": 710.57,
      "expenseCount": 7,
      "correctionCount": 1
    }
  ],
  "dataAsOf": "2024-03-15T09:00:05+01:00",
  "syncStatus": "SUCCESS"
}
```

| Pole | Opis |
|------|------|
| `totals.costsGross` | Suma brutto aktywnych kosztów (bez EXCLUDED, CANCELLED) |
| `totals.expenseCount` | Liczba aktywnych faktur kosztowych |
| `totals.correctionCount` | Liczba faktur korygujących |
| `monthly` | Lista 12 miesięcy dla danego roku (0 gdy brak danych) |
| `dataAsOf` | Timestamp ostatniej udanej synchronizacji z KSeF |
| `syncStatus` | Aktualny status sync (`NEVER_SYNCED` \| `SUCCESS` \| `FAILED` \| `RUNNING`) |

---

## 11. Słownik wartości enum

### DocumentType

| Wartość | Label | Prefix dokumentu |
|---------|-------|-----------------|
| `RECEIPT` | Paragon | `PAR/YYYY/NNNN` |
| `INVOICE` | Faktura | `FAK/YYYY/NNNN` |
| `OTHER` | Dokument | `DOK/YYYY/NNNN` |

### DocumentDirection

| Wartość | Label |
|---------|-------|
| `INCOME` | Przychód |
| `EXPENSE` | Koszt |

### DocumentStatus

| Wartość | Label | Kiedy |
|---------|-------|-------|
| `PAID` | Opłacony | CASH/CARD = zawsze; TRANSFER = po oznaczeniu |
| `PENDING` | Oczekujący | TRANSFER przed opłaceniem |
| `OVERDUE` | Przeterminowany | TRANSFER po minięciu `dueDate` (auto) |

### PaymentMethod (Finance)

| Wartość | Label | Efekt na kasę |
|---------|-------|---------------|
| `CASH` | Gotówka | Aktualizuje kasę |
| `CARD` | Karta | Brak |
| `TRANSFER` | Przelew | Brak |
| `OTHER` | Inne | Brak |

### PaymentForm (KSeF)

Kody zgodne ze schematem FA(2) KSeF (`<FormaPlatnosci>`):

| Wartość | KSeF kod | Label |
|---------|----------|-------|
| `GOTOWKA` | 1 | Gotówka |
| `KARTA` | 2 | Karta |
| `CZEK` | 3 | Czek |
| `BON` | 4 | Bon / voucher |
| `KREDYT` | 5 | Kredyt |
| `PRZELEW` | 6 | Przelew |
| `MOBILNA` | 7 | Mobilna |

### DocumentSource (Finance)

| Wartość | Label |
|---------|-------|
| `VISIT` | Wizyta (auto-created przy zamknięciu wizyty) |
| `MANUAL` | Ręcznie |

### CashOperationType

| Wartość | Label |
|---------|-------|
| `PAYMENT_IN` | Wpłata |
| `PAYMENT_OUT` | Wypłata |
| `MANUAL_ADJUSTMENT` | Korekta manualna |

### ExpenseStatus (KSeF)

| Wartość | Kiedy wyświetlać |
|---------|-----------------|
| `ACTIVE` | Zielony chip — aktywna |
| `CORRECTED` | Szary chip — zastąpiona korektą |
| `CANCELLED` | Czerwony chip — anulowana |
| `EXCLUDED` | Szary dashed chip — ukryta przez admina |

---

## 12. Przepływy UI — gotowe scenariusze

### Ekran "Dokumenty Przychodowe"

```
1. Załaduj listę:
   GET /api/v1/finance/documents?direction=INCOME&page=1&size=20

2. Filtry (onChange):
   GET /api/v1/finance/documents?direction=INCOME&status=PENDING&dateFrom=...

3. Kliknięcie w wiersz:
   GET /api/v1/finance/documents/{id}

4. Zmiana statusu przelewu na opłacony:
   PATCH /api/v1/finance/documents/{id}/status
   Body: { "status": "PAID" }

5. Edycja numeru dokumentu (po wystawieniu w zewn. systemie):
   PATCH /api/v1/finance/documents/{id}
   Body: { "documentNumber": "FAK/2024/0099" }

6. Usunięcie (tylko OWNER):
   DELETE /api/v1/finance/documents/{id}
   → Pokaż potwierdzenie przed akcją!
```

### Ekran "Koszty KSeF"

```
1. Sprawdź status sync (header widget):
   GET /api/v1/ksef/sync/status

2. Załaduj listę:
   GET /api/v1/ksef/expenses?page=1&size=20

3. Filtry:
   GET /api/v1/ksef/expenses?source=KSEF&paymentStatus=PENDING

4. Pokaż ukryte:
   GET /api/v1/ksef/expenses?includeExcluded=true

5. Ukryj fakturę:
   PATCH /api/v1/ksef/expenses/{id}/exclude

6. Oznacz jako zapłaconą:
   PATCH /api/v1/ksef/expenses/{id}/payment-status
   Body: { "paymentStatus": "PAID" }

7. Dodaj ręcznie fakturę:
   POST /api/v1/ksef/expenses
   → Otwórz modal z formularzem

8. Ręczna sync:
   POST /api/v1/ksef/sync/trigger
   → Pokaż spinner, odśwież listę po odpowiedzi
```

### Ekran "Kasa"

```
1. Widget salda:
   GET /api/v1/finance/cash

2. Historia (paginowana tabela):
   GET /api/v1/finance/cash/history?page=1&size=30

3. Korekta manualna (modal z polem kwota + opis):
   POST /api/v1/finance/cash/adjust
   Body: { "amount": -50000, "comment": "Wypłata do banku" }
   → Odśwież stan kasy po odpowiedzi
```

### Widget Dashboard — KPI finansowe

```
1. GET /api/v1/finance/summary?dateFrom=2024-01-01&dateTo=2024-12-31

Wyświetl:
  totalRevenue / 100 → "Przychody: 48 000,00 PLN"
  totalCosts / 100 → "Koszty: 12 000,00 PLN"
  profit / 100 → "Zysk: 36 000,00 PLN"

  overdueReceivables > 0 → badge ostrzeżenia "2 przeterminowane należności"
```

### Inicjalizacja KSeF przez właściciela

```
1. Sprawdź czy skonfigurowane:
   GET /api/v1/ksef/credentials
   → 404: pokaż banner "Skonfiguruj KSeF"
   → 200: pokaż NIP i zamaskowany token

2. Konfiguracja:
   POST /api/v1/ksef/credentials
   Body: { "nip": "...", "ksefToken": "..." }

3. Pierwsza sync (opcjonalnie historyczna):
   POST /api/v1/ksef/expenses/sync
   Body: { "dateFrom": "2024-01-01T00:00:00Z", "dateTo": "2024-12-31T23:59:59Z" }
   → Pokaż spinner + wynik { fetched: 87, skipped: 0 }

4. Sprawdź status:
   GET /api/v1/ksef/sync/status
```
