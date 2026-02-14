# Photo Upload API - Dokumentacja dla Frontend

## 📋 Przegląd

API do uploadowania zdjęć podczas tworzenia wizyty (check-in). System używa **upload sessions** z presigned URLs dla bezpośredniego uploadu do S3.

### Kluczowe Cechy

✅ **Bezpieczeństwo**: Token-based validation
✅ **Performance**: Direct-to-S3 uploads (presigned URLs)
✅ **Reliability**: Automatyczny cleanup porzuconych sesji
✅ **Scalability**: Minimal backend load

---

## 🔄 Flow Użycia

```
1. Otwarcie formularza
   └─→ POST /api/photo-sessions
       └─→ Otrzymujesz: sessionId + token

2. Upload zdjęć (wielokrotnie)
   └─→ POST /api/photo-sessions/{sessionId}/upload-url
       └─→ Otrzymujesz: uploadUrl + photoId
       └─→ PUT do S3 (direct upload)
       └─→ Zapisujesz photoId

3. Podgląd zdjęć (opcjonalnie)
   └─→ GET /api/photo-sessions/{sessionId}/photos
       └─→ Otrzymujesz: lista z thumbnailUrl

4. Usunięcie zdjęcia (opcjonalnie)
   └─→ DELETE /api/photo-sessions/{sessionId}/photos/{photoId}

5. Submit formularza
   └─→ POST /api/checkin/reservation-to-visit
       └─→ W payload: photoIds: ["uuid1", "uuid2", ...]
       └─→ Backend automatycznie linkuje zdjęcia do wizyty

6. Anulowanie formularza
   └─→ Nic nie rób - cleanup job usunie sesję po 2h
```

---

## 🔧 API Endpoints

### 1. Utwórz Upload Session

**Wywołaj przy otwarciu formularza check-in**

```http
POST /api/photo-sessions
Authorization: Bearer {token}
Content-Type: application/json

{
  "appointmentId": "uuid"
}
```

**Response: 201 Created**
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "token": "xYz123AbC...",
  "expiresAt": "2026-02-14T16:00:00Z"
}
```

**Zapisz:**
- `sessionId` - potrzebny do wszystkich kolejnych requestów
- `token` - potrzebny do walidacji uploadów
- `expiresAt` - możesz pokazać countdown w UI

---

### 2. Generuj Upload URL

**Wywołaj dla każdego zdjęcia, które użytkownik wybierze**

```http
POST /api/photo-sessions/{sessionId}/upload-url
Authorization: Bearer {token}
Content-Type: application/json

{
  "fileName": "front-view.jpg",
  "photoType": "FRONT",
  "contentType": "image/jpeg",
  "fileSize": 2048576,
  "sessionToken": "xYz123AbC..."
}
```

**PhotoType - dostępne wartości:**
```typescript
type PhotoType =
  | "FRONT"
  | "REAR"
  | "LEFT_SIDE"
  | "RIGHT_SIDE"
  | "DAMAGE_FRONT"
  | "DAMAGE_REAR"
  | "DAMAGE_LEFT"
  | "DAMAGE_RIGHT"
  | "DAMAGE_OTHER"
```

**Content Types - dozwolone:**
- `image/jpeg`
- `image/jpg`
- `image/png`
- `image/webp`

**Limity:**
- Max rozmiar pliku: **10MB**
- Max zdjęć na sesję: **20**

**Response: 200 OK**
```json
{
  "photoId": "660e8400-e29b-41d4-a716-446655440000",
  "uploadUrl": "https://s3.amazonaws.com/bucket/...",
  "expiresAt": "2026-02-14T12:15:00Z"
}
```

**Następny krok - Upload do S3:**
```typescript
// Upload bezpośrednio do S3 używając presigned URL
const response = await fetch(uploadUrl, {
  method: 'PUT',
  body: file,
  headers: {
    'Content-Type': file.type
  }
});

