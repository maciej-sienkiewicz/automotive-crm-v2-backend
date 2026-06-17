# API Contract — System ról i uprawnień

> Wersja po migracji: usunięcie hardkodowanego enuma `UserRole` (OWNER/MANAGER/DETAILER).  
> Dotyczy wszystkich zmian wpływających na frontend.

---

## 1. Przegląd modelu

### Poprzedni model (nieaktualny)
Każdy użytkownik miał przypisaną jedną z trzech hardkodowanych ról: `OWNER`, `MANAGER`, `DETAILER`. Rola decydowała o dostępie do endpointów.

### Nowy model
| Pojęcie | Opis |
|---|---|
| **isOwner** | Właściciel studia — jedno konto na studio, ma dostęp do wszystkiego, w tym do rozliczeń i subskrypcji. |
| **Rola niestandardowa** | Studio tworzy własne role z dowolnymi uprawnieniami (np. "Recepcja", "Mechanik"). Przypisywana do konta pracownika. |
| **Uprawnienie (Permission)** | Atomowa akcja np. `CUSTOMERS_CREATE`, `FINANCE_VIEW_INVOICES`. Endpoint zwraca 403 jeśli rola użytkownika nie zawiera wymaganego uprawnienia. |

---

## 2. Pole `role` w odpowiedziach

### Login / `/api/v1/auth/me`

Pole `role` w odpowiedzi przyjmuje teraz tylko dwie wartości:

```
"OWNER"  — właściciel studia
"USER"   — każdy inny użytkownik (pracownik)
```

**Poprzednio:** `"OWNER"` | `"MANAGER"` | `"DETAILER"`  
**Teraz:** `"OWNER"` | `"USER"`

Przykład odpowiedzi po zalogowaniu:
```json
{
  "success": true,
  "user": {
    "userId": "uuid",
    "studioId": "uuid",
    "email": "jan@studio.pl",
    "role": "USER",
    "firstName": "Jan",
    "lastName": "Kowalski",
    "subscriptionStatus": "ACTIVE"
  }
}
```

> **Ważne:** Pole `role` służy wyłącznie do wyświetlania (np. odróżnienia właściciela). Nie używaj go do ukrywania elementów UI — zamiast tego bazuj na uprawnieniach zwracanych przez `/api/v1/roles/permissions`.

---

## 3. Endpointy — Zarządzanie rolami

Baza URL: `/api/v1/roles`

### `GET /api/v1/roles/permissions`
Zwraca katalog wszystkich dostępnych uprawnień, pogrupowanych według modułu.  
Używany do budowania UI edytora roli (checkboxy per uprawnienie).

**Autoryzacja:** każdy zalogowany użytkownik.

**Odpowiedź `200`:**
```json
[
  {
    "module": "CALENDAR",
    "displayName": "Kalendarz",
    "featureKey": null,
    "permissions": [
      { "code": "CALENDAR_VIEW",   "displayName": "Podgląd kalendarza" },
      { "code": "CALENDAR_MANAGE", "displayName": "Zarządzanie terminami w kalendarzu" }
    ]
  },
  {
    "module": "FINANCE",
    "displayName": "Finanse",
    "featureKey": "FINANCE",
    "permissions": [
      { "code": "FINANCE_VIEW_INVOICES",      "displayName": "Podgląd faktur" },
      { "code": "FINANCE_CREATE_INVOICE",     "displayName": "Wystawianie faktur" },
      { "code": "FINANCE_VIEW_REPORTS",       "displayName": "Podgląd raportów finansowych" },
      { "code": "FINANCE_MANAGE_CASH_REGISTER","displayName": "Zarządzanie kasą fiskalną" }
    ]
  }
]
```

> Pole `featureKey` — gdy nie-null, moduł wymaga aktywnej funkcji w planie subskrypcyjnym. Możesz go użyć do wizualnego wyszarzenia uprawnień niedostępnych w planie.

---

### `GET /api/v1/roles`
Lista ról studia.

**Autoryzacja:** każdy zalogowany użytkownik.

**Odpowiedź `200`:**
```json
[
  {
    "id": "uuid",
    "name": "Recepcja",
    "description": "Obsługa klientów i kalendarza",
    "permissions": [
      {
        "code": "CALENDAR_VIEW",
        "displayName": "Podgląd kalendarza",
        "module": "CALENDAR",
        "moduleDisplayName": "Kalendarz"
      }
    ],
    "createdAt": "2024-01-01T10:00:00Z",
    "updatedAt": "2024-01-01T10:00:00Z"
  }
]
```

---

### `GET /api/v1/roles/{roleId}`
Szczegóły jednej roli.

**Autoryzacja:** każdy zalogowany użytkownik.  
**Odpowiedź:** jak wyżej (pojedynczy obiekt).

---

### `POST /api/v1/roles`
Tworzenie nowej roli.

**Autoryzacja:** wymaga uprawnienia `EMPLOYEES_MANAGE` (właściciel zawsze może).

