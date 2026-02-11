# API Changes: Visit Creation Workflow (Draft State Pattern)

## 📋 Overview

Zaimplementowano produkcyjne flow tworzenia wizyty oparte na **Draft State Pattern**. Wizyta jest teraz tworzona w statusie `DRAFT` i wymaga potwierdzenia (po podpisaniu dokumentów) zanim stanie się aktywna.

### Główne zmiany:
1. ✅ Nowy status wizyty: `DRAFT`
2. ✅ Tworzenie wizyty + generowanie dokumentów w jednym zapytaniu
3. ✅ Możliwość anulowania wizyty przed potwierdzeniem
4. ✅ Potwierdzenie wizyty dopiero po podpisaniu obowiązkowych dokumentów
5. ✅ Appointment pozostaje `CONFIRMED` do momentu potwierdzenia wizyty

---

## 🔄 Nowy Flow Tworzenia Wizyty

### Poprzedni flow (problematyczny):
```
POST /api/checkin/reservation-to-visit
  ↓
  Wizyta zapisana (IN_PROGRESS) ✓
  Appointment → CONVERTED ✓
  ↓
UI pokazuje modal z dokumentami
  ↓
POST /api/v1/visits/{id}/protocols/generate
  ↓
[Problem: Anulowanie w modalu nie usuwa wizyty!]
```

### Nowy flow (produkcyjny):
```
1. POST /api/checkin/reservation-to-visit
   ↓
   Wizyta utworzona (status: DRAFT)
   Protokoły wygenerowane automatycznie
   Appointment pozostaje CONFIRMED

2. UI: Modal z listą dokumentów do podpisu
   - Użytkownik może anulować → DELETE /api/visits/{visitId}
   - Lub przejść do podpisywania

3. POST /api/v1/visits/{visitId}/protocols/{protocolId}/sign
   (Dla każdego dokumentu)

4. POST /api/visits/{visitId}/confirm
   ↓
   Walidacja: wszystkie mandatory docs podpisane?
   Wizyta: DRAFT → IN_PROGRESS
   Appointment: CONFIRMED → CONVERTED
   ↓
   Wizyta aktywna, nie można już anulować
```

---

## 📡 Zmiany w API Endpoints

### 1. **POST /api/checkin/reservation-to-visit** ⚠️ BREAKING CHANGE

**Co się zmieniło:**
- Zwraca dodatkowo listę wygenerowanych protokołów
- Wizyta jest tworzona w statusie `DRAFT` (zamiast `IN_PROGRESS`)
- Appointment NIE jest zmieniany na `CONVERTED` (pozostaje `CONFIRMED`)

**Nowy response:**
```typescript
{
  "visitId": "227d7c93-ed07-43ad-9e53-b63176c3b4f9",
  "protocols": [
    {
      "id": "8f3e2a1b-4c5d-6e7f-8a9b-0c1d2e3f4a5b",
      "templateId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
      "templateName": "Protokół przyjęcia pojazdu",
      "stage": "CHECK_IN",
      "isMandatory": true,
      "status": "READY_FOR_SIGNATURE",  // lub "PENDING"
      "filledPdfUrl": "https://s3.amazonaws.com/..."  // Presigned URL (10 min)
    }
  ]
}
```

**Request pozostaje bez zmian** - wszystkie pola jak dotychczas.

---

### 2. **POST /api/visits/{visitId}/confirm** 🆕 NOWY ENDPOINT

**Opis:**
Potwierdza wizytę typu DRAFT i przenosi ją do statusu IN_PROGRESS.

**Walidacje:**
- ✅ Wizyta musi być w statusie `DRAFT`
- ✅ Wszystkie protokoły oznaczone jako `isMandatory: true` muszą być `SIGNED`

**Request:**
```http
POST /api/visits/227d7c93-ed07-43ad-9e53-b63176c3b4f9/confirm
Authorization: Bearer {token}
```

**Response (200 OK):**
```json
{
  "visitId": "227d7c93-ed07-43ad-9e53-b63176c3b4f9",
  "message": "Visit confirmed successfully"
}
```

**Error responses:**
```json
// 400 Bad Request - Nie wszystkie mandatory docs podpisane
{
  "error": "ValidationException",
  "message": "Cannot confirm visit: 2 mandatory protocol(s) not signed yet. All mandatory documents must be signed before confirming the visit."
}

// 400 Bad Request - Wizyta już potwierdzona
{
  "error": "ValidationException",
  "message": "Only DRAFT visits can be confirmed. Current status: IN_PROGRESS"
}

// 403 Forbidden - Brak uprawnień
{
  "error": "ForbiddenException",
  "message": "Only OWNER and MANAGER can confirm visits"
}
```

