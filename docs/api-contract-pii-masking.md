# Kontrakt API — Maskowanie danych osobowych (PII) i graf zależności uprawnień

Dokument dla zespołu frontendu. Opisuje dwie zmiany backendu:

1. **Graf zależności uprawnień** — pole `requires` w katalogu uprawnień (stream 1).
2. **Maskowanie danych osobowych (PII)** — sterowane uprawnieniem `CUSTOMERS_VIEW_PERSONAL_DATA` (stream 2).

---

## 1. Graf zależności uprawnień

### Endpoint: `GET /api/v1/roles/permissions`

Każdy wpis uprawnienia w katalogu ma teraz dodatkowe pole `requires` — listę kodów uprawnień,
które dane uprawnienie **bezpośrednio** wymaga (jeden poziom zależności).

```jsonc
[
  {
    "module": "CUSTOMERS",
    "displayName": "Klienci",
    "featureKey": null,
    "permissions": [
      {
        "code": "CUSTOMERS_EDIT",
        "displayName": "Edycja klientów",
        "requires": ["CUSTOMERS_VIEW_PERSONAL_DATA"]   // ⬅ NOWE
      },
      {
        "code": "CUSTOMERS_VIEW_PERSONAL_DATA",
        "displayName": "Podgląd danych osobowych klienta",
        "requires": ["CUSTOMERS_VIEW"]                  // ⬅ NOWE
      },
      {
        "code": "CUSTOMERS_VIEW",
        "displayName": "Podgląd listy klientów",
        "requires": []
      }
    ]
  }
]
```

### Zachowanie frontendu w edytorze ról

- `requires` zawiera **tylko bezpośrednich** poprzedników. Przechodniość (np. `CUSTOMERS_EDIT`
  → `CUSTOMERS_VIEW_PERSONAL_DATA` → `CUSTOMERS_VIEW`) frontend powinien rozwinąć sam, idąc
  rekurencyjnie po grafie, albo polegać na tym, że backend i tak domknie zbiór przy zapisie.
- **Zaznaczenie** uprawnienia powinno automatycznie zaznaczyć (i zablokować odznaczenie)
  wszystkich jego poprzedników z `requires` — kaskadowo.
- **Odznaczenie** uprawnienia bazowego powinno być zablokowane, dopóki istnieje zaznaczone
  uprawnienie, które go wymaga (inaczej backend i tak je z powrotem doda).
- Zależności mogą **przekraczać moduły** (np. `VISITS_CREATE` wymaga `CUSTOMERS_CREATE` i
  `VEHICLES_CREATE`). UI musi obsłużyć kaskadę między sekcjami modułów.

### `POST /api/v1/roles` i `PUT /api/v1/roles/{roleId}`

- Request bez zmian: `{ "name", "description", "permissions": ["CODE", ...] }`.
- **Uwaga:** backend **domyka** zbiór uprawnień przy zapisie. Jeśli wyślesz `["CUSTOMERS_EDIT"]`,
  rola w odpowiedzi (`GET`) będzie miała `["CUSTOMERS_EDIT", "CUSTOMERS_VIEW_PERSONAL_DATA",
  "CUSTOMERS_VIEW"]`. To oczekiwane — UI powinno odświeżyć stan z odpowiedzi po zapisie.

---

## 2. Maskowanie danych osobowych (PII)

### Reguła ogólna

Gdy użytkownik **nie ma** uprawnienia `CUSTOMERS_VIEW_PERSONAL_DATA`:

- Widoki nadal się ładują (200 OK), ale pola PII są **zamaskowane**.
- Backend zwraca stały token `"***"` zamiast prawdziwej wartości (string) **lub** `null`
  (adresy, URL-e do plików).
- **Frontend powinien dodatkowo zablurować** te pola wizualnie — nie polegać wyłącznie na
  treści z serwera.
- Właściciel studia (`isOwner: true`) **zawsze** widzi pełne dane (omija maskowanie).

### Maskowane pola

| Pole | Sposób maskowania |
|---|---|
| `firstName`, `lastName` | `"***"` |
| `phone`, `email` / `recipientAddress` | `"***"` |
| `nip` / `counterpartyNip` | `"***"` |
| `homeAddress`, `address` (firmowy) | `null` |
| URL-e do dokumentów/PDF | `null` lub `403` (patrz niżej) |

Wartości, które były `null`, pozostają `null` (nie zamieniamy na `"***"`).

### Token maskowania

```
"***"
```

Stały string. Frontend może go traktować jako sygnał „dane ukryte" (np. renderować skeleton/blur).

---

### Endpointy zwracające zamaskowane PII (200 OK, pola = `"***"`/`null`)

