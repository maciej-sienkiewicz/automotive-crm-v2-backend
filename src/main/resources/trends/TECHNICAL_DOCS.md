# Moduł Trends — Dokumentacja Techniczna

## 1. Cel modułu

Moduł **Trends** odpowiada za autonomiczne monitorowanie popularności fraz kluczowych związanych z branżą auto-detailingu na rynku polskim. Dostarcza dane o:

- **Wolumenie wyszukiwań** (miesięczna średnia + historia 12 miesięcy wstecz)
- **CPC i konkurencji** w Google Ads
- **Rozkładzie geograficznym** — Polska ogólnie + 16 województw osobno
- **Trendzie dziennym** — wypełnia lukę od ostatniego miesiąca z danych Google Ads do dnia dzisiejszego

Moduł działa **w pełni autonomicznie** — sam seeduje frazy, pobiera dane, odświeża je cyklicznie. Nie wymaga ręcznego triggerowania.

---

## 2. Architektura

```
┌─────────────────────────────────────────────────────────────────┐
│                         SCHEDULER                                │
│  (cron + ApplicationReadyEvent)                                  │
│                                                                  │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ INITIAL_SEED │  │ VOLUME_REFRESH   │  │ TREND_FILL       │  │
│  │ (first start)│  │ (weekly Mon 3AM) │  │ (daily 4AM)      │  │
│  └──────┬───────┘  └────────┬─────────┘  └────────┬─────────┘  │
│         │                   │                      │             │
│         ▼                   ▼                      ▼             │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              SearchVolumeClient (HTTP)                      │ │
│  │  • POST /v3/keywords_data/google_ads/search_volume/live    │ │
│  │  • POST /v3/keywords_data/dataforseo_trends/explore/live   │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────┬──────────────────────────────────┘
                               │ writes
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      PostgreSQL                                   │
│  tracked_keywords │ keyword_metrics │ monthly_searches │         │
│  trend_data       │ sync_status                                  │
└──────────────────────────────┬──────────────────────────────────┘
                               │ reads
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                  TrendsReadController (REST)                      │
│  GET /api/trends/keywords                                        │
│  GET /api/trends/keywords/{keyword}/history                      │
│  GET /api/trends/summary                                         │
│  GET /api/trends/voivodeships/{keyword}                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Źródło danych — DataForSEO

### Dlaczego DataForSEO?

- Jedyne API oferujące **Google Ads search volume** programowo (bez konta Google Ads i UI)
- Daje dostęp do **lokalizacji na poziomie województw** (Google Ads geotargeting regions)
- Posiada dodatkowy moduł **Trends Explore** do danych near-real-time

### Używane endpointy

| Endpoint | Rola | Limit | Docs |
|----------|------|-------|------|
| `POST /v3/keywords_data/google_ads/search_volume/live` | Wolumen, CPC, konkurencja, monthly_searches | 1000 keywords/req, **12 req/min** | [link](https://docs.dataforseo.com/v3/keywords_data/google_ads/search_volume/live) |
| `POST /v3/keywords_data/dataforseo_trends/explore/live` | Trend dzienny (index 0-100) | 5 keywords/req | [link](https://docs.dataforseo.com/v3/keywords_data/dataforseo_trends/explore/live) |

### Dlaczego dwa endpointy, a nie jeden?

| | Google Ads Search Volume | Trends Explore |
|---|---|---|
| **Dane** | Absolutne wolumeny (np. 720 searches/month) | Relatywny index 0-100 |
| **Granulacja** | Miesięczna | Dzienna/tygodniowa |
| **Opóźnienie** | ~5-6 tygodni (brak bieżącego miesiąca) | Dane do dnia dzisiejszego |
| **Geolokalizacja** | Dowolny location_code (kraj + województwa) | Tylko kraj (location_name) |
| **Max keywords** | 1000 / request | 5 / request |

**Search Volume** to główne źródło (absolutne liczby, podział na województwa). **Trends Explore** to uzupełnienie — wypełnia lukę czasową od ostatniego miesiąca z Google Ads do dzisiaj, aby na froncie był ciągły wykres trendu.

---

## 4. Lifecycle — jak moduł działa

### 4.1 Pierwszy start (INITIAL_SEED)

1. Aplikacja startuje → `@EventListener(ApplicationReadyEvent)` sprawdza tabelę `sync_status`
2. Jeśli `INITIAL_SEED` nie ma `last_success_at` → uruchamia seed **asynchronicznie** (nie blokuje startu)
3. Wstawia 30 predefiniowanych fraz do `tracked_keywords` ze statusem `ACTIVE`
4. Pobiera wolumeny z Google Ads dla **17 lokalizacji** (1 kraj + 16 województw)
5. Pobiera trendy z Trends Explore za ostatnie 3 miesiące
6. Zapisuje `sync_status.INITIAL_SEED.last_success_at`

**Czas trwania:** ~90 sekund (17 requestów × 5.2s rate-limit delay)

### 4.2 Odświeżanie wolumenów (VOLUME_REFRESH) — co poniedziałek 3:00

1. Pobiera wszystkie frazy ze statusem `ACTIVE`
2. Dla każdej z 17 lokalizacji wywołuje `search_volume/live`
3. Upsertuje `keyword_metrics` (najnowszy snapshot) i `monthly_searches` (historia)
4. Rate-limit: 5.2s między requestami

### 4.3 Wypełnianie trendów (TREND_FILL) — codziennie 4:00

1. Dla każdej frazy sprawdza ostatni miesiąc w `monthly_searches`
2. Oblicza lukę: `(ostatni_miesiąc + 1 miesiąc)` → `dzisiaj`
3. Wywołuje Trends Explore w batchach po 5 keywords
4. Upsertuje `trend_data` (dzienne punkty, index 0-100)

---

## 5. Schemat bazy danych

```
tracked_keywords
├── id (PK, BIGSERIAL)
├── keyword (UNIQUE, TEXT)
├── status (PENDING | ACTIVE | IGNORED)
├── source (SEED | EXPANDED | MANUAL)
├── added_at (TIMESTAMP)
└── last_fetched_at (TIMESTAMP)

