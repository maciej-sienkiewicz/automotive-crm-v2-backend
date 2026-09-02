# Live metrics — śledzenie zdarzeń biznesowych w czasie rzeczywistym

Zastępuje moduł metryk (`crm/metrics`, tabele `metric_*`, joby rollupów, audyt API,
sesje) oraz warstwę Prometheus/Grafana (`crm/observability`, `deploy/monitoring`).
Wszystko to zostało usunięte; migracja `V104__drop_metrics_module.sql` kasuje tabele.

## Architektura

```
  handler biznesowy                    (rezerwacja, wizyta, usługa, zdjęcie, wpis audytu)
        │  BusinessEventPublisher.publish(tenantId, type, dimension, attributes)
        ▼
  Spring ApplicationEvent ──► BusinessEventIngestListener   @TransactionalEventListener(AFTER_COMMIT,
        │                                                   fallbackExecution = true)
        ▼
  BusinessEventIngestWorker   ograniczona kolejka (20k) + 1 wątek, partie co 250 ms
        │  jeden pipeline Redis
        ▼
  Redis ──┬── lm:events                  XADD  (Redis Streams — transport między instancjami)
          ├── lm:{scope}:{series}:m:*    HINCRBY minuta   (TTL 3 dni)
          ├── lm:{scope}:{series}:h:*    HINCRBY godzina  (TTL 90 dni)
          ├── lm:{scope}:{series}:d      HINCRBY dzień    (bez TTL)
          ├── lm:{scope}:total / :last   suma od początku / ostatnie zdarzenie per seria
          └── lm:{scope}:recent          LPUSH+LTRIM ostatnie 200 zdarzeń (JSON)
        │
        ▼  StreamMessageListenerContainer (każda instancja czyta od `$`)
  LiveMetricsBroadcaster ──┬── STOMP  /topic/studio.{tenantId}.metrics   (SPA studia)
                           ├── SSE    /api/v1/live-metrics/stream         (studio, sesja)
                           └── SSE    /api/internal/live-metrics/stream   (platforma, X-Platform-Key)
```

`scope` to `t:{tenantId}` **i** `p` (platforma) — każde zdarzenie inkrementuje oba,
więc konsola operatora nie sumuje tenantów przy każdym odświeżeniu.

Hot path ma trzy nienegocjowalne własności: nigdy nie rzuca, nigdy nie blokuje
(pełna kolejka odrzuca), degraduje się widocznie (`pipeline.dropped` w konsoli).

## Zdarzenia

| Typ | Wymiar (pod-serie) | Gdzie emitowane |
|---|---|---|
| `RESERVATION_CREATED` | — | `CreateAppointmentHandler` (także z leada), `CreateRecurringAppointmentHandler` (każde kolejne wystąpienie serii) |
| `VISIT_CREATED` | `origin` = `DIRECT` \| `FROM_RESERVATION` | `CreateVisitFromReservationHandler.handleWalkIn` (DIRECT), `.handle` (FROM_RESERVATION). `ConfirmVisitHandler` nie tworzy wizyty (zmienia status), więc nie emituje. Rezerwacja syntetyczna tworzona pod walk-in **nie** liczy się jako `RESERVATION_CREATED`. |
| `SERVICE_CREATED` | `kind` = `SERVICE` \| `PACKAGE` | `CreateServiceHandler`, `CreatePackageHandler` |
| `PHOTO_UPLOADED` | `target` = `VISIT` \| `VEHICLE` \| `BATCH_ORDER` \| `CHECKIN` | `AddVisitPhotoHandler`, `AddVehiclePhotoHandler`, `AddBatchOrderPhotoHandler` (po zapisie metadanych i wydaniu presigned URL), `CheckinPhotoService.uploadPhoto` (po `putObject` do S3) |
| `ACTIVITY_LOGGED` | — | `AuditLogWriter.write` — jedyny punkt zapisu do `audit_logs`; listener AFTER_COMMIT respektuje transakcję REQUIRES_NEW |

