# Podpis na tablecie — architektura bezpieczeństwa (eIDAS)

Dokument opisuje implementację procesu podpisywania protokołów i zgód na tablecie
w sposób odporny na zarzut „mój podpis został skopiowany i wklejony do innej umowy"
(atak przez powtórzenie / oderwanie podpisu). Stanowi część Polityki Bezpieczeństwa
systemu i może być przedstawiony jako dowód architektoniczny w postępowaniu z art. 253 KPC.

Moduł: `pl.detailing.crm.signing`

## Przepływ biznesowy

```
CRM (pracownik)                      Backend (Spring)                        Tablet (klient)
──────────────────                   ─────────────────                       ───────────────
1. „Poproś o podpis"  ─────────────► POST /api/v1/visits/{v}/protocols/{p}/signature-requests
                                     • pobiera DOKŁADNY wypełniony PDF z S3
                                     • liczy SHA-256 dokumentu  ── WYSIWYS ANCHOR
                                     • tworzy SignatureRequest (Postgres)
                                     • wystawia jednorazowy challenge (Redis, GETDEL)
                                     • publikuje event (Redis pub/sub → STOMP)
                                                     │
                                                     ▼  /topic/studio.{id}.tablet.signature
2.                                                                      GET /api/tablet/signature-requests/pending
                                                                        GET /api/tablet/signature-requests/{id}/document
                                     • bajty PDF re-hashowane przy wydaniu
                                     • hash w nagłówku X-Document-Sha256
                                     • status → DISPLAYED + wpis audytowy
                                                                        3. klient czyta dokument,
                                                                           zaznacza oświadczenie,
                                                                           podpisuje na PRZEZROCZYSTYM canvasie
4.                                                                      POST /api/tablet/signature-requests/{id}/submit
                                                                           {obraz podpisu (PNG+alpha, base64),
                                                                            hash wyświetlonego dokumentu,
                                                                            challenge, declarationAccepted, czas}
                                     5. SubmitSignatureHandler:
                                     • challenge zużyty ATOMOWO (anty-replay)
                                     • hash z tabletu == hash żądania == hash bajtów z S3
                                     • obraz podpisu: TYLKO RAM (normalizacja → przezroczyste PNG)
                                     • PDF + podpis + Karta Podpisu (ścieżka audytu)
                                     • kwalifikowana pieczęć PAdES + znacznik czasu RFC 3161
                                     • upload WYŁĄCZNIE zaplombowanego PDF do S3
                                     • protokół → SIGNED, bufor podpisu WYZEROWANY
```

## Realizacja wymogów prawnych

### 1. WYSIWYS — powiązanie podpisu z hashem dokumentu

* `RequestSignatureHandler` liczy SHA-256 nad dokładnymi bajtami wypełnionego PDF
  w momencie kliknięcia „Poproś o podpis" (`SignatureRequest.documentSha256`).
* `TabletSignatureController.getDocument` ponownie hashuje bajty przy wydaniu na tablet —
  jeżeli obiekt w S3 zmienił się od utworzenia żądania, dokument NIE zostaje wyświetlony.
* Tablet odsyła pakiet `[obraz podpisu + hash wyświetlanego dokumentu + czas]`.
* `SubmitSignatureHandler` wymaga potrójnej zgodności: hash z tabletu == hash żądania ==
  hash bajtów ponownie pobranych z magazynu. Porównania stało-czasowe (`MessageDigest.isEqual`).
* Dodatkowo każda sesja ma jednorazowy challenge (Redis `GETDEL`) — przechwycony
  pakiet submit nie może zostać odtworzony (ochrona przed atakiem przez powtórzenie).

### 2. Natychmiastowe zniszczenie pliku graficznego podpisu (RAM-only)

**Podział odpowiedzialności tablet/backend (odpowiedź na pytanie projektowe):**

* **Tablet** rysuje kreski podpisu na w pełni PRZEZROCZYSTYM canvasie i wysyła PNG
  z kanałem alfa — po stronie klienta tło nigdy nie powstaje. Po `submit` aplikacja
  tabletowa MUSI usunąć bitmapę i dokument z pamięci podręcznej.
* **Backend nie ufa klientowi** — `SignatureImageProcessor` wymusza gwarancję po stronie
  serwera: dekoduje obraz w RAM, usuwa ewentualne nieprzezroczyste tło (piksele
  quasi-białe → alfa 0), przycina do obwiedni kresek, odrzuca puste podpisy.
  To backend jest gwarantem, że na PDF trafiają wyłącznie kreski podpisu.

Gwarancje minimalizacji danych:

* obraz podpisu istnieje wyłącznie w pamięci operacyjnej w obrębie jednej transakcji HTTP;
* NIE jest zapisywany na dysk, do S3, bazy danych ani logów
  (`VisitProtocol.signatureImageS3Key = null` dla tego przepływu);
* bufory (surowy upload + znormalizowany PNG) są jawnie zerowane w bloku `finally`
  (`SignatureImageProcessor.wipe`) — również przy niepowodzeniu transakcji.

System fizycznie nie posiada wyciągalnego pliku z podpisem klienta.

### 3. Karta Podpisu / Ścieżka Audytu

