# PROMPT: Aplikacja tabletowa do podpisu elektronicznego (DetailBoost Tablet)

> Poniższy prompt jest kompletną specyfikacją do implementacji aplikacji webowej
> na tablet. Przekaż go w całości zespołowi / agentowi implementującemu frontend.

---

Jesteś Senior Frontend Engineerem. Zaimplementuj od zera aplikację webową **DetailBoost Tablet** — kiosk do składania podpisu elektronicznego przez klientów salonu detailingowego. Aplikacja będzie uruchamiana na tabletach (Android/iPad, przeglądarka w trybie pełnoekranowym/kiosk) zamontowanych na recepcji, ekranem w stronę klienta.

## 1. Kontekst biznesowy

DetailBoost to CRM dla salonów detailingu samochodowego (multi-tenant; salon = "studio"). Przepływ biznesowy:

1. Pracownik przyjmuje pojazd w CRM na komputerze (proces "check-in"). Backend generuje wypełniony protokół przyjęcia pojazdu (PDF), a także inne dokumenty (np. zgody marketingowe RODO).
2. Pracownik klika w CRM **„Poproś o podpis"**. Backend tworzy *sesję podpisu* (signature request) i wysyła powiadomienie WebSocket do tabletów salonu.
3. Tablet — który do tej pory był w **trybie czuwania** (ciemny ekran z zegarem) — **wybudza się** i wyświetla dokument PDF klientowi.
4. Klient czyta dokument, zaznacza checkbox z oświadczeniem („Oświadczam, że zapoznałem się z treścią…"), składa podpis palcem w polu podpisu i klika **„Gotowe"**.
5. Tablet wysyła na serwer pakiet: obraz podpisu + hash SHA-256 wyświetlonego dokumentu + challenge + znaczniki czasu. Serwer weryfikuje integralność (zasada WYSIWYS — What You See Is What You Sign, eIDAS), scala podpis z PDF, nakłada pieczęć elektroniczną i kończy sesję.
6. Tablet pokazuje ekran podziękowania i wraca do trybu czuwania.

**Wymogi prawne, które aplikacja tabletowa MUSI współrealizować (eIDAS / ochrona przed atakiem przez powtórzenie):**

- **WYSIWYS**: tablet liczy SHA-256 nad *dokładnie tymi bajtami PDF*, które pobrał i wyrenderował, i odsyła ten hash przy submit. Serwer odrzuci podpis, jeśli hash nie zgadza się z dokumentem oczekującym na podpis.
- **Podpis bez tła**: podpis jest rysowany na **w pełni przezroczystym canvasie** i eksportowany jako **PNG z kanałem alfa**. Nigdy nie renderuj białego tła pod kreskami podpisu (serwer i tak wymusi przezroczystość, ale klient ma jej nie tworzyć).
- **Natychmiastowe niszczenie danych**: po wysłaniu (sukces LUB błąd) aplikacja MUSI usunąć ze swojej pamięci: bitmapę podpisu (wyczyścić canvas, zwolnić referencje), bajty PDF (`ArrayBuffer`), object URL-e (`URL.revokeObjectURL`). Niczego nie zapisuj w `localStorage`/`sessionStorage`/IndexedDB/cache poza tokenem urządzenia i metadanymi parowania. Dokument serwowany jest z `Cache-Control: no-store` — uszanuj to.
- **Anty-replay**: każda sesja podpisu ma jednorazowy `challenge`, który trzeba odesłać przy submit. Nie przechowuj go dłużej niż trwa sesja.

## 2. Stack i hosting

- **React 18 + TypeScript + Vite**, SPA.
- Adres produkcyjny: `https://www.tablet.detailboost.pl` (origin jest na białej liście CORS i WebSocket backendu — `https://*.detailboost.pl`).
- Backend API: `https://detailboost.pl` (te same ścieżki na dev/stage — bazowy URL w zmiennej środowiskowej `VITE_API_BASE_URL`).
- WebSocket: **STOMP przez SockJS**, endpoint `https://detailboost.pl/ws-registry`. Użyj `@stomp/stompjs` + `sockjs-client`.
- Render PDF: **pdf.js** (`pdfjs-dist`) — renderuj z `ArrayBuffer`, który wcześniej zahashowałeś (te same bajty!).
- Podpis: własny komponent canvas lub `signature_pad` — skonfigurowany na przezroczyste tło.
- Hash: WebCrypto — `crypto.subtle.digest("SHA-256", arrayBuffer)` → hex lowercase.
- Język UI: **polski**. Motyw: **ciemny wszędzie** (OLED-friendly, minimalizuje pobór mocy i nagrzewanie).

## 3. Parowanie tabletu (onboarding)

Proces widziany przez użytkownika:

1. Pracownik na komputerze w CRM wchodzi w zakładkę **„Tablety"** i klika **„Dodaj tablet"**. CRM wyświetla mu **6-cyfrowy kod** (jak BLIK), ważny 5 minut, jednorazowy.
2. Na tablecie ktoś otwiera `www.tablet.detailboost.pl`. Jeśli urządzenie nie jest sparowane, aplikacja pokazuje ekran parowania: duże pole na 6 cyfr (klawiatura numeryczna na ekranie, auto-advance między polami) + pole „Nazwa urządzenia" (np. „Recepcja 1") + przycisk „Połącz".
3. Aplikacja woła `POST /api/tablet/pair`. Sukces → zapisz w `localStorage`: `token`, `tabletId`, `studioId`, `deviceName`. Błąd 403 → komunikat „Nieprawidłowy lub wygasły kod — wygeneruj nowy w CRM".
4. **Auto-reconnect (dzień 2 i kolejne):** przy każdym starcie aplikacja sprawdza `localStorage`. Jeśli jest token → `GET /api/tablet/context` z nagłówkiem `X-Tablet-Token`. Odpowiedź 200 = token ważny (backend przedłuża TTL przy każdym użyciu — token żyje 30 dni od ostatniego użycia) → od razu łączy WebSocket i przechodzi do trybu czuwania, bez żadnej interakcji. Odpowiedź 403 = token odwołany/wygasły → wyczyść storage i pokaż ekran parowania.