if (response.ok) {
  // Upload sukces - zapisz photoId
  photoIds.push(photoId);
}
```

---

### 3. Lista Zdjęć w Sesji

**Opcjonalnie - do wyświetlenia podglądu**

```http
GET /api/photo-sessions/{sessionId}/photos
Authorization: Bearer {token}
```

**Response: 200 OK**
```json
{
  "photos": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440000",
      "photoType": "FRONT",
      "fileName": "front-view.jpg",
      "fileSize": 2048576,
      "uploadedAt": "2026-02-14T12:00:00Z",
      "thumbnailUrl": "https://s3.amazonaws.com/..."
    }
  ]
}
```

**Uwaga:** `thumbnailUrl` jest presigned URL ważnym 15 minut

---

### 4. Usuń Zdjęcie z Sesji

**Gdy użytkownik chce usunąć uploadowane zdjęcie**

```http
DELETE /api/photo-sessions/{sessionId}/photos/{photoId}
Authorization: Bearer {token}
```

**Response: 204 No Content**

---

### 5. Submit Formularza Check-in

**Istniejący endpoint - dodaj pole `photoIds`**

```http
POST /api/checkin/reservation-to-visit
Authorization: Bearer {token}
Content-Type: application/json

{
  "reservationId": "uuid",
  "customer": { ... },
  "vehicle": { ... },
  "technicalState": { ... },
  "services": [ ... ],
  "damagePoints": [ ... ],
  "vehicleHandoff": "...",
  "photoIds": [
    "660e8400-e29b-41d4-a716-446655440000",
    "770e8400-e29b-41d4-a716-446655440001"
  ]
}
```

**Backend automatycznie:**
1. Waliduje że photoIds należą do upload session
2. Przenosi pliki z temp do final location
3. Linkuje zdjęcia do utworzonej wizyty
4. Oznacza sesję jako "claimed"

---

## 💻 TypeScript Implementation Example

### Kompletny Hook dla React

```typescript
import { useState, useCallback } from 'react';

interface UploadSession {
  sessionId: string;
  token: string;
  expiresAt: string;
}

interface Photo {
  id: string;
  photoType: string;
  file: File;
  uploadProgress: number;
  error?: string;
}

