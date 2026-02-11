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

## 📝 Przykładowy kod TypeScript (Frontend)

### 1. Tworzenie wizyty z protokołami

```typescript
interface CreateVisitResponse {
  visitId: string;
  protocols: Protocol[];
}

interface Protocol {
  id: string;
  templateId: string;
  templateName: string;
  stage: "CHECK_IN" | "CHECK_OUT";
  isMandatory: boolean;
  status: "PENDING" | "READY_FOR_SIGNATURE" | "SIGNED";
  filledPdfUrl: string | null;
}

async function createVisit(formData: ReservationToVisitRequest): Promise<CreateVisitResponse> {
  const response = await fetch('/api/checkin/reservation-to-visit', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify(formData)
  });

  if (!response.ok) {
    throw new Error('Failed to create visit');
  }

  return response.json();
}
```

### 2. Modal dokumentacji z anulowaniem

```typescript
function DocumentationModal({ visitId, protocols }: { visitId: string, protocols: Protocol[] }) {
  const handleCancel = async () => {
    try {
      // Usuń wizytę DRAFT
      await fetch(`/api/visits/${visitId}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${token}` }
      });

      // Wróć do formularza rezerwacji
      navigate('/reservations/create');

    } catch (error) {
      console.error('Failed to cancel visit:', error);
    }
  };

  const handleProceedToSigning = () => {
    // Przejdź do widoku podpisywania
    navigate(`/visits/${visitId}/sign-documents`);
  };

  return (
    <Modal>
      <h2>Dokumentacja do podpisania</h2>

      <ProtocolList protocols={protocols} />

      <div className="actions">
        <Button onClick={handleCancel} variant="secondary">
          Anuluj
        </Button>
        <Button onClick={handleProceedToSigning} variant="primary">
          Przejdź do podpisywania
        </Button>
      </div>
    </Modal>
  );
}
```

### 3. Potwierdzanie wizyty po podpisaniu

```typescript
async function confirmVisit(visitId: string): Promise<void> {
  try {
    const response = await fetch(`/api/visits/${visitId}/confirm`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (!response.ok) {
      const error = await response.json();

      if (error.error === 'ValidationException') {
        // Nie wszystkie mandatory docs podpisane
        alert(error.message);
        return;
      }

      throw new Error('Failed to confirm visit');
    }

    // Sukces - redirect do wizyty
    navigate(`/visits/${visitId}`);

  } catch (error) {
    console.error('Failed to confirm visit:', error);
  }
}
```

### 4. Pełny flow krok po kroku

```typescript
// Krok 1: Tworzenie wizyty
const { visitId, protocols } = await createVisit(formData);

// Krok 2: Pokaż modal z dokumentami
showDocumentationModal(visitId, protocols);

// Krok 3a: Jeśli użytkownik kliknie "Anuluj"
await cancelDraftVisit(visitId);  // DELETE /api/visits/{visitId}
navigate('/reservations/create');

// Krok 3b: Jeśli użytkownik przejdzie do podpisywania
for (const protocol of protocols.filter(p => p.isMandatory)) {
  await signProtocol(visitId, protocol.id, signatureData);
}

// Krok 4: Potwierdź wizytę
await confirmVisit(visitId);  // POST /api/visits/{visitId}/confirm

// Krok 5: Redirect do wizyty
navigate(`/visits/${visitId}`);
```

---

## ⚠️ Breaking Changes Checklist

### Frontend - Co trzeba zmienić:

- [ ] **Endpoint tworzenia wizyty**
  - Obsłużyć nowy format response z `protocols[]`
  - NIE wywoływać już `/api/v1/visits/{id}/protocols/generate`

- [ ] **Modal dokumentacji**
  - Wyświetlać protokoły z response (są już wygenerowane)
  - Przycisk "Anuluj" musi wywoływać `DELETE /api/visits/{visitId}`

- [ ] **Nowy status wizyty**
  - Dodać obsługę statusu `DRAFT` w UI
  - Badge/label dla statusu "Draft" / "Robocza"

- [ ] **Potwierdzanie wizyty**
  - Po podpisaniu wszystkich mandatory docs wywołać `POST /api/visits/{visitId}/confirm`
  - Obsłużyć błąd walidacji (nie wszystkie docs podpisane)

- [ ] **Lista wizyt**
  - Filtrowanie po statusie `DRAFT`
  - Możliwość anulowania wizyt DRAFT z listy

---

## 🧪 Scenariusze Testowe

### Test 1: Happy path
1. ✅ Utwórz wizytę → Otrzymaj visitId + protocols
2. ✅ Sprawdź status wizyty → `DRAFT`
3. ✅ Podpisz wszystkie mandatory protocols
4. ✅ Potwierdź wizytę → Status `IN_PROGRESS`
5. ✅ Sprawdź appointment → Status `CONVERTED`

### Test 2: Anulowanie przed podpisaniem
1. ✅ Utwórz wizytę → Otrzymaj visitId
2. ✅ Anuluj wizytę (DELETE)
3. ✅ Sprawdź czy wizyta nie istnieje (404)
4. ✅ Sprawdź appointment → Wciąż `CONFIRMED`

### Test 3: Próba potwierdzenia bez podpisów
1. ✅ Utwórz wizytę → Otrzymaj visitId
2. ❌ Potwierdź wizytę bez podpisywania
3. ✅ Otrzymaj błąd walidacji: "mandatory protocol(s) not signed"

### Test 4: Próba anulowania potwierdzonej wizyty
1. ✅ Utwórz wizytę + podpisz + potwierdź
2. ❌ Próba DELETE
3. ✅ Otrzymaj błąd: "Only DRAFT visits can be cancelled"

---

## 📞 Pytania? Problemy?

Jeśli masz pytania dotyczące implementacji lub napotkasz problemy:
1. Sprawdź przykłady kodu TypeScript powyżej
2. Przetestuj flow ręcznie w Postman/Insomnia
3. Skontaktuj się z backendem w razie wątpliwości

---

## 🎯 Podsumowanie dla Backend & Frontend

### Backend (zrobione ✅):
- [x] Dodany status `DRAFT` do `VisitStatus`
- [x] Wizyta tworzona w statusie `DRAFT`
- [x] Appointment pozostaje `CONFIRMED` do confirm
- [x] Protokoły generowane automatycznie przy tworzeniu
- [x] Endpoint `POST /api/visits/{visitId}/confirm`
- [x] Endpoint `DELETE /api/visits/{visitId}`
- [x] Walidacja mandatory protocols przy confirm
- [x] Usuwanie dokumentów i protokołów z S3

### Frontend (do zrobienia 🔨):
- [ ] Obsługa nowego response z protokołami
- [ ] Modal anulowania z DELETE request
- [ ] Endpoint confirm po podpisaniu dokumentów
- [ ] Obsługa statusu DRAFT w UI
- [ ] Testy e2e dla nowego flow

---

**Data wdrożenia:** 2026-02-11
**Wersja API:** 2.0
**Kompatybilność wsteczna:** ⚠️ Breaking changes w `/api/checkin/reservation-to-visit`