| Endpoint | Maskowane pola |
|---|---|
| `GET /api/v1/customers` | `firstName`, `lastName`, `contact.email`, `contact.phone`, `company.nip`, `homeAddress=null`, `company.address=null` |
| `GET /api/v1/customers/{id}` | jw. + `homeAddress=null`, `company.address=null` |
| `GET /api/v1/customers/{id}/detail` | jw. (zagnieżdżone w `customer`) |
| `POST /api/v1/customers` (odpowiedź) | jw. |
| `PATCH /api/v1/customers/{id}` (odpowiedź) | `firstName`, `lastName`, `contact.*`, `homeAddress=null` |
| `PATCH /api/v1/customers/{id}/company` (odpowiedź) | `nip`, `address.*` |
| `GET /api/visits` | `customer.firstName/lastName/phone/email` |
| `GET /api/visits/deleted` | jw. |
| `GET /api/visits/{id}` | `visit.customer.firstName/lastName/email/phone`, `vehicleHandoff.contactPerson.*`, `documents[].fileUrl=null` |
| `GET /api/v1/calendar/events` | `appointments[].customer.{firstName,lastName,phone,email}`, `visits[].customer.{firstName,lastName,phone}` |
| `GET /api/visits/{id}/communication` | `entries[].recipientAddress` |
| `GET /api/v1/customers/{id}/communication` | `entries[].recipientAddress` |
| `GET /api/v1/leads` | `leads[].assignedCustomer.{firstName,lastName,email,phone}` |
| `GET /api/v1/leads/{id}` | `assignedCustomer.{firstName,lastName,email,phone}` |
| `GET /api/v1/finance/documents` | `documents[].{customerFirstName,customerLastName,counterpartyNip}` |
| `GET /api/v1/finance/documents/{id}` | jw. |
| `POST/PATCH /api/v1/finance/documents/...` (odpowiedzi) | jw. |
| `GET /api/v1/visits/{id}/protocols` | `filledPdfUrl=null`, `signatureUrl=null` |
| `POST /api/v1/visits/{id}/protocols/generate` | jw. |
| `POST /api/v1/visits/{id}/protocols/{pid}/sign` | jw. |

### Przykład — `GET /api/visits/{id}` bez uprawnienia PII

```jsonc
{
  "visit": {
    "customer": {
      "id": "c4f...",          // ID zawsze widoczne
      "firstName": "***",
      "lastName": "***",
      "email": "***",
      "phone": "***",
      "companyName": "Auto-Detailing XYZ",  // nazwa firmy NIE jest maskowana
      "stats": { ... }
    }
  },
  "documents": [
    { "id": "d1...", "fileName": "protokol.pdf", "fileUrl": null }  // URL ukryty
  ]
}
```

---

### Endpointy całkowicie zablokowane bez uprawnienia PII (`403 Forbidden`)

Eksporty i pliki dokumentów **nie są dostępne** bez `CUSTOMERS_VIEW_PERSONAL_DATA`:

| Endpoint | Status bez uprawnienia |
|---|---|
| `GET /api/v1/customers/{id}/documents` | `403` |
| `POST /api/v1/customers/{id}/documents` (initiate upload) | `403` |
| `GET /api/visits/{id}/documents` | `403` |
| `GET /api/customers/{id}/documents` | `403` |
| `GET /api/documents/{id}/download-url` | `403` |

Body błędu (standardowy format `ForbiddenException`):

```jsonc
{
  "message": "Brak uprawnień do przeglądania dokumentów klientów"
}
```

Frontend powinien:
- Ukryć/zablokować przyciski „Pobierz", „Eksportuj", „Dokumenty" dla użytkowników bez tego uprawnienia.
- Obsłużyć `403` na tych endpointach jako brak dostępu (komunikat, nie błąd techniczny).

---

## 3. Jak frontend ustala, czy maskować?

Backend egzekwuje maskowanie po stronie serwera niezależnie od frontendu. Aby UI mogło
**proaktywnie** ukryć/zablurować pola i przyciski, sprawdź uprawnienia zalogowanego
użytkownika (lista uprawnień zwracana po zalogowaniu / w profilu):

- Ma `CUSTOMERS_VIEW_PERSONAL_DATA` **lub** `isOwner === true` → pełny widok.
- W przeciwnym razie → blur pól PII + ukrycie akcji eksportu/dokumentów.

> Pola, które przychodzą jako `"***"` lub `null`, są twardą gwarancją serwera — nawet jeśli
> frontend nie zablurował, prawdziwych danych nie ma w odpowiedzi.

---

## Podsumowanie zmian (changelog)

- **NOWE** pole `requires: string[]` w `GET /api/v1/roles/permissions`.
- Zapis roli domyka zbiór uprawnień (transitive closure) — odpowiedź może zawierać więcej
  uprawnień niż wysłano.
- Pola PII maskowane do `"***"`/`null` w odpowiedziach bez `CUSTOMERS_VIEW_PERSONAL_DATA`.
- Endpointy dokumentów/eksportów zwracają `403` bez tego uprawnienia.
- `companyName` (nazwa firmy) **nie** jest traktowana jako PII — pozostaje widoczna.
