# Photo Upload API - Dokumentacja dla Frontend

## 📋 Przegląd

API do uploadowania zdjęć podczas tworzenia wizyty (check-in). System używa **upload sessions** z presigned URLs dla bezpośredniego uploadu do S3.

### Kluczowe Cechy

✅ **Bezpieczeństwo**: Token-based validation
✅ **Performance**: Direct-to-S3 uploads (presigned URLs)
✅ **Reliability**: Automatyczny cleanup porzuconych sesji
✅ **Scalability**: Minimal backend load
✅ **Prostota**: Brak kategoryzacji - wizyta może mieć wiele zdjęć

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

```http
POST /api/photo-sessions
Authorization: Bearer {token}
Content-Type: application/json

{
  "appointmentId": "uuid"
}
```

**Response: 201**
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "token": "xYz123AbC...",
  "expiresAt": "2026-02-14T16:00:00Z"
}
```

---

### 2. Generuj Upload URL

```http
POST /api/photo-sessions/{sessionId}/upload-url
Authorization: Bearer {token}
Content-Type: application/json

{
  "fileName": "photo-1.jpg",
  "contentType": "image/jpeg",
  "fileSize": 2048576,
  "sessionToken": "xYz123AbC..."
}
```

**Dozwolone typy:**
- `image/jpeg`, `image/jpg`, `image/png`, `image/webp`

**Limity:**
- Max rozmiar: **10MB**
- Max zdjęć: **20/sesję**

**Response: 200**
```json
{
  "photoId": "660e8400-e29b-41d4-a716-446655440000",
  "uploadUrl": "https://s3.amazonaws.com/...",
  "expiresAt": "2026-02-14T12:15:00Z"
}
```

**Upload do S3:**
```typescript
await fetch(uploadUrl, {
  method: 'PUT',
  body: file,
  headers: { 'Content-Type': file.type }
});
```

---

### 3. Lista Zdjęć

```http
GET /api/photo-sessions/{sessionId}/photos
Authorization: Bearer {token}
```

**Response: 200**
```json
{
  "photos": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440000",
      "fileName": "photo-1.jpg",
      "fileSize": 2048576,
      "uploadedAt": "2026-02-14T12:00:00Z",
      "thumbnailUrl": "https://s3.amazonaws.com/..."
    }
  ]
}
```

---

### 4. Usuń Zdjęcie

```http
DELETE /api/photo-sessions/{sessionId}/photos/{photoId}
Authorization: Bearer {token}
```

**Response: 204**

---

### 5. Submit Check-in

```http
POST /api/checkin/reservation-to-visit
Authorization: Bearer {token}
Content-Type: application/json

{
  "reservationId": "uuid",
  "customer": { ... },
  "vehicle": { ... },
  "photoIds": [
    "660e8400-e29b-41d4-a716-446655440000",
    "770e8400-e29b-41d4-a716-446655440001"
  ]
}
```

---

## 💻 React Hook Example

```typescript
export function usePhotoUpload(appointmentId: string) {
  const [session, setSession] = useState<UploadSession | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);

  const initSession = async () => {
    const res = await fetch('/api/photo-sessions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`
      },
      body: JSON.stringify({ appointmentId })
    });
    const data = await res.json();
    setSession(data);
  };

  const uploadPhoto = async (file: File) => {
    // 1. Get presigned URL
    const urlRes = await fetch(
      `/api/photo-sessions/${session.sessionId}/upload-url`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getToken()}`
        },
        body: JSON.stringify({
          fileName: file.name,
          contentType: file.type,
          fileSize: file.size,
          sessionToken: session.token
        })
      }
    );
    const { uploadUrl, photoId } = await urlRes.json();

    // 2. Upload to S3
    await fetch(uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type }
    });

    return photoId;
  };

  const getPhotoIds = () => photos.map(p => p.id);

  return { initSession, uploadPhoto, getPhotoIds };
}
```

---

## ⚠️ Error Handling

| Status | Błąd | Action |
|--------|------|--------|
| 400 | Invalid content type | Sprawdź typ pliku |
| 400 | File too large | Limit 10MB |
| 400 | Too many photos | Limit 20/sesję |
| 403 | Invalid token | Zresetuj sesję |
| 404 | Session not found | Utwórz nową |
| 400 | Session expired | Utwórz nową (>2h) |

---

## 🧹 Auto Cleanup

- Runs hourly (5 mins past)
- Deletes expired sessions (>2h)
- Removes orphaned S3 files
- No manual cleanup needed

---

## 🔒 Security

- Token validation per upload
- Studio isolation
- File type & size validation
- 15-min presigned URL expiry
- 2h session TTL

---

## 🚀 Quick Start

1. Init session on form open
2. Get upload URL for each file
3. Upload directly to S3
4. Collect photoIds
5. Submit with photoIds array

**Gotowe! 🎉**