## 4. Maszyna stanów aplikacji

```
UNPAIRED ──pair──► CONNECTING ──ok──► STANDBY ◄────────────────┐
                        │403                │ WS: SIGNATURE_REQUESTED│
                        ▼                   │ (lub polling pending)  │
                    UNPAIRED                ▼                        │
                                   DOCUMENT_REVIEW ──„Odmawiam"──► DECLINED_INFO ─┐
                                        │ checkbox zaznaczony                     │
                                        ▼                                         │
                                   SIGNATURE_PAD ──„Gotowe"──► SUBMITTING         │
                                        │„Wyczyść" (reset canvas)   │ ok          │
                                        │                           ▼             │
                                        │                      THANK_YOU ──3-5s──┤
                                        │ błąd (hash/replay/expiry)               │
                                        └──────────► ERROR_SCREEN ──„OK"──────────┘
```

### STANDBY — tryb czuwania (kluczowe wymaganie)

- Pełnoekranowy **ciemny ekran (#000)** z dużym zegarem (HH:MM), datą i dyskretnym logo + status połączenia (mała kropka: zielona = WS połączony, pomarańczowa = reconnect/polling).
- **Oszczędzanie energii i temperatury:** zegar aktualizowany raz na minutę (bez sekund, bez animacji, bez re-renderów częstszych niż to konieczne); brak jasnych elementów; żadnych pętli renderujących w tle.
- **Screen Wake Lock API** (`navigator.wakeLock.request('screen')`) — ekran nie może zgasnąć, bo tablet musi być gotowy na wybudzenie. Re-akwizycja wake locka po `visibilitychange`.
- Aplikacja przez cały czas utrzymuje połączenie STOMP i **wybudza się natychmiast** po odebraniu zdarzenia `SIGNATURE_REQUESTED`.
- **WS jest tylko sygnałem — źródłem prawdy jest REST.** Po każdym zdarzeniu (i po każdym reconnect) wołaj `GET /api/tablet/signature-requests/pending`. Dodatkowo, gdy WS jest rozłączony, przełącz się na polling `pending` co 10 s. Dzięki temu żądanie utworzone podczas chwilowej utraty łączności nie przepadnie.
- Reconnect STOMP: exponential backoff 1s → 2s → 5s → 10s (max), w nieskończoność.

### DOCUMENT_REVIEW

- Nagłówek: nazwa dokumentu (`documentName`) i dla kogo (`signerName`), licznik ważności sesji (odliczanie do `expiresAt`; po upływie → wróć do STANDBY z komunikatem).
- Pobierz dokument: `GET /api/tablet/signature-requests/{id}/document` jako `ArrayBuffer`.
  1. Policz `sha256hex(buffer)`.
  2. Porównaj z nagłówkiem odpowiedzi `X-Document-Sha256` ORAZ z `documentSha256` z obiektu pending. **Rozbieżność = twardy błąd** („Integralność dokumentu nie mogła zostać potwierdzona — wezwij pracownika") — nie renderuj dokumentu.
  3. Zachowaj policzony hex w pamięci (stan React) — to on poleci w submit.
  4. Renderuj PDF przez pdf.js **z tego samego bufora**, wszystkie strony, scrollowalnie, z pinch-zoom.
- Pod dokumentem: checkbox z **dokładną treścią** `declarationText` z API. Przycisk **„Przejdź do podpisu"** jest nieaktywny, dopóki checkbox nie jest zaznaczony. Moment zaznaczenia zapisz jako ISO timestamp (`declarationAcceptedAt`).
- Drugi przycisk: **„Odmawiam podpisu"** → `POST .../decline` (opcjonalny powód) → STANDBY.
- Jeśli w trakcie przyjdzie WS `SIGNATURE_CANCELLED` z tym `requestId` → przerwij i wróć do STANDBY („Pracownik anulował żądanie").

### SIGNATURE_PAD

- Pełnoekranowe pole podpisu w orientacji poziomej: canvas o **przezroczystym tle** (ciemny UI wokół, samo pole lekko rozjaśnione ramką, ale tło canvasa = transparent). Kreski w kolorze ciemnogranatowym/czarnym (#1a1a2e lub #000), grubość zmienna od prędkości (naturalny wygląd).
- Obsługa `pointer events` (palec + rysik), `touch-action: none` na canvasie, canvas skalowany o `devicePixelRatio` (ostre kreski).
- Przyciski: **„Wyczyść"** (reset canvasa), **„Wstecz"** (powrót do dokumentu, checkbox zostaje), **„Gotowe"** (aktywny tylko, gdy cokolwiek narysowano — minimum kilkadziesiąt pikseli tuszu; serwer odrzuca puste podpisy).
- Eksport: `canvas.toDataURL("image/png")` → base64 **bez** prefiksu `data:image/png;base64,`.

### SUBMITTING / THANK_YOU / ERROR_SCREEN

- SUBMITTING: spinner + „Przetwarzanie podpisu…" (serwer scala PDF i nakłada pieczęć — może potrwać 2–8 s). Zablokuj wszystkie interakcje. **Nie ponawiaj automatycznie submitu** — challenge jest jednorazowy; retry po błędzie sieci najpierw sprawdza `GET /api/v1/...` nie — tablet nie ma dostępu; po timeoucie pokaż ERROR_SCREEN z instrukcją wezwania pracownika (pracownik widzi stan w CRM).
- THANK_YOU: „Dziękujemy! Dokument został podpisany." + ikona sukcesu; po 4 s automatycznie STANDBY. Po sukcesie ORAZ po błędzie: wyczyść canvas, wyzeruj stany z buforem PDF, hashem, challenge.
- ERROR_SCREEN: czytelny komunikat z `message` z API + przycisk „OK" (→ STANDBY). Statusy: patrz kontrakt niżej.

## 5. Kontrakt API (obowiązujący, zaimplementowany na backendzie)

Wszystkie żądania (poza `pair`) wymagają nagłówka **`X-Tablet-Token: <token>`**.
Błędy mają format: `{ "error": string, "message": string, "timestamp": string }`.
Mapowanie statusów: `400` walidacja / sesja wygasła, `403` zły token / zły tablet / replay, `404` nie znaleziono, `409` konflikt (sesja już zakończona / integralność naruszona).

### 5.1 Parowanie

```
POST /api/tablet/pair
Content-Type: application/json

{ "pairingCode": "483920", "deviceName": "Recepcja 1" }

→ 201 Created
{ "tabletId": "0d9f...uuid", "token": "wJalrXUt...43znF", "studioId": "8c1b...uuid" }

→ 403  kod nieprawidłowy lub wygasły (kod jest jednorazowy)
```

### 5.2 Kontekst / walidacja tokenu przy starcie

```
GET /api/tablet/context
X-Tablet-Token: <token>

→ 200 { "tabletId": "...", "studioId": "...", "deviceName": "Recepcja 1" }
→ 403  token nieważny → wyczyść storage, pokaż parowanie
```

### 5.3 Oczekujące żądanie podpisu

```
GET /api/tablet/signature-requests/pending
X-Tablet-Token: <token>

→ 200
{
  "requestId": "b7e2...uuid",
  "documentName": "Protokół przyjęcia pojazdu",
  "signerName": "Jan Kowalski",
  "declarationText": "Oświadczam, że zapoznałem/zapoznałam się z treścią niniejszego dokumentu, rozumiem jego treść i akceptuję zawarte w nim ustalenia.",
  "documentSha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "challenge": "kJ8n...jednorazowy-nonce",
  "expiresAt": "2026-07-05T12:34:56Z",
  "documentUrl": "/api/tablet/signature-requests/b7e2.../document"
}

→ 204 No Content  — nic nie czeka (normalny stan w STANDBY)
```

### 5.4 Pobranie dokumentu (dokładne bajty do wyświetlenia)

```
GET /api/tablet/signature-requests/{requestId}/document
X-Tablet-Token: <token>

→ 200
Content-Type: application/pdf
X-Document-Sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
Cache-Control: no-store
<binarne bajty PDF>

→ 400  żądanie wygasło
→ 403  żądanie przypisane do innego tabletu
→ 409  sesja zakończona LUB integralność dokumentu naruszona
```

Uwaga: pobranie dokumentu zmienia status sesji na `DISPLAYED` po stronie serwera i jest
odnotowywane w łańcuchu audytowym — pobieraj dokument dopiero, gdy faktycznie go wyświetlasz.

### 5.5 Wysłanie podpisu

```
POST /api/tablet/signature-requests/{requestId}/submit
X-Tablet-Token: <token>
Content-Type: application/json

{
  "documentSha256": "<hex policzony przez tablet nad pobranymi bajtami>",
  "challenge": "<challenge z pending — jednorazowy>",
  "declarationAccepted": true,
  "declarationAcceptedAt": "2026-07-05T12:31:07Z",
  "signatureImageBase64": "<PNG z kanałem alfa, base64, bez prefiksu data:>"
}

→ 200
{ "requestId": "b7e2...", "status": "COMPLETED", "sealApplied": true, "timestampApplied": true }

→ 400  hash niezgodny / oświadczenie niezaakceptowane / pusty podpis / sesja wygasła
→ 403  challenge zużyty lub nieprawidłowy (wykryta próba powtórzenia) / inny tablet
→ 409  sesja już zakończona
```

Po odpowiedzi 400 (hash) i 403 (challenge) sesja jest po stronie serwera oznaczona jako
FAILED — nie ponawiaj; pracownik musi wysłać dokument ponownie z CRM.

### 5.6 Odmowa podpisu

```
POST /api/tablet/signature-requests/{requestId}/decline
X-Tablet-Token: <token>
Content-Type: application/json

{ "reason": "Klient chce skonsultować treść" }   // body opcjonalne

→ 200 { "requestId": "...", "status": "DECLINED", "sealApplied": false, "timestampApplied": false }
```

## 6. Kontrakt WebSocket (STOMP / SockJS)

- Endpoint: `https://detailboost.pl/ws-registry` (SockJS).
- **CONNECT** z nagłówkiem STOMP (connectHeaders): `{ "X-Tablet-Token": "<token>" }` — bez tego serwer odrzuci połączenie. Heart-beat: `10000,10000` (klient musi je deklarować i honorować — po 2 nieodebranych heartbeatach traktuj połączenie jako martwe i reconnectuj).
- **SUBSCRIBE** (jedyny dozwolony temat dla tabletu): `/topic/studio.{studioId}.tablet.signature` — `studioId` z odpowiedzi `pair`/`context`. Subskrypcja innego tematu = zerwanie połączenia przez serwer.
- Wiadomości (JSON):

```json
{
  "type": "SIGNATURE_REQUESTED",   // także: SIGNATURE_DISPLAYED, SIGNATURE_COMPLETED,
                                    // SIGNATURE_CANCELLED, SIGNATURE_DECLINED, SIGNATURE_FAILED
  "requestId": "b7e2...uuid",
  "tabletId": "0d9f...uuid | null", // null = żądanie dla dowolnego tabletu w salonie
  "documentName": "Protokół przyjęcia pojazdu",
  "signerName": "Jan Kowalski",
  "status": "PENDING_DISPLAY",
  "occurredAt": "2026-07-05T12:30:00Z"
}
```

Reakcje tabletu:
- `SIGNATURE_REQUESTED` z `tabletId == null` lub `tabletId == mój` → wybudź się i pobierz `pending`.
- `SIGNATURE_CANCELLED` / `SIGNATURE_COMPLETED` / `SIGNATURE_FAILED` dla aktualnie obsługiwanego `requestId` → przerwij bieżący ekran, sprzątnij pamięć, wróć do STANDBY (COMPLETED może przyjść, gdy inny tablet obsłużył wspólne żądanie).
- Pozostałe typy — ignoruj.

## 7. Wymagania niefunkcjonalne

- **Kiosk**: pełny ekran (Fullscreen API po pierwszym tapnięciu), `user-scalable=no` poza widokiem PDF, brak selekcji tekstu, brak menu kontekstowego, `overscroll-behavior: none`.
- **PWA**: manifest + service worker WYŁĄCZNIE dla powłoki aplikacji (app shell); **nigdy nie cache'uj** odpowiedzi `/api/**` ani dokumentów PDF.
- Duże cele dotykowe (min. 48px), wysoki kontrast na ciemnym tle, czcionka systemowa.
- Wskaźnik offline: gdy brak sieci — dyskretny banner na STANDBY „Brak połączenia — próbuję ponownie…".
- Testy: jednostkowe dla maszyny stanów i funkcji hashującej; e2e (Playwright) dla ścieżki parowanie → standby → podpis (z mockiem API).
- Struktura: `src/api` (klient REST + typy z tego dokumentu), `src/ws` (klient STOMP z reconnectem), `src/state` (maszyna stanów — np. XState lub reducer), `src/screens/{Pairing,Standby,DocumentReview,SignaturePad,ThankYou,Error}`, `src/components/SignatureCanvas`.

## 8. Definicja ukończenia (DoD)

1. Tablet sparowany kodem 6-cyfrowym; po restarcie przeglądarki następnego dnia łączy się automatycznie bez interakcji.
2. W STANDBY pokazuje ciemny zegar, trzyma wake lock, utrzymuje STOMP i wybudza się < 2 s po „Poproś o podpis" w CRM.
3. Hash liczony na tablecie zgadza się z nagłówkiem `X-Document-Sha256`; submit przechodzi (status `COMPLETED`).
4. Wyeksportowany podpis to PNG z przezroczystym tłem (weryfikacja: brak białych pikseli tła).
5. Po submit/odmowie/anulowaniu w pamięci aplikacji nie ma bufora PDF, bitmapy podpisu ani challenge (weryfikacja w devtools).
6. Odcięcie sieci w STANDBY → po przywróceniu połączenia oczekujące żądanie zostaje podjęte (polling fallback działa).