**Request body:**
```json
{
  "name": "Recepcja",
  "description": "Opcjonalny opis",
  "permissions": ["CALENDAR_VIEW", "CUSTOMERS_VIEW", "CUSTOMERS_CREATE"]
}
```

**Odpowiedź `201`:**
```json
{ "roleId": "uuid" }
```

**Błędy:**
- `400` — nieznany kod uprawnienia w tablicy `permissions`
- `403` — brak uprawnienia `EMPLOYEES_MANAGE`

---

### `PUT /api/v1/roles/{roleId}`
Aktualizacja roli (nazwa, opis, lista uprawnień).

**Autoryzacja:** wymaga `EMPLOYEES_MANAGE`.

**Request body:** identyczny jak POST.  
**Odpowiedź `200`:** zaktualizowany obiekt roli.

---

### `DELETE /api/v1/roles/{roleId}`
Usunięcie roli.

**Autoryzacja:** wyłącznie właściciel (`isOwner = true`).

**Odpowiedź `204`:** brak treści.

> Przed usunięciem roli odepnij ją od użytkowników (`PUT /api/v1/roles/assign/{userId}` z `roleId: null`), inaczej użytkownicy stracą wszystkie uprawnienia.

---

### `PUT /api/v1/roles/assign/{userId}`
Przypisanie lub odpięcie roli niestandardowej od konta użytkownika.

**Autoryzacja:** wymaga `EMPLOYEES_MANAGE`.

**Path param:** `userId` — UUID konta użytkownika (nie employeeId!).

**Request body:**
```json
{ "roleId": "uuid" }
```
Aby odpiąć rolę, przekaż `null`:
```json
{ "roleId": null }
```

**Odpowiedź `204`:** brak treści.

**Błędy:**
- `400` — próba przypisania roli właścicielowi studia (właściciel ma zawsze pełny dostęp)
- `404` — użytkownik lub rola nie istnieje

---

## 4. Endpointy — Konta pracowników (breaking changes)

### `POST /api/v1/employees` — tworzenie pracownika

Usunięte pola z request body (nie wysyłaj ich):
- ~~`createAccount`~~ 
- ~~`accountEmail`~~
- ~~`accountRole`~~

Konto użytkownika tworzy się teraz osobno przez `POST /api/v1/employees/{id}/account`.

**Nowe request body:**
```json
{
  "firstName": "Jan",
  "lastName": "Kowalski",
  "phone": "+48600000000",
  "email": "jan@studio.pl",
  "position": "Detailer",
  "hireDate": "2024-01-01",
  "notes": null
}
```

---

### `POST /api/v1/employees/{employeeId}/account` — tworzenie konta

Usunięte pole z request body:
- ~~`role`~~ (wcześniej: `"MANAGER"` | `"DETAILER"`)

Nowe konta tworzone są bez przypisanej roli (brak uprawnień do czegokolwiek). Po utworzeniu konta przypisz rolę przez `PUT /api/v1/roles/assign/{userId}`.

**Nowe request body:**
```json
{
  "email": "jan@studio.pl"
}
```

**Odpowiedź `201`:**
```json
{ "userId": "uuid" }
```

Użyj zwróconego `userId` do wywołania `PUT /api/v1/roles/assign/{userId}`.

---

### `GET /api/v1/employees/{employeeId}` — pole `account.role`

Pole `account.role` zwraca teraz:
- `"OWNER"` — jeśli konto to właściciel studia
- `"EMPLOYEE"` — każdy inny użytkownik

**Poprzednio:** `"OWNER"` | `"MANAGER"` | `"DETAILER"`  
**Teraz:** `"OWNER"` | `"EMPLOYEE"`

> Docelowo to pole powinno zwracać nazwę przypisanej roli niestandardowej. Na razie traktuj `"EMPLOYEE"` jako sygnał, żeby pobrać przypisaną rolę przez `GET /api/v1/roles` i porównać z `customRoleId` użytkownika.

---

## 5. Autoryzacja — tabela uprawnień

Poniższa tabela pokazuje jakie uprawnienie jest wymagane dla każdej grupy operacji. Właściciel zawsze ma dostęp.

