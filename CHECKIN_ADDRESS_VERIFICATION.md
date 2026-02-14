# Weryfikacja Obsługi Adresu i Danych Firmowych w Check-In

**Data weryfikacji:** 2026-02-14
**Status:** ✅ GOTOWE - Backend w pełni obsługuje nowe pola

---

## Podsumowanie

Backend API już w pełni obsługuje pola `homeAddress` i `company` w procesie check-in (tworzenie wizyty z rezerwacji). Nie były wymagane żadne zmiany w kodzie.

---

## Zweryfikowane Komponenty

### 1. ✅ DTOs Request

**Plik:** `src/main/kotlin/pl/detailing/crm/checkin/CheckinController.kt:202-237`

Struktura `CustomerDataRequest` zawiera:
- `homeAddress: HomeAddressRequest?` - adres domowy klienta
- `company: CompanyRequest?` - dane firmowe klienta

#### HomeAddressRequest
```kotlin
data class HomeAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)
```

#### CompanyRequest
```kotlin
data class CompanyRequest(
    val name: String,
    val nip: String,
    val regon: String?,
    val address: CompanyAddressRequest
)
```

#### CompanyAddressRequest
```kotlin
data class CompanyAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)
```

---

### 2. ✅ Logika Tworzenia Klienta (mode: NEW)

**Plik:** `src/main/kotlin/pl/detailing/crm/checkin/CreateVisitFromReservationHandler.kt:274-346`

Metoda `createCustomer()` prawidłowo:
- Mapuje `homeAddress` z request do obiektu domenowego `HomeAddress` (linie 313-320)
- Mapuje `company` z request do obiektu domenowego `CompanyData` (linie 321-333)
- Zapisuje dane do bazy poprzez `CustomerEntity.fromDomain()` (linia 342)

---

### 3. ✅ Logika Aktualizacji Klienta (mode: UPDATE)

**Plik:** `src/main/kotlin/pl/detailing/crm/checkin/CreateVisitFromReservationHandler.kt:388-478`

Metoda `updateCustomerIfNeeded()` prawidłowo:
- Wykrywa zmiany w `homeAddress` (linie 411-419)
- Wykrywa zmiany w `company` (linie 421-432)
- Aktualizuje dane tylko gdy się zmieniły (linie 434-477)
- Obsługuje przypadki, gdy pola są `null` (usuwanie danych)

---

### 4. ✅ Model Domenowy

**Plik:** `src/main/kotlin/pl/detailing/crm/customer/domain/Customer.kt`

Klasa `Customer` zawiera:
```kotlin
data class Customer(
    // ... podstawowe pola
    val homeAddress: HomeAddress?,
    val companyData: CompanyData?,
    // ...
)

data class HomeAddress(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyData(
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddress?
)

data class CompanyAddress(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)
```

---

### 5. ✅ Encja Bazodanowa

**Plik:** `src/main/kotlin/pl/detailing/crm/customer/infrastructure/CustomerEntity.kt:43-74`

Tabela `customers` zawiera kolumny:

**Adres domowy:**
- `home_address_street`
- `home_address_city`
- `home_address_postal_code`
- `home_address_country`

**Dane firmowe:**
- `company_name`
- `company_nip`
- `company_regon`
- `company_address_street`
- `company_address_city`
- `company_address_postal_code`
- `company_address_country`

---

### 6. ✅ Mapowanie Domain ↔ Entity

**Plik:** `src/main/kotlin/pl/detailing/crm/customer/infrastructure/CustomerEntity.kt:94-160`

Metody mapowania prawidłowo konwertują:
- `toDomain()` - odtwarza obiekty `HomeAddress` i `CompanyData` z kolumn bazy (linie 101-125)
- `fromDomain()` - mapuje obiekty domenowe do kolumn bazy (linie 142-152)

---

### 7. ✅ Response DTOs

**Plik:** `src/main/kotlin/pl/detailing/crm/customer/CustomerController.kt:523-556`

