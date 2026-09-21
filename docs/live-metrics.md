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
          ├── lm:{scope}:sum             HINCRBY suma kwot w groszach (typy `monetary`)
          └── lm:{scope}:recent          LPUSH+LTRIM ostatnie 200 zdarzeń (JSON)
        │
        ▼  StreamMessageListenerContainer (każda instancja czyta od `$`)
  LiveMetricsBroadcaster ──┬── STOMP  /topic/studio.{tenantId}.metrics   (SPA studia)
                           ├── SSE    /api/v1/live-metrics/stream         (studio, sesja)
                           └── SSE    /api/internal/live-metrics/stream   (platforma, X-Platform-Key)

  LiveMetricsPrometheusExporter ──► /actuator/prometheus ──► Prometheus ──► Grafana
        (licznik per tenant/typ/wymiar + gauge'e „dziś”/„od początku”/kwoty/profil godzinowy z Redisa)

  TenantStateMetricsExporter ──────► /actuator/prometheus
        (druga, niezależna noga: stany 0/1 i wielkości bieżące czytane WPROST Z BAZY co 5 min)
```

Dwie nogi, bo to dwa różne pytania. **Zdarzenie** odpowiada na „ile razy” i musi być
policzone u źródła, bo baza często nie umie go odtworzyć (usunięta notatka, zanonimizowany
klient, nadpisana cena). **Stan** odpowiada na „czy teraz” i musi być czytany z bazy, bo
przełącza się w obie strony: pocztę można odłączyć, usługę skasować, a kredyty SMS maleją.
Suma zdarzeń `MAILBOX_CONNECTED` po odłączeniu skrzynki dalej pokazywałaby 1.

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
| `LEAD_CREATED` | `source` = `PHONE` \| `EMAIL` \| `FORM` \| `MANUAL` | `LeadMetricsListener` nasłuchuje `NewLeadCreatedEvent`. Lead powstaje na czterech ścieżkach (`CreateLeadHandler`, `HandleFormSubmissionHandler`, `FormMailLeadProcessor`, `MarkThreadAsLeadHandler`) i każda publikuje to zdarzenie — piąta policzy się sama |
| `MESSAGE_SENT` | `channel` = `SMS` \| `EMAIL` \| `MAILBOX` | `OutboundCommunicationGateway` (SMS + mail systemowy, tylko gdy `result.success`), `SendMailHandler` (`MAILBOX` — mail napisany ręcznie w Poczcie). Blokada (brak modułu, zgody, kredytów) **nie** jest wysyłką; od niej są liczniki `communication.blocked.*` |
| `CAMPAIGN_CREATED` | `medium` = `SMS` \| `EMAIL` \| `BOTH` | `CampaignService.create` |
| `EMPLOYEE_CREATED` | — | `CreateEmployeeHandler` |
| `VISIT_CARD_SENT` | `channel` = `EMAIL` \| `SMS` | `SendVisitCardLinkHandler`, `SendReservationCardLinkHandler` — po jednym zdarzeniu na **realnie wysłany** kanał, więc wysyłka na oba to dwa zdarzenia |
| `INSTAGRAM_PROFILE_ADDED` | — | `AddInstagramProfileHandler` |
| `MAILBOX_CONNECTED` | — | `MailAccountService.connect` — liczy konfigurowanie, nie skrzynki: ponowne podłączenie (zmiana hasła) to kolejne zdarzenie |
| `TASK_CREATED` | — | `CreateTaskHandler` |
| `CALENDAR_EVENT_CREATED` | — | `CalendarEventService.create` — wydarzenia własne, nie rezerwacje |
| `BATCH_CONTRACTOR_CREATED` | — | `CreateContractorHandler` |
| `BATCH_SERVICE_ADDED` | — | `CreateEntryHandler` — jedno zdarzenie na **pozycję** wpisu. `RegisterBatchServicesHandler` celowo nie liczy: tamten zapis to ciche „douczanie” katalogu, nie decyzja użytkownika |
| `LEAD_COMPLETED` | `status` = `COMPLETED` \| `LOST` \| `NO_SHOW` | `LeadStatusService.transition` — jedyny punkt zmiany statusu, więc łapie klik użytkownika, cykl życia rezerwacji i job no-show |
| `LEAD_QUOTED` | — | `LeadServiceItemsService.replaceItems`, tylko przy przejściu pusta → niepusta. „Niepusta” = pozycja `status != SUGGESTED && priceGross != null` (ten sam warunek co `LeadQuoteSyncService`). `estimatedValue` **nie** jest kryterium — sumuje też sugestie AI |
| `LEAD_RESERVATION_LINKED` | — | `LeadSyncService.linkAppointment` |
| `CUSTOMER_CREATED` | `origin` = `DIRECT` \| `APPOINTMENT` | `CreateCustomerHandler` (DIRECT), `CreateAppointmentHandler.createCustomer` (APPOINTMENT — klient założony mimochodem przy rezerwacji) |
| `CONSENT_SIGNED` | — | `SignConsentHandler` — zgoda marketingowa to podpis klienta pod definicją, nie pole na kliencie |
| `CUSTOMER_NOTE_ADDED` | — | `CustomerNoteService.addNote` |
| `CUSTOMER_DELETED` | — | `DeleteCustomerHandler` (anonimizacja RODO), za guardem idempotencji. Atrybuty bez danych osobowych |
| `CUSTOMER_NIP_SET` | — | `UpdateCompanyHandler`, `CreateCustomerHandler` — tylko przejście brak → wartość, żeby liczyć klientów z NIP, nie edycje |
| `VEHICLE_CREATED` | `origin` = `DIRECT` \| `APPOINTMENT` | `CreateVehicleHandler`, `CreateAppointmentHandler.createVehicle` |
| `VEHICLE_NOTE_ADDED` | — | `VehicleNoteService.addNote` |
| `FINANCIAL_DOC_ISSUED` 💰 | `documentType` = `INVOICE` \| `RECEIPT` \| `OTHER` | `CreateFinancialDocumentHandler` (gałąź INCOME) — faktury, paragony i „inne” jednym handlerem; `KsefRevenueController.issueInvoice` dla faktur wystawianych **ręcznie** z modułu KSeF (jedyna ścieżka bez dokumentu finansowego) |
| `EXPENSE_RECORDED` 💰 | — | `CreateFinancialDocumentHandler` (gałąź EXPENSE) |
| `CASH_OPERATION` 💰 | `operationType` = `PAYMENT_IN` \| `PAYMENT_OUT` \| `MANUAL_ADJUSTMENT` | `AdjustCashBalanceHandler` (ręczne), `CreateFinancialDocumentHandler.recordCashMovement` (automatyczne przy gotówce) |
| `STATS_CATEGORY_CREATED` | `kind` = `SERVICE` \| `COST` | `CreateCategoryHandler` (przychodowe), `CostCategoryController.createCategory` (kosztowe) |
| `INSTAGRAM_CONTENT_RATED` | `target` = `COMPETITOR` \| `AI` | `ReactToInstagramPostHandler` (usunięcie oceny **nie** liczy się), `InstagramGeneratedPostService.rate` |
| `INSTAGRAM_AD_DETAILS_VIEWED` | — | `MetaAdsController.detail` — jedyne zdarzenie czysto **odczytowe**, publikowane w kontrolerze przy niepustym wyniku |
| `WORKTIME_ENTRY_SAVED` | — | `WorkTimeService.upsertEntry` i `.fillMonth` — jedno zdarzenie na **wpis**, nie na gest, żeby 20 dni grafiku liczyło się tak samo niezależnie od przycisku (wpisy z `fillMonth` mają to w atrybutach) |
| `APPOINTMENT_COLOR_CREATED` | — | `CreateAppointmentColorHandler` |
| `SMS_TEMPLATE_UPDATED` | — | `UpdateAutomationConfigHandler` |
| `ROLE_CREATED` | — | `CreateRoleHandler` |
| `TABLET_PAIRED` | — | `TabletSessionService.pairTablet` — studio z payloadu kodu parowania (żądanie idzie z niezalogowanego tabletu) |
| `UPSELL_USED` | `stage` = `REQUESTED` \| `CONFIRMED` | `RequestUpsellServicesHandler` (klient wybrał na Karcie Wizyty), `UpsellConsentConfirmedListener` (potwierdził SMS-em). Jedna usługa daje oba — to dwa kroki lejka, nie duplikat |
| `VISIT_PRICE_EDITED` | — | `SaveVisitServicesHandler`, tylko gdy `payload.updated` niepuste (dodanie/usunięcie pozycji to co innego) |
| `PRICE_CONFIRMATION_REQUESTED` | — | `SmsConsentService.sendConsentRequest` — wąskie gardło obu ścieżek (edycja usług i upsell). `sendServiceChangeNotification` to czyste powiadomienie i się nie liczy |
| `VISIT_COMMENT_ADDED` | `commentType` = `INTERNAL` \| `FOR_CUSTOMER` | `AddCommentToVisitHandler` |
| `PROTOCOL_SIGNED` | `stage` = `CHECK_IN` \| `CHECK_OUT` | `SubmitSignatureHandler` (tablet / link SMS), `SignVisitProtocolHandler` (podpis w CRM). Protokoły zgód (`consentDefinitionId != null`) nie mają etapu i się nie liczą |
| `DAMAGE_MAP_REFILLED` | — | `UpdateVisitDamageMapHandler`, tylko gdy `revision > 1`. Pierwsze wypełnienie sieje check-in (`seedDamageMap`, rewizja 1) i nie jest „ponownym” |

💰 = typ `monetary`: niesie `amountCents` sumowane osobno w `lm:{scope}:sum`.

Wymiary są zamkniętymi zbiorami (`BusinessEventType.dimensions`) — konstruktor
`BusinessEvent` odrzuca inne wartości. Wszystko o nieograniczonej kardynalności
(id encji, nazwy) idzie w `attributes` wyłącznie do strumienia, nigdy do kluczy.

### Kwoty (`amountCents`)

Pole niesie **dokładne brutto zapisane na dokumencie**, w groszach, i nigdy nie jest
przeliczane z netta — przejście brutto → netto → brutto nie jest tożsamością na siatce
groszowej, więc przeliczona kwota rozjechałaby się z tym, co klient widzi na fakturze
(patrz `CLAUDE.md` §1 i `VatRate.resolveGrossAmount`). Kwota musi być **nieujemna**, bo
suma w Prometheusie ma rosnąć monotonicznie: kierunek operacji kasowej idzie do wymiaru
(`PAYMENT_IN` / `PAYMENT_OUT`), nie do znaku. Typ bez flagi `monetary` z niezerową kwotą
jest odrzucany przez konstruktor.

**Znana granica:** faktury korygujące (`IssueCorrectionHandler`) nie są liczone, więc
korekta zmniejszająca nie odejmie przychodu z metryki. Do rozliczeń źródłem prawdy
pozostaje baza — te liczby służą do oceny zaangażowania, nie do księgowości.

### Kafle „dziś” a reszta (`daily`)

Gauge `crm_business_events_today` jest eksportowany **wyłącznie** dla typów z `daily = true`
(rezerwacje, wizyty, leady, wiadomości, zdjęcia, aktywność). Koszt tej metryki to jeden HGET
na tenanta i typ **co 15 sekund**: przy komplecie typów i kilkuset studiach to dziesiątki
tysięcy poleceń na cykl za odpowiedź „dziś zero”, która dla zdarzeń rzadkich (parowanie
tabletu, dodanie roli) nie niesie żadnej informacji. Na pytanie „ile łącznie” odpowiada suma
od początku — jeden HGETALL na tenanta co 5 minut, niezależnie od liczby typów.

## Stany i wielkości bieżące (`TenantStateMetricsExporter`)

Druga noga eksportu: kilkanaście zapytań `GROUP BY studio_id` co 5 minut, każde zwracające
komplet tenantów naraz (koszt nie rośnie z liczbą studiów). Uniwersum tenantów to tabela
`studios`, nie `lm:tenants` — inaczej z dashboardu adopcji wypadliby dokładnie ci, o których
się pyta: studia, które nic jeszcze nie zrobiły.

| Metryka | Etykiety | Wartości |
|---|---|---|
| `crm_tenant_state` | `tenant_id, tenant, state` | `mailbox_configured`, `ksef_configured`, `ksef_token_valid`, `ksef_can_issue`, `instagram_self_profile`, `tablet_paired`, `sms_sender_name_set`, `sms_sender_confirmed`, `signature_configured`, `idle_lock_enabled` → 0/1 |
| `crm_tenant_inventory` | `tenant_id, tenant, kind` | `services`, `service_packages`, `employees`, `roles`, `appointment_colors`, `instagram_profiles`, `sms_templates_enabled`, `sms_credits`, `service_categories`, `cost_categories`, `services_unassigned`, `cost_items_assigned`, `cost_items_unassigned`, `ksef_cost_grosze` |
| `crm_tenant_state_refreshed_seconds` | — | epoch ostatniego **udanego** cyklu |

Trzy poziomy KSeF zamiast jednej skali liczbowej (`configured` → `token_valid` →
`can_issue`, gdzie ostatni to `verified_permissions` zawierające `InvoiceWrite`) — dokładnie
rozróżnienie z `KsefController.getInvoicingStatus()`.

`services_unassigned` to zbiorcza wersja `StatsRepository.findUnassignedServiceIds`, łącznie
z rekurencją po `replaces_service_id` i pomijaniem nieaktywnych kategorii. Liczby **muszą**
zgadzać się z modułem Statystyki — dashboard pokazujący inną liczbę niż aplikacja jest gorszy
niż brak dashboardu.

**Dlaczego jest kafel z wiekiem odświeżenia:** nieudany cykl ZOSTAWIA poprzednie wartości
(`MultiGauge` ich nie zeruje), więc martwy eksporter wygląda jak zamrożona prawda.
`crm_tenant_state_refreshed_seconds` rośnie tylko po udanym cyklu; pilnuje go alert
`TenantStateGaugesStale`.

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

| Dashboard | UID | Odpowiada na pytanie |
|---|---|---|
| Live metrics — platforma | `crm-live-platform` | **„ile dzieje się TERAZ”** — tempo zdarzeń (`increase`), rozkład godzinowy rezerwacji, tabela tenantów „dziś”, stan potoku |
| Live metrics — tenant | `crm-live-tenant` | to samo dla jednego studia (zmienna `$tenant_id`) |
| Zaangażowanie — tenant | `crm-engagement-tenant` | **„ile łącznie od wdrożenia”** — wiersz per moduł: liczniki narastające, stany funkcji, sumy kwot |
| Adopcja — platforma | `crm-adoption-platform` | **„kto z czego korzysta”** — odsetek studiów per funkcja, tabele tenant × funkcja i tenant × zdarzenia, ranking zaangażowania |

Rozdział na „tempo” i „kumulację” jest sednem tych dashboardów. `increase()` rysuje
impulsy — zdarzenie daje pik i powrót do zera — i nie odpowiada na pytanie „ile tego
w ogóle jest”. Odwrotnie: licznik narastający nie pokazuje, czy coś dzieje się teraz.
Mieszanie obu w jednym panelu dawało wykresy wyglądające jak „krótkotrwałe piki”.

Odświeżanie: 10 s na dashboardach tempa, 1 min na kumulatywnych (ich źródła i tak
odświeżają się co 5 minut). Wiersze modułów poza pierwszym są **zwinięte** — trzynaście
rozwiniętych to kilkadziesiąt zapytań na odświeżenie, z czego widać jeden ekran.

| Metryka | Etykiety | Źródło | Agregacja w Grafanie |
|---|---|---|---|
| `crm_business_events_total` | `tenant_id, tenant, type, dimension` | licznik ingestu tej instancji | `sum(increase(...[$__rate_interval]))` |
| `crm_business_events_today` | `tenant_id, tenant, type` | Redis, co 15 s; **tylko typy `daily`** | `max by (tenant_id)` (ta sama wartość na każdej instancji) |
| `crm_business_events_all_time` | `tenant_id, tenant, type` | Redis (`lm:{scope}:total`, bez TTL), co 5 min | `max by (tenant_id)` |
| `crm_business_events_all_time_dim` | `tenant_id, tenant, type, dimension` | j.w., pod-serie | `max by (tenant_id, dimension)` |
| `crm_business_events_sum_all_time` | `tenant_id, tenant, type` | Redis (`lm:{scope}:sum`), co 5 min, **grosze** | `max by (tenant_id)`, dzielone przez 100 |
| `crm_business_events_hour_of_day` | `tenant_id, tenant, type, hour` | Redis (7 dni), co 5 min; per tenant tylko `RESERVATION_CREATED`, `tenant_id="_platform"` dla wszystkich typów | `max by (hour, tenant_id)` |
| `crm_tenant_state` | `tenant_id, tenant, state` | **baza**, co 5 min | `max by (tenant_id)`; udział = `sum(...) / count(...)` |
| `crm_tenant_inventory` | `tenant_id, tenant, kind` | **baza**, co 5 min | `max by (tenant_id)` |
| `crm_live_metrics_pipeline_*`, `crm_live_metrics_sse_subscribers`, `crm_tenant_state_refreshed_seconds` | — | stan potoku / eksportera | `sum` / `max` |

**`max by (tenant_id)`, nigdy `sum`** — gauge'e niosą wartość odczytaną z Redisa lub z bazy,
więc każda instancja backendu eksportuje **tę samą** liczbę. `sum` pomnożyłby ją przez liczbę
instancji, a przy rolling deployu dałby chwilowy skok ×2 nie do odróżnienia od prawdziwego
ruchu. Zewnętrzne `sum` dodaje tenantów, nie instancje.

**`all_time` i `all_time_dim` to rozłączne metryki.** Pod-seria (`VISIT_CREATED:DIRECT`) jest
inkrementowana razem z bazową, więc w jednej metryce liczyłaby każde zdarzenie z wymiarem
dwa razy. Nigdy nie sumuj ich ze sobą.

Kardynalność: ~70 serii licznika na tenanta, tyle samo gauge'y „od początku”, ~30 z rozbiciem
na wymiar, 3 sumy kwot, 10 stanów, 14 wielkości, kilka kafli „dziś” i 24 kubełki godzinowe
(tylko rezerwacje). Żadnych id encji w etykietach — te idą wyłącznie do strumienia Redis
(`attributes`) dla SPA.

### Pułapki, na które te dashboardy są odporne

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

**Wykres zbiorczy jest narastający, nie „na minutę".** Tempo zdarzeń mówi, ile dzieje się
teraz — i mówią to już panele wyżej, każdy w rozbiciu na wymiar. Panel zbiorczy odpowiada
na inne pytanie: „ile tego w ogóle jest do tego momentu". `rate()` go nie dotykał, bo wracał
do zera po każdej ciszy i wyglądał tak samo przy pierwszym leadzie studia, co przy
dziesięciotysięcznym. Dlatego czyta z `crm_business_events_all_time`
(`sum by (type) (max by (tenant_id, type) (...))`) — sumy z Redisa bez TTL, odpornej na
restarty. Cena: odświeżanie co 5 minut, więc linia jest schodkowa.

**Stan czyta się z „od początku", nie z „dziś".** Część pytań dotyczy faktu, który zdarzył się
raz i dawno: „kto ma skonfigurowaną pocztę", „ile profili IG obserwuje". Kafel dzienny odpowiada
na nie zerem u każdego, kogo pytanie dotyczy — bo dziś akurat nic nie zrobił — i jest nie do
odróżnienia od studia, które nie ma niczego. Dlatego `MAILBOX_CONNECTED`, `INSTAGRAM_PROFILE_ADDED`,
`EMPLOYEE_CREATED`, `CAMPAIGN_CREATED` i `VISIT_CARD_SENT` nie mają kafla „dziś" ani kolumny w tabeli
tenantów; są w wierszu „Od początku", który czyta sumę z Redisa bez TTL. Uwaga na granicę: suma
liczy zdarzenia **od wdrożenia tej metryki**, a nie stan bazy — poczta podłączona wcześniej nie
zostanie policzona, dopóki ktoś nie podłączy jej ponownie.

**Liczniki rejestrowane z zerem, zanim padnie pierwsze zdarzenie.** `increase()` liczy przyrost
między dwiema próbkami, więc seria, która pojawia się w Prometheusie od razu z wartością `1`,
jest dla niego niewidzialna — nie ma czego odjąć od pierwszej próbki. Licznik tworzony leniwie
(przy pierwszym zdarzeniu) gubił więc pierwsze zdarzenie każdej kombinacji tenant × typ × wymiar,
i to od nowa po każdym restarcie instancji, bo `crm_business_events_total` żyje w jej pamięci.
Kafle KPI działały przy tym normalnie, bo czytają gauge z Redisa — rozjazd „licznik pokazuje 1,
wykres pusty" jest sygnaturą właśnie tego błędu. `primeCounters` rejestruje więc komplet liczników
tenanta z zerem przy odświeżaniu gauge'y „dziś" (co 15 s). Kardynalność bez zmian: to te same
serie, które i tak by powstały (10 na tenanta).

**Filtrowanie po `tenant_id`, nigdy po nazwie studia.** Grafana escapuje wartość zmiennej
wstawianą do zapytania Prometheusa, więc apostrof w `Maciej Sienkiewicz's Detailing Studio`
trafiał do matchera jako `\'` i `tenant="$tenant"` nie pasowało do niczego. Mylące było to,
że tytuł wiersza wyglądał poprawnie — tam interpolacja jest zwykłym tekstem, nie zapytaniem.
Wybierak na dashboardzie tenanta operuje więc na `tenant_id` (UUID, nic do escapowania,
odporne na zmianę nazwy), a nazwa studia jest doklejana do tytułów przez ukrytą zmienną
`tenant_name` wyprowadzoną z wybranego identyfikatora.

### Regeneracja dashboardów

JSON dashboardów jest artefaktem. Pliki dzielą ten sam zestaw paneli, więc ręczna edycja
jednego rozjeżdża je względem siebie i gubi reguły wymuszone wyżej. Po każdej zmianie:

```bash
python3 deploy/monitoring/grafana/generate_dashboards.py
```

Źródłem prawdy jest **katalog `MODULES`** w generatorze: mapa moduł → zdarzenia, wymiary,
kwoty, stany i wielkości. Dodanie metryki sprowadza się tam do jednej linijki — panele,
kolory, tabele i legendy wyprowadzają się same.

Spójność katalogu z kodem pilnuje `DashboardCatalogTest`, bo rozjazd jest tu cichy w obie
strony: typ bez panelu to metryka, której nikt nigdy nie zobaczy, a panel pytający o typ,
którego już nie ma, pokazuje `—` — czyli wygląda dokładnie jak awaria scrape'u. Kompilator
nie wyłapie żadnego z tych przypadków, bo po drugiej stronie jest JSON. Test sprawdza też,
że kafle „dziś” dotyczą wyłącznie typów `daily`, panele kwotowe wyłącznie typów `monetary`,
a pod-serie — zadeklarowanych wartości wymiaru.

Wdrożenie na serwer jest ręczne — Jenkins buduje wyłącznie obraz backendu i nie dotyka
`deploy/monitoring`. Grafana montuje provisioning z katalogu na hoście
(`/opt/apps/prod/app-backend/monitoring/grafana/provisioning`), a jej własna baza
(`app-backend_grafana_data`) przeżywa restarty i potrafi serwować STARĄ wersję dashboardu mimo
nowego pliku na dysku. Po aktualizacji plików zweryfikuj, co Grafana naprawdę oddaje, i w razie
rozjazdu zrestartuj kontener:

```bash
curl -s http://localhost:3000/api/dashboards/uid/crm-live-tenant | grep -c tenant_id
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