**Co się dzieje po potwierdzeniu:**
1. Status wizyty: `DRAFT` → `IN_PROGRESS`
2. Status appointment: `CONFIRMED` → `CONVERTED`
3. Wizyta nie może być już anulowana (tylko rejected)

---

### 3. **DELETE /api/visits/{visitId}** 🆕 NOWY ENDPOINT

**Opis:**
Anuluje wizytę typu DRAFT i usuwa ją z systemu wraz ze wszystkimi powiązanymi danymi.

**Walidacje:**
- ✅ Wizyta musi być w statusie `DRAFT`
- ✅ Tylko wizyty DRAFT można usunąć (potwierdzone używają rejection flow)

**Request:**
```http
DELETE /api/visits/227d7c93-ed07-43ad-9e53-b63176c3b4f9
Authorization: Bearer {token}
```

**Response (204 No Content):**
```
(Empty response body)
```

**Error responses:**
```json
// 400 Bad Request - Wizyta nie jest DRAFT
{
  "error": "ValidationException",
  "message": "Only DRAFT visits can be cancelled. Current status: IN_PROGRESS. To cancel a confirmed visit, use the rejection flow instead."
}

// 403 Forbidden - Brak uprawnień
{
  "error": "ForbiddenException",
  "message": "Only OWNER and MANAGER can cancel visits"
}

// 404 Not Found
{
  "error": "EntityNotFoundException",
  "message": "Visit not found"
}
```

**Co jest usuwane:**
- ✅ Wizyta z bazy danych
- ✅ Wszystkie protokoły (database + S3)
- ✅ Damage map (S3)
- ✅ Wszystkie dokumenty powiązane z wizytą
- ⚠️ **Appointment pozostaje w statusie `CONFIRMED`** (można ponownie stworzyć wizytę)

---

### 4. **POST /api/v1/visits/{visitId}/protocols/generate** ℹ️ DEPRECATED

⚠️ **Ten endpoint NIE jest już używany w nowym flow!**

Protokoły są teraz generowane automatycznie podczas tworzenia wizyty (endpoint `POST /api/checkin/reservation-to-visit`).

Endpoint nadal działa (dla kompatybilności wstecznej), ale:
- Zwraca istniejące protokoły jeśli już zostały wygenerowane
- Nie generuje duplikatów

---

## 🎨 Zmiany w UI Flow

### Poprzednie flow:
```
1. Formularz rezerwacji
2. Klik "Utwórz wizytę"
3. POST /api/checkin/reservation-to-visit → Wizyta zapisana ✓
4. Modal "Dokumentacja"
5. POST /api/v1/visits/{id}/protocols/generate
6. Lista dokumentów
   - [Anuluj] → Wraca do formularza, ale wizyta już istnieje! ❌
   - [Podpisz] → Przejście do podpisywania
```

### Nowe flow (zaimplementuj to):
```
1. Formularz rezerwacji
2. Klik "Utwórz wizytę"
3. POST /api/checkin/reservation-to-visit
   ↓
   Otrzymujesz: { visitId, protocols: [...] }

4. Modal "Dokumentacja"
   - Pokaż listę protokołów (już wygenerowanych!)
   - Status wizyty: DRAFT

   [Anuluj] → DELETE /api/visits/{visitId}
             → Redirect do formularza rezerwacji
             → Wizyta i dokumenty usunięte ✓

   [Przejdź do podpisywania] ↓

5. Podpisywanie dokumentów
   - Dla każdego mandatory doc:
     POST /api/v1/visits/{visitId}/protocols/{protocolId}/sign

6. Gdy wszystkie mandatory docs podpisane:
   POST /api/visits/{visitId}/confirm
   ↓
   Wizyta: DRAFT → IN_PROGRESS ✓
   Redirect do widoku wizyty
```

---

## 🔢 Nowy Status: DRAFT

### Enum VisitStatus:
```typescript
enum VisitStatus {
  DRAFT = "draft",              // 🆕 Nowy status
  IN_PROGRESS = "in_progress",
  READY_FOR_PICKUP = "ready_for_pickup",
  COMPLETED = "completed",
  REJECTED = "rejected",
  ARCHIVED = "archived"
}
```

### Znaczenie statusów:
- `DRAFT` - Wizyta utworzona, czeka na podpisanie dokumentów (można anulować)
- `IN_PROGRESS` - Dokumenty podpisane, wizyta aktywna (nie można anulować, tylko reject)
- Pozostałe statusy bez zmian

---