Response zawiera:
```kotlin
data class CustomerResponse(
    // ... podstawowe pola
    val homeAddress: HomeAddressResponse?,
    val companyData: CompanyDataResponse?,
    // ...
)

data class HomeAddressResponse(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyDataResponse(
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddressResponse?
)
```

---

## Przepływ Danych

### Wariant NEW (nowy klient)

```
Frontend Request:
{
  "mode": "NEW",
  "newData": {
    "firstName": "Jan",
    "lastName": "Kowalski",
    "homeAddress": { "street": "...", "city": "...", ... },
    "company": { "name": "...", "nip": "...", ... }
  }
}
    ↓
CheckinController (linie 67-80)
    ↓
CreateVisitFromReservationHandler.createCustomer() (linie 274-346)
    ↓
Customer (model domenowy) z homeAddress i companyData
    ↓
CustomerEntity.fromDomain() (linie 135-159)
    ↓
Baza danych - kolumny home_address_* i company_*
```

### Wariant UPDATE (aktualizacja klienta)

```
Frontend Request:
{
  "mode": "UPDATE",
  "id": "customer_123",
  "updateData": {
    "firstName": "Jan",
    "lastName": "Kowalski",
    "homeAddress": { "street": "...", "city": "...", ... },
    "company": { "name": "...", "nip": "...", ... }
  }
}
    ↓
CheckinController (linie 81-95)
    ↓
CreateVisitFromReservationHandler.updateCustomerIfNeeded() (linie 388-478)
    ↓
Porównanie z istniejącymi danymi
    ↓
Aktualizacja tylko zmienionych pól (linie 441-471)
    ↓
Baza danych - zaktualizowane kolumny
```

---

## Endpoint

**POST** `/api/checkin/reservation-to-visit`

**Autoryzacja:** OWNER, MANAGER

**Request Body:**
```json
{
  "reservationId": "uuid",
  "customer": {
    "mode": "NEW" | "UPDATE" | "EXISTING",
    "id": "uuid (dla UPDATE i EXISTING)",
    "newData": {
      "firstName": "string",
      "lastName": "string",
      "phone": "string",
      "email": "string",
      "homeAddress": {
        "street": "string",
        "city": "string",
        "postalCode": "string",
        "country": "string"
      },
      "company": {
        "name": "string",
        "nip": "string",
        "regon": "string",
        "address": {
          "street": "string",
          "city": "string",
          "postalCode": "string",
          "country": "string"
        }
      }
    },
    "updateData": { /* ta sama struktura jak newData */ }
  },
  "vehicle": { /* ... */ },
  "services": [ /* ... */ ],
  "technicalState": { /* ... */ },
  "damagePoints": [ /* ... */ ],
  "vehicleHandoff": "string"
}
```

---

## Wnioski

✅ **Backend jest w pełni gotowy do obsługi nowych pól.**

Wszystkie wymagane komponenty są już zaimplementowane:
- DTOs request i response
- Logika biznesowa dla wszystkich trybów (NEW, UPDATE, EXISTING)
- Model domenowy
- Encje bazodanowe z wszystkimi kolumnami
- Prawidłowe mapowanie między warstwami

**Nie są wymagane żadne dodatkowe zmiany w backendzie.**

---

## Powiązane Pliki

1. `src/main/kotlin/pl/detailing/crm/checkin/CheckinController.kt` - endpoint i DTOs
2. `src/main/kotlin/pl/detailing/crm/checkin/CreateVisitFromReservationHandler.kt` - logika biznesowa
3. `src/main/kotlin/pl/detailing/crm/customer/domain/Customer.kt` - model domenowy
4. `src/main/kotlin/pl/detailing/crm/customer/infrastructure/CustomerEntity.kt` - encja i mapowanie
5. `src/main/kotlin/pl/detailing/crm/customer/CustomerController.kt` - response DTOs