Wymiary są zamkniętymi zbiorami (`BusinessEventType.dimensions`) — konstruktor
`BusinessEvent` odrzuca inne wartości. Wszystko o nieograniczonej kardynalności
(id encji, nazwy) idzie w `attributes` wyłącznie do strumienia, nigdy do kluczy.

Nazwy serii: `RESERVATION_CREATED`, `VISIT_CREATED`, `VISIT_CREATED:DIRECT`,
`VISIT_CREATED:FROM_RESERVATION`, `SERVICE_CREATED[:SERVICE|:PACKAGE]`,
`PHOTO_UPLOADED[:VISIT|:VEHICLE|:BATCH_ORDER|:CHECKIN]`, `ACTIVITY_LOGGED`.

## API

### Studio (sesja CRM, uprawnienie `STATISTICS_VIEW`)

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/v1/live-metrics/overview` | KPI, serie (60 min/24 h/30 dni), profil godzinowy 7 dni, ostatnie zdarzenia |
| GET | `/api/v1/live-metrics/series?series=&bucket=minute\|hour\|day&from=&to=` | jedna seria w zadanym oknie (max 3 dni / 90 dni / 400 dni) |
| GET | `/api/v1/live-metrics/hour-profile?series=&days=7` | rozkład 0–23 h |
| GET | `/api/v1/live-metrics/events?limit=50` | ostatnie zdarzenia |
| GET | `/api/v1/live-metrics/stream` | SSE — ramki `BUSINESS_EVENT` + `HEARTBEAT` co 15 s |
| STOMP | `/topic/studio.{studioId}.metrics` | ta sama ramka przez `/ws-registry` |

Ramka:
```json
{"kind":"BUSINESS_EVENT","timestamp":"…","event":{"id":"…","tenantId":"…","type":"VISIT_CREATED",
 "series":["VISIT_CREATED","VISIT_CREATED:FROM_RESERVATION"],"dimension":"origin",
 "dimensionValue":"FROM_RESERVATION","occurredAt":"…","attributes":{"visitId":"…","appointmentId":"…","userId":"…"}}}
```

### Platforma (`X-Platform-Key` = `PLATFORM_METRICS_KEY`; brak klucza = 503)

| Metoda | Ścieżka |
|---|---|
| GET | `/api/internal/live-metrics/overview` — platforma + tabela tenantów (dziś per typ) + stan potoku |
| GET | `/api/internal/live-metrics/tenants/{tenantId}/overview` |
| GET | `/api/internal/live-metrics/series|hour-profile|events` (+ opcjonalnie `tenantId=`) |
| GET | `/api/internal/live-metrics/pipeline` |
| GET | `/api/internal/live-metrics/stream` — SSE ze wszystkich tenantów |

`PlatformKeyInterceptor` chroni całe `/api/internal/**` (także `/api/internal/studios`).

## Dashboardy

Serwowane przez backend jako statyczne strony bez zewnętrznych zależności:

- `/live-metrics/platform.html` — konsola operatora: 5 kafli KPI z iskierkami, wykresy
  (rezerwacje na żywo, godziny rezerwacji, lejek wizyt bezpośrednie/z rezerwacji,
  zdjęcia wg miejsca, nowości w cenniku, log aktywności), strumień zdarzeń, tabela
  tenantów, stan potoku. Klucz podaje się raz, zostaje w `sessionStorage` karty.
- `/live-metrics/studio.html` — ten sam zestaw dla zalogowanego studia (cookie sesji).

Wykresy aktualizują się z ramki SSE (patch w pamięci) i co 60 s z pełnego snapshotu.
Każdy wykres ma widok tabeli, tooltip i tryb jasny/ciemny.

## Konfiguracja (`crm.live-metrics.*`)

| Klucz | Domyślnie |
|---|---|
| `enabled` | `true` |
| `platform-api-key` | `${PLATFORM_METRICS_KEY:}` |
| `zone` | `Europe/Warsaw` |
| `retention.minute-days` / `retention.hour-days` | `3` / `90` |
| `recent-events` | `200` |
| `stream-max-length` | `100000` (XTRIM ~ co 1000 zapisów) |
| `ingest.queue-capacity` / `batch-size` / `flush-interval-ms` | `20000` / `500` / `250` |