keyword_metrics
├── id (PK)
├── keyword_id (FK → tracked_keywords)
├── location_code (INT) ─── 2616=Polska, 20861-20876=województwa
├── search_volume (INT)
├── cpc (DOUBLE)
├── competition (HIGH | MEDIUM | LOW)
├── competition_index (0-100)
├── low_top_of_page_bid (DOUBLE)
├── high_top_of_page_bid (DOUBLE)
├── fetched_at (TIMESTAMP)
└── UNIQUE(keyword_id, location_code)

monthly_searches
├── id (PK)
├── keyword_id (FK)
├── location_code (INT)
├── year (INT)
├── month (INT)
├── search_volume (INT)
└── UNIQUE(keyword_id, location_code, year, month)

trend_data
├── id (PK)
├── keyword_id (FK)
├── date (DATE)
├── trend_index (INT, 0-100)
├── location_code (INT, default 2616)
└── UNIQUE(keyword_id, date, location_code)

sync_status
├── task_name (PK, TEXT)
├── last_run_at (TIMESTAMP)
├── last_success_at (TIMESTAMP)
├── status (IDLE | RUNNING | FAILED)
└── details (TEXT)
```

Migracja: `src/main/resources/db/migration/V1__keyword_monitoring_schema.sql` (Flyway)

---

## 6. REST API (tylko odczyt)

Wszystkie endpointy czytają z bazy danych. **Zero wywołań do DataForSEO** na request użytkownika.

### `GET /api/trends/keywords`

Lista fraz z aktualymi metrykami.

| Param | Default | Opis |
|-------|---------|------|
| `locationCode` | 2616 | Lokalizacja (2616=PL, 20861=pomorskie, ...) |
| `sort` | volume | volume, cpc, competition, keyword |
| `status` | ACTIVE | ACTIVE, PENDING, IGNORED |

Odpowiedź:
```json
{
  "locationCode": 2616,
  "locationName": "Poland",
  "totalKeywords": 30,
  "keywords": [
    {
      "keyword": "myjnia samochodowa",
      "searchVolume": 49500,
      "cpc": 0.42,
      "competition": "HIGH",
      "competitionIndex": 87,
      "lastFetchedAt": "2026-05-05T03:12:00Z"
    }
  ]
}
```

### `GET /api/trends/keywords/{keyword}/history`

Pełna historia frazy: monthly volumes + daily trend.

| Param | Default | Opis |
|-------|---------|------|
| `locationCode` | 2616 | Lokalizacja |
| `from` | -12 months | Start zakresu (YYYY-MM-DD) |
| `to` | today | Koniec zakresu |

Odpowiedź:
```json
{
  "keyword": "folia PPF",
  "locationCode": 2616,
  "locationName": "Poland",
  "currentMetrics": {
    "searchVolume": 720,
    "cpc": 0.7,
    "competition": "HIGH",
    "competitionIndex": 100
  },
  "monthlySearches": [
    {"year": 2025, "month": 5, "searchVolume": 880},
    {"year": 2025, "month": 6, "searchVolume": 880}
  ],
  "dailyTrend": [
    {"date": "2026-04-01", "trendIndex": 72},
    {"date": "2026-04-02", "trendIndex": 68}
  ]
}
```

### `GET /api/trends/summary`

Dashboard — top frazy, status synchronizacji.

### `GET /api/trends/voivodeships/{keyword}`

Porównanie jednej frazy we wszystkich województwach (posortowane malejąco po wolumenie).

---

## 7. Konfiguracja

Plik `application.properties`:

```properties
dataforseo.login=${DATAFORSEO_LOGIN}
dataforseo.password=${DATAFORSEO_PASSWORD}
dataforseo.base-url=https://api.dataforseo.com
dataforseo.max-retries=3
dataforseo.backoff-millis=1000
dataforseo.location-name=Poland
dataforseo.type=web
```

**Nigdy nie commituj credentials.** Podawać przez zmienne środowiskowe lub Vault.

---

## 8. Mapowanie lokalizacji (województwa)

Hardcoded w `PolandLocations` (plik `SearchVolumeModels.kt`). Wartości `location_code` z Google Ads geotargetingu:

| Województwo | location_code |
|-------------|---------------|
| Polska (kraj) | 2616 |
| dolnośląskie | 20862 |
| kujawsko-pomorskie | 20863 |
| lubelskie | 20864 |
| lubuskie | 20865 |
| łódzkie | 20866 |
| małopolskie | 20867 |
| mazowieckie | 20868 |
| opolskie | 20869 |
| podkarpackie | 20870 |
| podlaskie | 20871 |
| pomorskie | 20861 |
| śląskie | 20872 |
| świętokrzyskie | 20873 |
| warmińsko-mazurskie | 20874 |
| wielkopolskie | 20875 |
| zachodniopomorskie | 20876 |

Źródło: `GET /v3/keywords_data/google_ads/locations` filtrowane po `country_iso_code=PL`, `location_type=Region`.

---

## 9. Rate-limiting i retry

- **Google Ads Search Volume:** max **12 requests/minute** per account
- Implementacja: `Thread.sleep(5200ms)` między requestami (= max 11.5 req/min, z marginesem)
- Full sweep (17 lokalizacji) trwa ~90 sekund
- **Retry:** exponential backoff (1s → 2s → 4s), max 3 próby
- Retryable errors: HTTP 429, 500, 502, 503, 504
- Non-retryable: HTTP 401, 403 (auth failure)

---

## 10. Struktura plików

```
src/main/kotlin/com/example/demo/trends/
├── config/
│   ├── DataForSeoConfig.kt          — Bean RestClient z Basic Auth
│   └── DataForSeoProperties.kt      — @ConfigurationProperties
├── controller/
│   └── TrendsReadController.kt      — 4 GET endpointy (read-only)
├── exception/
│   ├── TrendsExceptions.kt          — Hierarchia wyjątków
│   └── TrendsExceptionHandler.kt    — @ControllerAdvice
├── repository/
│   └── Repositories.kt              — 5 repozytoriów (JdbcTemplate)
├── scheduler/
│   └── KeywordSyncScheduler.kt      — Cron + ApplicationReady logic
└── searchvolume/
    ├── client/
    │   └── SearchVolumeClient.kt     — HTTP client (retry, backoff)
    └── model/
        └── SearchVolumeModels.kt     — DTOs, PolandLocations mapping