export function usePhotoUpload(appointmentId: string) {
  const [session, setSession] = useState<UploadSession | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [loading, setLoading] = useState(false);

  // 1. Inicjalizuj sesję
  const initSession = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/photo-sessions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getToken()}`
        },
        body: JSON.stringify({ appointmentId })
      });

      if (!response.ok) throw new Error('Failed to create session');

      const data = await response.json();
      setSession(data);
    } catch (error) {
      console.error('Session init error:', error);
      throw error;
    } finally {
      setLoading(false);
    }
  }, [appointmentId]);

  // 2. Upload zdjęcia
  const uploadPhoto = useCallback(async (
    file: File,
    photoType: string
  ) => {
    if (!session) throw new Error('Session not initialized');

    const photoId = crypto.randomUUID();

    // Dodaj do state
    setPhotos(prev => [...prev, {
      id: photoId,
      photoType,
      file,
      uploadProgress: 0
    }]);

    try {
      // Krok 1: Pobierz presigned URL
      const urlResponse = await fetch(
        `/api/photo-sessions/${session.sessionId}/upload-url`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${getToken()}`
          },
          body: JSON.stringify({
            fileName: file.name,
            photoType,
            contentType: file.type,
            fileSize: file.size,
            sessionToken: session.token
          })
        }
      );

      if (!urlResponse.ok) {
        const error = await urlResponse.json();
        throw new Error(error.message || 'Failed to get upload URL');
      }

      const { uploadUrl, photoId: serverPhotoId } = await urlResponse.json();

      // Krok 2: Upload do S3 z progress
      await new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();

        xhr.upload.addEventListener('progress', (e) => {
          if (e.lengthComputable) {
            const progress = (e.loaded / e.total) * 100;
            setPhotos(prev => prev.map(p =>
              p.id === photoId ? { ...p, uploadProgress: progress } : p
            ));
          }
        });

        xhr.addEventListener('load', () => {
          if (xhr.status === 200) {
            setPhotos(prev => prev.map(p =>
              p.id === photoId
                ? { ...p, id: serverPhotoId, uploadProgress: 100 }
                : p
            ));
            resolve(null);
          } else {
            reject(new Error(`Upload failed: ${xhr.status}`));
          }
        });

        xhr.addEventListener('error', () => reject(new Error('Upload error')));

        xhr.open('PUT', uploadUrl);
        xhr.setRequestHeader('Content-Type', file.type);
        xhr.send(file);
      });

      return serverPhotoId;

    } catch (error) {
      setPhotos(prev => prev.map(p =>
        p.id === photoId ? { ...p, error: error.message } : p
      ));
      throw error;
    }
  }, [session]);

  // 3. Usuń zdjęcie
  const deletePhoto = useCallback(async (photoId: string) => {
    if (!session) return;

    try {
      await fetch(
        `/api/photo-sessions/${session.sessionId}/photos/${photoId}`,
        {
          method: 'DELETE',
          headers: {
            'Authorization': `Bearer ${getToken()}`
          }
        }
      );

      setPhotos(prev => prev.filter(p => p.id !== photoId));
    } catch (error) {
      console.error('Delete photo error:', error);
      throw error;
    }
  }, [session]);

  // 4. Pobierz photoIds dla submit
  const getPhotoIds = useCallback(() => {
    return photos
      .filter(p => p.uploadProgress === 100 && !p.error)
      .map(p => p.id);
  }, [photos]);

  return {
    session,
    photos,
    loading,
    initSession,
    uploadPhoto,
    deletePhoto,
    getPhotoIds
  };
}
```

### Użycie w komponencie

```typescript
function CheckInForm({ appointmentId }) {
  const {
    session,
    photos,
    initSession,
    uploadPhoto,
    deletePhoto,
    getPhotoIds
  } = usePhotoUpload(appointmentId);

  // Inicjalizuj sesję przy montowaniu
  useEffect(() => {
    initSession();
  }, [initSession]);

  const handleFileSelect = async (files: FileList, photoType: string) => {
    for (const file of Array.from(files)) {
      try {
        await uploadPhoto(file, photoType);
      } catch (error) {
        toast.error(`Failed to upload ${file.name}`);
      }
    }
  };

  const handleSubmit = async (formData) => {
    const photoIds = getPhotoIds();

    const response = await fetch('/api/checkin/reservation-to-visit', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`
      },
      body: JSON.stringify({
        ...formData,
        photoIds  // ← Dodaj photoIds
      })
    });

    if (response.ok) {
      navigate('/visits');
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      {/* Twój formularz... */}

      <PhotoUploader
        photos={photos}
        onUpload={(files, type) => handleFileSelect(files, type)}
        onDelete={deletePhoto}
      />

      <button type="submit">Utwórz wizytę</button>
    </form>
  );
}
```

---

## ⚠️ Obsługa Błędów

### Możliwe błędy

| Status | Błąd | Rozwiązanie |
|--------|------|-------------|
| 400 | `Invalid content type` | Sprawdź czy typ pliku jest na liście dozwolonych |
| 400 | `File size exceeds maximum` | Plik > 10MB - ogranicz rozmiar lub kompresuj |
| 400 | `Maximum X photos per session exceeded` | Limit 20 zdjęć - nie pozwól dodać więcej |
| 403 | `Invalid session token` | Token nieprawidłowy - zresetuj sesję |
| 404 | `Upload session not found` | Sesja nie istnieje lub wygasła - utwórz nową |
| 400 | `Upload session expired` | Sesja wygasła (>2h) - utwórz nową |
| 400 | `Upload session already claimed` | Sesja już użyta - utwórz nową |

### Best Practices

```typescript
// 1. Waliduj pliki przed uploadem
function validateFile(file: File): string | null {
  const allowedTypes = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp'];
  const maxSize = 10 * 1024 * 1024; // 10MB

  if (!allowedTypes.includes(file.type)) {
    return 'Nieprawidłowy typ pliku. Dozwolone: JPG, PNG, WEBP';
  }

  if (file.size > maxSize) {
    return 'Plik zbyt duży. Maksymalnie 10MB';
  }

  return null;
}

// 2. Retry dla network errors (nie dla validation errors)
async function uploadWithRetry(
  uploadFn: () => Promise<void>,
  maxRetries = 3
) {
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await uploadFn();
    } catch (error) {
      if (error.status >= 400 && error.status < 500) {
        // Client error - nie retry
        throw error;
      }

      if (i === maxRetries - 1) throw error;

      // Exponential backoff
      await new Promise(r => setTimeout(r, Math.pow(2, i) * 1000));
    }
  }
}

// 3. Pokazuj progress
<ProgressBar
  value={photo.uploadProgress}
  max={100}
  label={`${photo.uploadProgress.toFixed(0)}%`}
/>

// 4. Countdown do wygaśnięcia sesji
function SessionExpiry({ expiresAt }: { expiresAt: string }) {
  const [timeLeft, setTimeLeft] = useState('');

  useEffect(() => {
    const interval = setInterval(() => {
      const now = new Date();
      const expiry = new Date(expiresAt);
      const diff = expiry.getTime() - now.getTime();

      if (diff <= 0) {
        setTimeLeft('Sesja wygasła');
        clearInterval(interval);
      } else {
        const minutes = Math.floor(diff / 60000);
        setTimeLeft(`Sesja wygasa za ${minutes} min`);
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [expiresAt]);

  return <div className="text-sm text-gray-500">{timeLeft}</div>;
}
```

---

## 🧹 Automatyczny Cleanup

**Backend automatycznie czyści:**
- Wygasłe sesje (>2h od utworzenia)
- Nieużyte temporary photos z S3
- Orphaned DB records

**Scheduled job:** Co godzinę o 5 minut po pełnej (1:05, 2:05, itd.)

**Nie musisz:**
- Ręcznie usuwać sesji przy anulowaniu formularza
- Martwić się orphaned files w S3
- Implementować własnego cleanup

---

## 🔒 Bezpieczeństwo

1. **Token validation**: Każdy upload wymaga `sessionToken`
2. **Studio isolation**: Użytkownik może uploadować tylko do własnego studia
3. **File validation**: Type, size, content-type
4. **Presigned URLs**: 15-minutowe wygaśnięcie
5. **Session expiry**: 2 godziny TTL

---

## 📊 Monitoring

### Logi backend

```
INFO  - Creating photo upload session for appointment xxx
DEBUG - Generated upload URL for photo yyy in session xxx
INFO  - Claimed 3 photos for visit zzz
INFO  - Cleanup completed: deleted 5 sessions, 12 photos, 12 S3 files
```

### Metryki do trackowania (opcjonalnie)

- Liczba utworzonych sesji
- % sesji claimed vs expired
- Średni czas od utworzenia sesji do claim
- Średnia liczba zdjęć na wizytę
- Upload success rate

---

## 🚀 Quick Start Checklist

- [ ] Utwórz sesję przy otwarciu formularza
- [ ] Zapisz `sessionId` i `token` w state
- [ ] Dla każdego pliku:
  - [ ] Waliduj rozmiar i typ
  - [ ] Pobierz presigned URL
  - [ ] Upload do S3 z progress bar
  - [ ] Zapisz `photoId`
- [ ] Submit formularza z `photoIds` array
- [ ] Obsłuż błędy gracefully
- [ ] Pokaż podgląd uploadowanych zdjęć

---

## 📞 Pytania?

Jeśli masz pytania lub napotkasz problemy, sprawdź:
1. Logi backend (zawierają szczegółowe info o błędach)
2. Network tab (czy requesty mają prawidłowe headery)
3. S3 bucket (czy pliki są uploadowane)

---

**Powodzenia z integracją! 🎉**