* `SignatureAuditTrailService` — łańcuch zdarzeń w tabeli `signature_audit_events`,
  każde zdarzenie zawiera hash poprzedniego (`previousEventHash`) i własny
  (`eventHash` = SHA-256 kanonicznej postaci pól + hash poprzednika). Modyfikacja,
  usunięcie lub przestawienie wpisu łamie łańcuch w sposób wykrywalny przez biegłego.
* Zdarzenia: REQUEST_CREATED → DOCUMENT_DELIVERED → DECLARATION_ACCEPTED →
  SIGNATURE_SUBMITTED → HASH_VERIFIED → DOCUMENT_SEALED → REQUEST_COMPLETED
  (oraz CANCELLED / DECLINED / EXPIRED / FAILED).
* `AuditTrailPageGenerator` dokleja do PDF ostatnią stronę („KARTA PODPISU — ŚCIEŻKA
  AUDYTU") zawierającą: ID dokumentu i sesji podpisu, numer wizyty, SHA-256 dokumentu
  źródłowego, imię i nazwisko podpisującego, pracownika (login CRM), adres IP i
  urządzenie, treść zaakceptowanego oświadczenia oraz chronologię zdarzeń z dokładnością
  do sekundy (Europe/Warsaw). Strona jest doklejana PRZED pieczęcią — pieczęć ją obejmuje.

### 4. Kwalifikowana pieczęć elektroniczna + kwalifikowany znacznik czasu

* `PadesQualifiedSealService` — pieczęć w formacie PAdES (`ETSI.CAdES.detached`):
  CMS SignedData (BouncyCastle) z atrybutem `signing-certificate-v2`, bez `signingTime`
  (zgodnie z PAdES), zapis inkrementalny PDFBox (ByteRange obejmuje cały dokument:
  protokół + obraz podpisu + kartę audytu).
* Znacznik czasu RFC 3161 (`id-aa-signatureTimeStampToken`) pobierany z TSA i osadzany
  w niepodpisanych atrybutach SignerInfo (poziom PAdES B-T).
* Konfiguracja (`application.properties` / zmienne środowiskowe):

| Klucz | Opis |
|---|---|
| `signing.seal.enabled` | włącza pieczętowanie |
| `signing.seal.required` | `true` = fail-closed: brak pieczęci przerywa podpisanie (produkcja) |
| `signing.seal.keystore-path/-password`, `key-alias/-password` | PKCS#12 z certyfikatem pieczęci wydanym przez kwalifikowanego dostawcę (Certum, KIR Szafir, EuroCert) |
| `signing.seal.tsa-url` | kwalifikowane TSA, np. `http://time.certum.pl` |

**Uwaga prawna:** status „kwalifikowana" pieczęć uzyskuje z certyfikatu wydanego przez
kwalifikowanego dostawcę usług zaufania (rejestr EU Trust List); wymogi ochrony klucza
(QSCD/HSM) mogą w praktyce wymagać integracji z HSM dostawcy zamiast pliku PKCS#12 —
interfejs `QualifiedSealService` jest punktem wpięcia takiej integracji.

Skutek: od chwili opieczętowania plik jest zaplombowany. Wycięcie wizualizacji podpisu
i wklejenie do innego dokumentu tworzy plik BEZ ważnej pieczęci i znacznika czasu —
dokument oryginalny korzysta z domniemania integralności i autentyczności
(art. 35 ust. 2 eIDAS), a ciężar dowodu przechodzi na kwestionującego (art. 253 KPC).

## Bezpieczeństwo kanału tabletu

* Parowanie: pracownik generuje 6-cyfrowy kod (TTL 5 min, jednorazowy — Redis GETDEL),
  tablet wymienia go na długożyciowy token urządzenia (`X-Tablet-Token`, TTL 30 dni,
  odświeżany przy użyciu; odwoływalny: `DELETE /api/v1/tablets/{tabletId}`).
* Izolacja tenantów: `tenantId` wyłącznie z metadanych tokenu w Redis, nigdy z żądania
  (wzorzec identyczny jak `X-Upload-Token` przy zdjęciach QR).
* Endpointy `/api/tablet/**` są `permitAll` w Spring Security — uwierzytelnienie
  realizuje warstwa tokenów; dokument wydawany z `Cache-Control: no-store`.
* Żądanie podpisu może być przypięte do konkretnego tabletu (`tabletId`) — inny tablet
  otrzyma 403.

## Nowe tabele (Hibernate ddl-auto=update)

* `signature_requests` — sesje podpisu (pełne metadane transakcji),
* `signature_audit_events` — łańcuch audytowy (hash-chained).

## Endpointy

CRM (sesja):
* `POST /api/v1/visits/{visitId}/protocols/{protocolId}/signature-requests` — „Poproś o podpis"
* `GET/DELETE /api/v1/signature-requests/{id}` — status / anulowanie
* `POST /api/v1/tablets/pairing-codes`, `DELETE /api/v1/tablets/{tabletId}`

Tablet (`X-Tablet-Token`):
* `POST /api/tablet/pair`
* `GET /api/tablet/signature-requests/pending`
* `GET /api/tablet/signature-requests/{id}/document`
* `POST /api/tablet/signature-requests/{id}/submit`
* `POST /api/tablet/signature-requests/{id}/decline`

WebSocket (STOMP):
* `/topic/studio.{studioId}.tablet.signature` — powiadomienie tabletów o oczekującym dokumencie
* `/topic/studio.{studioId}.signature.{requestId}` — status na żywo dla CRM
