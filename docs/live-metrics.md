# Live metrics — śledzenie zdarzeń biznesowych w czasie rzeczywistym

Zastępuje moduł metryk (`crm/metrics`, tabele `metric_*`, joby rollupów, audyt API,
sesje), aspekty `crm/observability` i stare dashboardy Grafany. Migracja
`V104__drop_metrics_module.sql` kasuje tabele. Prometheus i Grafana zostają jako
warstwa wizualizacji — zasilane teraz przez licznik zdarzeń biznesowych.

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

  LiveMetricsPrometheusExporter ──► /actuator/prometheus ──► Prometheus ──► Grafana
        (licznik per tenant/typ/wymiar + gauge'e „dziś”/„60 min”/profil godzinowy z Redisa)
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

## Dashboardy (Grafana)

Grafana jest provisionowana z repo (`deploy/monitoring/grafana/provisioning`), źródłem
danych jest Prometheus scrape'ujący `/actuator/prometheus`. Nic nie trzeba klikać:
usunięcie pliku z repo usuwa dashboard (`disableDeletion: false`).

| Dashboard | UID | Co pokazuje |
|---|---|---|
| Live metrics — platforma | `crm-live-platform` | KPI „dziś” per typ, rezerwacje na żywo, rozkład godzinowy rezerwacji (7 dni), wizyty bezpośrednie vs z rezerwacji, zdjęcia wg miejsca, nowości w cenniku, log aktywności, tempo zdarzeń/min, tabela tenantów, stan potoku |
| Live metrics — tenant | `crm-live-tenant` | ten sam zestaw dla jednego tenanta (zmienna `$tenant`) |

Odświeżanie co 10 s. Metryki eksportowane przez `LiveMetricsPrometheusExporter`:

| Metryka | Etykiety | Źródło | Agregacja w Grafanie |
|---|---|---|---|
| `crm_business_events_total` | `tenant_id, tenant, type, dimension` | licznik ingestu tej instancji | `sum(increase(...[$__interval]))` |
| `crm_business_events_today` | `tenant_id, tenant, type` | Redis, co 15 s | `max by (tenant_id)` (ta sama wartość na każdej instancji) |
| `crm_business_events_hour_of_day` | `tenant_id, tenant, type, hour` | Redis (7 dni), co 5 min; per tenant tylko `RESERVATION_CREATED`, `tenant_id="_platform"` dla wszystkich typów | `max by (hour, tenant_id)` |
| `crm_live_metrics_pipeline_*`, `crm_live_metrics_sse_subscribers` | — | stan potoku instancji | `sum` |

Kardynalność jest zamknięta: tenant × typ (5) × wymiar (≤4), godzina (24) tylko dla
rezerwacji per tenant. Żadnych id encji w etykietach — te idą wyłącznie do strumienia
Redis (`attributes`) dla SPA.

### Trzy pułapki, na które te dashboardy są odporne

**Krok minimalny 1 min na wykresach.** Scrape trwa 15 s, a `increase()` potrzebuje co
najmniej dwóch próbek w oknie. Bez wymuszonego kroku `$__interval` na szerokim panelu
schodzi do kilku sekund i każdy słupek jest pusty — wykres pokazuje zero mimo poprawnych
danych w Prometheusie. Panele mają `interval: 1m` i pytają o `[$__rate_interval]`.

**Tabela tenantów to jedno zapytanie.** Sklejanie sześciu zapytań `joinByField` po
`tenant_id` rozjeżdżało kolumny, gdy któraś seria nie istniała. Teraz jedno zapytanie
`max by (tenant, type) (crm_business_events_today)` i pivot `groupingToMatrix`.

**`—` zamiast `0` na kaflach KPI.** Gauge wystawia wiersz dla każdego tenanta i typu, także
z zerem. Brak danych oznacza więc awarię scrape'u, nie spokojny dzień — i ma wyglądać inaczej
niż prawdziwe zero.

**Filtrowanie po `tenant_id`, nigdy po nazwie studia.** Grafana escapuje wartość zmiennej
wstawianą do zapytania Prometheusa, więc apostrof w `Maciej Sienkiewicz's Detailing Studio`
trafiał do matchera jako `\'` i `tenant="$tenant"` nie pasowało do niczego. Mylące było to,
że tytuł wiersza wyglądał poprawnie — tam interpolacja jest zwykłym tekstem, nie zapytaniem.
Wybierak na dashboardzie tenanta operuje więc na `tenant_id` (UUID, nic do escapowania,
odporne na zmianę nazwy), a nazwa studia jest doklejana do tytułów przez ukrytą zmienną
`tenant_name` wyprowadzoną z wybranego identyfikatora.

### Regeneracja dashboardów

JSON dashboardów jest artefaktem. Oba pliki dzielą ten sam zestaw paneli, więc ręczna edycja
jednego rozjeżdża je względem siebie i gubi reguły wymuszone wyżej. Po każdej zmianie:

```bash
python3 deploy/monitoring/grafana/generate_dashboards.py
```

## Konfiguracja (`crm.live-metrics.*`)

| Klucz | Domyślnie |
|---|---|
| `enabled` | `true` |
| `platform-api-key` | `${PLATFORM_METRICS_KEY:}` |
| `zone` | `Europe/Warsaw` |
| `retention.minute-days` / `retention.hour-days` | `3` / `90` |
| `prometheus-refresh-seconds` | `15` |
| `recent-events` | `200` |
| `stream-max-length` | `100000` (XTRIM ~ co 1000 zapisów) |
| `ingest.queue-capacity` / `batch-size` / `flush-interval-ms` | `20000` / `500` / `250` |