| Moduł | Uprawnienie | Co chroni |
|---|---|---|
| Kalendarz | `CALENDAR_VIEW` | Podgląd kalendarza |
| Kalendarz | `CALENDAR_MANAGE` | Tworzenie/edycja/usuwanie terminów i kolorów |
| Wizyty | `VISITS_VIEW` | Podgląd wizyt |
| Wizyty | `VISITS_CREATE` | Tworzenie i edycja wizyt, usług |
| Wizyty | `VISITS_CHANGE_STATUS` | Zmiana statusu wizyty, check-in, check-out |
| Wizyty | `VISITS_VIEW_PRICES` | Widok cen usług |
| Klienci | `CUSTOMERS_VIEW` | Lista klientów |
| Klienci | `CUSTOMERS_VIEW_PERSONAL_DATA` | Dane osobowe klienta |
| Klienci | `CUSTOMERS_CREATE` | Dodawanie klientów, zgody |
| Klienci | `CUSTOMERS_EDIT` | Edycja klientów i zgód |
| Klienci | `CUSTOMERS_DELETE` | Usuwanie klientów |
| Pojazdy | `VEHICLES_CREATE` | Dodawanie pojazdów |
| Pojazdy | `VEHICLES_EDIT` | Edycja pojazdów, przypisywanie właścicieli |
| Pojazdy | `VEHICLES_DELETE` | Usuwanie pojazdów |
| Dokumenty | `DOCUMENTS_CREATE` | Tworzenie protokołów i szablonów |
| Dokumenty | `DOCUMENTS_SIGN` | Podpisywanie dokumentów |
| Finanse | `FINANCE_VIEW_INVOICES` | Podgląd faktur |
| Finanse | `FINANCE_CREATE_INVOICE` | Wystawianie faktur, KSeF, wydatki |
| Finanse | `FINANCE_VIEW_REPORTS` | Raporty finansowe |
| Finanse | `FINANCE_MANAGE_CASH_REGISTER` | Kasa fiskalna |
| Pracownicy | `EMPLOYEES_VIEW` | Podgląd listy pracowników |
| Pracownicy | `EMPLOYEES_MANAGE` | Tworzenie/edycja pracowników, ról, ustawień studia |
| Pracownicy | `EMPLOYEES_MANAGE_ACCOUNTS` | Tworzenie/blokowanie/zmiana hasła kont |
| Pracownicy | `EMPLOYEES_VIEW_PAYROLL` | Podgląd listy płac |
| Pracownicy | `EMPLOYEES_MANAGE_PAYROLL` | Umowy, wynagrodzenia, czas pracy, urlopy, listy płac |
| Komunikacja | `COMMUNICATION_VIEW_LOGS` | Historia komunikacji |
| Komunikacja | `COMMUNICATION_SEND_SMS` | Wysyłanie SMS, kampanie, kredyty SMS |
| Komunikacja | `COMMUNICATION_SEND_EMAIL` | Automatyzacja e-mail |
| Statystyki | `STATISTICS_VIEW` | Statystyki i kategorie |
| Leady | `LEADS_VIEW` | Podgląd leadów |
| Leady | `LEADS_MANAGE` | Zarządzanie leadami |
| Zadania | `TASKS_VIEW` | Podgląd zadań |
| Zadania | `TASKS_MANAGE` | Zarządzanie zadaniami |
| Zadania | `TASKS_ASSIGN` | Przypisywanie zadań |

### Operacje wyłącznie dla właściciela (403 dla pozostałych, niezależnie od roli)

- `DELETE /api/v1/roles/{roleId}` — usunięcie roli
- `DELETE /api/v1/employees/{id}` — zakończenie zatrudnienia
- `DELETE /api/v1/employees/{id}/account` — usunięcie konta
- `POST /api/v1/sms-credits/purchase` — zakup kredytów SMS
- `DELETE /api/v1/leads/{id}` — usunięcie leada
- `DELETE /api/v1/visits/{id}/archive` — archiwizacja wizyty
- Wszystkie endpointy `/api/v1/subscription/*` — zarządzanie subskrypcją
- Wszystkie endpointy `/api/v1/entitlements/admin/*`

---

## 6. Obsługa błędów autoryzacji

| Kod | Znaczenie |
|---|---|
| `401 Unauthorized` | Brak sesji / token wygasł — przekieruj na login |
| `403 Forbidden` | Zalogowany użytkownik nie ma wymaganego uprawnienia — pokaż komunikat, nie przekierowuj |

Ciało odpowiedzi `403`:
```json
{
  "status": 403,
  "error": "Forbidden",
  "message": "Brak uprawnienia: Tworzenie klientów"
}
```

---

## 7. Rekomendowany flow dla UI

### Przy starcie aplikacji po zalogowaniu:
1. `GET /api/v1/roles` — pobierz listę ról studia
2. `GET /api/v1/auth/me` — sprawdź czy `role === "OWNER"`
3. Jeśli nie-właściciel: pobierz przypisaną rolę użytkownika i jej uprawnienia — używaj ich do warunkowego renderowania przycisków/sekcji

### Przy tworzeniu nowego pracownika z kontem:
1. `POST /api/v1/employees` — utwórz profil pracownika
2. `POST /api/v1/employees/{employeeId}/account` — utwórz konto (e-mail)
3. `PUT /api/v1/roles/assign/{userId}` — przypisz rolę do konta

### Przy zarządzaniu rolami:
1. `GET /api/v1/roles/permissions` — pobierz katalog uprawnień (raz, można cachować)
2. `GET /api/v1/roles` — aktualne role studia
3. `POST /api/v1/roles` — nowa rola
4. `PUT /api/v1/roles/{id}` — edycja
5. `DELETE /api/v1/roles/{id}` — usunięcie (tylko właściciel)