```

---

## 11. Wymagania infrastrukturalne

| Komponent | Wersja | Rola |
|-----------|--------|------|
| PostgreSQL | 14+ | Baza danych (tracked_keywords, metrics, trends) |
| Spring Boot | 4.0.x | Framework |
| Flyway | (auto) | Migracje schematu |
| JDK | 17+ | Runtime |

---

## 12. Znane ograniczenia i TODO

1. **Brak keyword expansion** — aktualnie moduł nie rozszerza automatycznie listy fraz (brak endpointu `keywords_for_keywords`). Frazy są hardcoded w `SEED_KEYWORDS`.
2. **In-memory scheduler lock** — `sync_status` chroni przed overlapping, ale tylko w ramach jednej instancji. Dla wielu instancji potrzebny ShedLock lub advisory lock.
3. **Brak paginacji** w endpointach REST — przy 500+ fraz potrzebna.
4. **Trends Explore daje dane tylko dla kraju** — nie ma podziału na województwa w tym endpoincie.
5. **Thread.sleep w schedulerze** — blokuje wątek. Dla produkcji lepiej użyć coroutines z delay.
6. **Brak dead-letter / alertingu** — jeśli sync failuje, jedyny sygnał to `sync_status.status = FAILED`.

---

## 13. Jak dodać nowe frazy

Aktualnie: edytuj `SEED_KEYWORDS` w `KeywordSyncScheduler.kt` i zrestartuj aplikację (lub ręcznie INSERT do `tracked_keywords` ze statusem `ACTIVE`).

Docelowo: endpoint `POST /api/trends/keywords` lub integracja z modułem keyword expansion.

---

## 14. Koszty DataForSEO

- `search_volume/live`: **0.075 USD / request** (niezależnie od ilości keywords w batchu)
- `trends/explore/live`: **0.01 USD / request**
- Full sweep (17 lokalizacji): 17 × $0.075 = **$1.275** per refresh
- Tygodniowo: ~$1.28 (volume) + ~$0.06 (trends daily × 7) ≈ **$1.34 / tydzień**
- Miesięcznie: ~**$5.50**

---

## 15. Monitoring i observability

Logi (SLF4J, level DEBUG dla `com.example.demo.trends`):

- Każdy request do DataForSEO: endpoint, czas odpowiedzi, status
- Każdy batch: ilość keywords, location, sukces/failure
- Sync lifecycle: start, sukces, failure z exception message
- **Nigdy nie loguje credentials**

Status synchronizacji: `GET /api/trends/summary` → pole `syncStatuses`.

