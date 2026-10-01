# Audyt modułu Pakiety (abonamenty) i Uprawnienia — październik 2026

Zakres: cykl życia subskrypcji i planów (`subscription/**`), uprawnienia (`subscription/entitlement/**`),
płatności Przelewy24 (`payments/**`), schedulery wygaszania i downgrade'ów, mapowanie JPA i schemat tabel
rozliczeniowych. Ścieżki w tabelach są względne wobec `src/main/kotlin/pl/detailing/crm/`.

Metoda: przegląd kodu, a każdy zarzut, który da się sprawdzić na bazie, **odtworzony na prawdziwym
PostgreSQL 16** (nie na mockach i nie w transakcji testowej). Odtworzenia są w repozytorium jako
`src/test/kotlin/pl/detailing/crm/subscription/SubscriptionKnownDefectsTest.kt` — każdy test opisuje
POPRAWNE zachowanie i dziś pada dokładnie z powodu opisanego niżej (`@Disabled` z identyfikatorem
defektu; PR naprawczy zdejmuje adnotację). Kolumna „Dowód" mówi, czy zarzut jest odtworzony (**R**),
czy wynika z analizy kodu (**K**).

Legenda ważności: **Critical** — klient płaci i nie dostaje usługi albo dostaje ją podwójnie/za darmo,
w zwykłym przebiegu (bez wyścigu) i bez żadnego sygnału; **High** — to samo, ale tylko przy wyścigu
lub w scenariuszu brzegowym, albo awaria, która zatrzymuje proces dla wszystkich tenantów; **Medium** —
niespójność danych lub stanu bez bezpośredniej straty; **Low** — hardening, kosmetyka rozliczeń.

Reguła brutto z `CLAUDE.md` §1: moduł operuje wyłącznie na cenach brutto z cennika
(`monthly_price_gross_cents`) i nigdzie nie przelicza VAT — naruszeń nie ma.

---

## Najważniejsze w dziesięciu zdaniach

1. **Constraintów nie usuwamy.** Każdy `DataIntegrityViolationException`, który odtworzyłem w tym module,
   jest objawem błędu w kodzie: wyścigu „sprawdź, potem zapisz" bez blokady (D1). Constraint jest jedynym,
   co w tym wyścigu zatrzymuje drugą realizację tej samej usługi; bez niego błąd znika z logów, a podwójna
   płatność zostaje (P7).
2. Zduplikowana notyfikacja P24, która przyjdzie, zanim pierwsza się skończy, **przedłuża abonament
   dwa razy** (+60 dni zamiast +30, dwa wpisy w historii) — P1, odtworzone.
3. `PlanDowngradeScheduler` ma `try/catch` wewnątrz jednej transakcji: awaria jednego studia oznacza
   transakcję jako rollback-only i **cofa downgrade'y wszystkich studiów, co godzinę od nowa** — J1.
4. `SubscriptionLifecycleScheduler` na pierwszym błędzie przerywa pętlę, a wygaszanie płatnych
   subskrypcji w tym przebiegu nie rusza w ogóle — J2. `@Transactional` na funkcji `suspend` nie daje
   tam żadnej transakcji.
5. Odnowienie zrobione po zaplanowaniu downgrade'u **pobiera cenę FULL, a dostarcza BASIC** — S1.
6. Anulowanie downgrade'u w trakcie przebiegu schedulera odpowiada „anulowano", a studio i tak traci
   FULL (utracona aktualizacja, brak `@Version`) — S2.
7. Studio z wygasłą subskrypcją dostaje upgrade do FULL **za 0 zł** (S4), a uprawnienia w ogóle nie
   zależą od statusu rozliczeniowego: automatyzacje SMS, kampanie i synchronizacja Instagrama działają
   dalej po wygaśnięciu (S3). Grace period nie istnieje; `PAST_DUE` daje dostęp bez końca (S5).
8. Webhook woła `verify` w P24 **przed** sprawdzeniem stanu zamówienia, a brak poświadczeń P24
   w konfiguracji włącza tryb, w którym każde zamówienie jest realizowane za darmo (P2, P3).
9. Schemat tabel rozliczeniowych nie jest w migracjach (sprzed Flyway; produkcja ma `ddl-auto=validate`,
   które nie sprawdza constraintów). Według mapowania encji nie ma kluczy obcych do `studios` ani unikatu
   „jeden PENDING downgrade na studio", choć komentarz w encji twierdzi, że jest — D7. Stan produkcji
   potwierdza zapytanie Q1 z części 4.
10. „Płatności odnawialne" w kodzie nie istnieją: przedłużenie to ręczna płatność jednorazowa
    właściciela (P9).

---

## CZĘŚĆ 1 — Raport z audytu

### 1.1 Krok 1: cykl życia subskrypcji (upgrade / downgrade)

#### Jak to dziś działa

Stan subskrypcji żyje w **dwóch niezależnych miejscach**, bez wspólnej maszyny stanów:

| Nośnik | Tabela | Kto zmienia | Co znaczy |
|---|---|---|---|
| Status rozliczeniowy + daty | `studios.subscription_status`, `trial_ends_at`, `subscription_ends_at` | `SubscriptionService`, `OrderFulfillmentService`, `SubscriptionLifecycleScheduler` | czy studio w ogóle wchodzi do API (`SubscriptionInterceptor`) |
| Plan funkcjonalny + moduły | `studio_subscription_plans`, `studio_subscription_add_ons` | `EntitlementService.assignPlan/activateAddOn/deactivateAddOn` | co studio może (`@RequiresFeature`, `@RequiresCapability`, `OutboundCommunicationGateway`) |
| Zaplanowana zmiana | `pending_plan_changes` | `PlanManagementService`, `PlanDowngradeScheduler`, `OrderFulfillmentService` | downgrade na koniec okresu |
| Pieniądze | `payment_orders`, `subscription_payment_log` | `CheckoutService`, webhook P24 | zamówienie i historia |

Przejścia statusu rozliczeniowego, jakie realnie wykonuje kod:

```
NO_PLAN ──start-trial──▶ TRIALING ──trial_ends_at minął (scheduler)──▶ EXPIRED
   │                        │                                            │
   └──────INITIAL_PURCHASE──┴──────────────▶ ACTIVE ◀──RENEWAL / INITIAL─┘
                                              │  ▲
                                              │  └── RENEWAL (+30 dni od max(now, ends_at))
                                              └── subscription_ends_at minął (scheduler) ──▶ EXPIRED

PAST_DUE — zdefiniowany, nigdzie nie ustawiany, a Studio.isAccessible() zwraca dla niego true bez warunku.
```

Dostęp do API **nie** zależy od zapisanego statusu, tylko jest liczony z dat przy każdym żądaniu
(`Studio.isAccessible`). Zapisany status dogania rzeczywistość raz na godzinę — albo wcale, gdy scheduler
stanie (J2). Plan funkcjonalny nie reaguje na status w ogóle: studio EXPIRED nadal „ma" FULL.

#### Odpowiedź na pytanie: downgrade, gdy wyższy pakiet jest opłacony do końca miesiąca

**Ścieżka szczęśliwa jest poprawna.** `POST /subscription/change-plan` (FULL → BASIC) zapisuje
`pending_plan_changes` z `effective_at = subscription_ends_at`; do tej chwili uprawnienia zostają bez
zmian, a `PlanDowngradeScheduler` (co godzinę) przepina plan najpóźniej godzinę po końcu okresu.
Upgrade opłacony w międzyczasie anuluje zaplanowany downgrade (`fulfillPlanUpgrade`).

Luki w tej maszynie stanów:

| # | Ważność | Miejsce | Luka | Dowód |
|---|---|---|---|---|
| S1 | **Critical** | `payments/checkout/CheckoutService.prepareRenewal` (l. 199–216), `subscription/management/PlanDowngradeScheduler` | Odnowienie po zaplanowanym downgradzie liczy cenę z **bieżącego** planu (FULL, 299 zł). `effective_at` downgrade'u zostaje na starym końcu okresu, więc BASIC wchodzi na samym początku właśnie opłaconego okresu FULL. Ten sam błąd pokazuje `GET /subscription/my-plan` → `nextRenewalCostCents`. | **R** — pobrano 29900, plan po końcu okresu: BASIC |
| S2 | **High** | `subscription/management/PendingPlanChangeRepository.cancelPendingForStudio` (l. 25–31), `PlanDowngradeScheduler.applyDowngrade` | Scheduler wczytuje wiersz PENDING, właściciel w tym czasie anuluje (API: 204), scheduler zapisuje `APPLIED` po `id` — bez wersji i bez warunku na status — i przepina na BASIC. Ten sam wzorzec: opłacony upgrade (który anuluje downgrade) przegrywa z równolegle stosowanym downgrade'em. | **R** — `cancel=true`, plan końcowy BASIC, status `APPLIED` |
| S3 | **High** | `subscription/entitlement/capability/CapabilityService`, `communication/OutboundCommunicationGateway` (l. 162), `instagram/sync/InstagramSyncOrchestrator` (l. 62, 87), `config/WebMvcConfig` | Uprawnienia = plan + moduły, **niezależnie od statusu rozliczeniowego**. Blokuje tylko interceptor HTTP. Zadania w tle (automatyzacje SMS, przypomnienia, kampanie — wszystkie przez `OutboundCommunicationGateway`, który sprawdza wyłącznie capability) działają dla studiów EXPIRED dalej; synchronizacja Instagrama nie patrzy ani na plan, ani na status. Ścieżki wyłączone z interceptora (`/api/mobile/**`, `/api/tablet/**`, `/api/v1/carddav/**`) też nie widzą wygaśnięcia. Poza samym modułem subskrypcji status rozliczeniowy sprawdza w tle tylko raport właściciela (`OwnerReportNotifications`) — pozostałe z 39 klas z `@Scheduled` go nie znają. | **K** |
| S4 | **Medium** | `payments/checkout/CheckoutService.preparePlanUpgrade` (l. 219–245), `subscription/pricing/ProrationService.calculatePlanUpgrade` (l. 90) | `PLAN_UPGRADE` nie sprawdza statusu rozliczeniowego. Dla okresu, który się skończył (EXPIRED, NO_PLAN, albo ACTIVE w godzinie przed przebiegiem schedulera) proracja zwraca `null` → kwota 0 → zamówienie realizowane od ręki → FULL za darmo. Sama w sobie strata jest niewielka (blokuje interceptor, a odnowienie policzy już FULL), ale w połączeniu z S3 płatne moduły działają w tle bez opłaty. Podgląd (`previewPlanChange`) mówi wtedy „jesteś na trialu". | **R** — EXPIRED, `amountCents=0`, plan FULL |
| S5 | **Medium** | `studio/domain/Studio.isAccessible` (l. 37–42) | `PAST_DUE -> true` bez sprawdzenia żadnej daty. Dziś stan martwy, ale pierwszy, kto go ustawi (ręcznie albo przy wdrażaniu grace period), da studiu dostęp bezterminowy. Grace period nie istnieje: dostęp kończy się co do sekundy `subscription_ends_at`. | **K** |
| S6 | **Medium** | `subscription/entitlement/EntitlementsController.deactivateAddOn` (l. 379) | Dezaktywacja modułu działa natychmiast i bez zwrotu — odwrotnie niż downgrade planu. Moduł opłacony do końca okresu znika w chwili kliknięcia. | **K** |
| S7 | **Medium** | `CheckoutService.preparePlanUpgrade`, `ProrationService.calculatePlanUpgrade` | Upgrade BASIC + moduły → FULL liczy różnicę samych planów (299 − 99), ignorując opłacony już do końca okresu koszt modułów, a `assignPlan` moduły kasuje. Klient z BASIC + Finanse + Statystyki nadpłaca 68 zł/mies. proporcjonalnie. | **K** |
| S8 | **Medium** | całość | Dwa źródła prawdy (status+daty vs plan) zmieniane w różnych transakcjach i przez różne komponenty; status zapisany ≠ status liczony; brak jednego miejsca, które definiuje dozwolone przejścia. Granica czasu jest niespójna: `isSubscriptionActive` blokuje przy `ends_at == now`, a `findExpiredSubscriptions` (`< :now`) jeszcze nie wygasza. | **K** |
| S9 | **Low** | `ProrationService` (l. 62, 95), `OrderFulfillmentService.fulfillInitialPurchase` | Stawka dzienna zaokrąglana przed mnożeniem: (29900 − 9900)/30 = 666,67 → 667 gr, za 30 dni 20010 gr zamiast 20000. Zakup w trakcie triala przepala pozostałe dni triala (decyzja produktowa do potwierdzenia). | **K** |

### 1.2 Krok 2: constrainty bazy i JPA

#### Werdykt

Sugestia „usuńmy constrainty, bo powodują błędy przy zapisie" myli objaw z przyczyną. Odtworzyłem dwa
warianty tego samego scenariusza — dwa zamówienia na ten sam moduł, oba opłacone:

* **równolegle**: druga realizacja trafia na unikat i kończy się
  `DataIntegrityViolationException: duplicate key value violates unique constraint "uq_studio_add_ons"`,
  zamówienie zostaje PENDING, P24 ponawia. To jest błąd, który widzą deweloperzy.
* **jedno po drugim**: oba kończą jako `PAID`, moduł jest jeden, druga płatność przepada bez śladu
  i bez alarmu (P7).

Bez constraintu wariant równoległy kończy się jak sekwencyjny, tylko gorzej: dwa wiersze tego samego
modułu i druga płatność bez efektu. Błąd znika z logów, strata zostaje.

Uwaga historyczna: zespół raz już poszedł tą drogą. Migracje V2, V21, V35, V36 i V100 łatały
CHECK-i enumów w `subscription_payment_log` i `payment_orders` — i to były dokładnie „błędy constraintów
przy upgrade'zie i przedłużeniu" (`plan_key='FULL'`, `event_type='SUBSCRIPTION_RENEWAL'`). V101 zdjęła
całą tę klasę ograniczeń. Przyczyną nie były constrainty, tylko to, że schematem zarządzał
`ddl-auto=update`, a nie migracje. Tego precedensu nie rozszerzamy na unikaty, klucze obce i NOT NULL —
w tej części raportu wszystkie propozycje **dodają** ograniczenia.

#### Skąd biorą się `DataIntegrityViolationException` przy zmianach planu

| # | Ważność | Miejsce | Przyczyna | Dowód |
|---|---|---|---|---|
| D1 | **High** | `subscription/entitlement/EntitlementService.activateAddOn` (l. 136–151), `.ensurePlanAssigned` (l. 93–103), `.assignPlan` gałąź „brak wiersza" (l. 125) | **Check-then-act w pamięci, bez blokady.** „Czy moduł już jest?" sprawdzane na kolekcji z kontekstu persystencji; „czy wiersz planu istnieje?" sprawdzane SELECT-em. Dwie transakcje (duplikat webhooka, podwójne kliknięcie, trial + zakup) obie widzą „nie ma" i obie wstawiają. Rozstrzyga unikat — poprawnie. | **R** — `uq_studio_add_ons`; 4 równoległe `ensurePlanAssigned` → 3× `uq_studio_subscription_plans_studio` |
| D2 | **High** | wszystkie encje rozliczeniowe (`StudioEntity`, `StudioSubscriptionPlanEntity`, `PendingPlanChangeEntity`, `PaymentOrderEntity`) | **Brak `@Version` i brak blokad pesymistycznych.** Zapis po `id` nadpisuje cudzy commit: utracone aktualizacje S2 i P1. Tu constraint nic nie łapie — dlatego te błędy są groźniejsze od D1. | **R** (S2, P1) |
| D3 | **Medium** | `EntitlementService.assignPlan` (l. 109–132), `entitlement/infrastructure/StudioSubscriptionPlanEntity` | Wszystkie pola encji to `val`, więc zmiana planu buduje **nową instancję z tym samym `id`** i woła `save()` → `merge()` kopiuje stan na zarządzaną instancję, a `orphanRemoval` usuwa moduły z kolekcji wyczyszczonej chwilę wcześniej. Działa sekwencyjnie (test-strażnik `GUARD…` jest zielony), ale wyłącznie dzięki szczegółom implementacji `merge` i kolejności akcji przy flushu. Każdy refaktor na `persist`, odłączenie kontekstu albo podmiana kolekcji kończy się `EntityExistsException` / „collection with cascade=all-delete-orphan was no longer referenced". | **R** (działa) + **K** (kruchość) |
| D4 | **Medium** | wszystkie encje z `@Id val id: UUID = UUID.randomUUID()` | Przypisany identyfikator bez `@Version`/`Persistable` → Spring Data uznaje KAŻDĄ encję za istniejącą → `save()` nowego obiektu to `merge()` = SELECT + INSERT (czyli check-then-act na poziomie ORM) i zwrócenie **innej** instancji niż przekazana. Dzieci przez kaskadę MERGE — to samo, po jednym SELECT-cie na wiersz. | **K** |
| D5 | **Medium** | `StudioSubscriptionPlanEntity.activeAddOns` (`EAGER`), `findByStudioIdWithAddOns` (`JOIN FETCH`) | Kolekcja zainicjalizowana raz w transakcji **nie jest odświeżana** przez kolejne zapytanie z `JOIN FETCH`. Transakcja, która wcześniej (np. w `ensurePlanAssigned`) wczytała plan, sprawdza „czy moduł jest" na stanie sprzed cudzego commitu → D1. | **R** (mechanizm testu D1) |
| D6 | **Medium** | `PendingPlanChangeRepository.cancelPendingForStudio` (`@Modifying` bez `flushAutomatically/clearAutomatically`) | Masowy UPDATE JPQL omija kontekst persystencji. Zarządzana instancja wczytana wcześniej w tej samej transakcji zostaje w pamięci jako PENDING i przy flushu może nadpisać CANCELLED. Dziś nie trafia w żadną ścieżkę jednej transakcji — wystarczy jedna zmiana kolejności wywołań. | **K** |
| D7 | **High** | tabele `studio_subscription_plans`, `studio_subscription_add_ons`, `pending_plan_changes`, `payment_orders`, `subscription_payment_log` | **Schemat poza migracjami.** Tabele powstały przed Flyway (`V1__baseline.sql` jest pusty, lista `PRE_FLYWAY_TABLES` w `EntityTableHasMigrationTest`), a produkcja ma `ddl-auto=validate`, które nie sprawdza constraintów — nie wiadomo z repozytorium, które ograniczenia realnie są na produkcji (zapytanie Q1 w części 4). Z mapowania wynika, że **nie ma**: kluczy obcych `studio_id → studios` (kolumny to gołe UUID), unikatu „jeden PENDING downgrade na studio" (KDoc `PendingPlanChangeEntity` twierdzi, że jest), unikatu `payment_orders.p24_order_id`, unikatu w księdze `subscription_payment_log` (podwójny wpis z P1 przechodzi). Unikat `studio_subscription_plans.studio_id` gwarantuje dopiero `StudioSubscriptionBackfill`, który zakłada indeks przy starcie, bo starsze bazy z `ddl-auto` mogły go nie mieć. | **R** (DDL wygenerowany z encji) + **K** |
| D8 | **Low** | `demo/DemoAccountService` (l. 67), `demo/DemoCleanupJob` (l. 245) | Konto demo powstaje z pominięciem `StudioProvisioningService` („the ONE place a studio comes into existence") — bez wiersza planu, więc zapala alarm `studios.missing.plan.row` i serwuje „degraded entitlements" z logiem ERROR, dopóki backfill przy starcie nie dopisze BASIC. `DemoCleanupJob` kasuje `studios`, ale nie wiersze subskrypcji — powstają **sieroty**, których przy braku FK nic nie wychwyci. | **K** |

### 1.3 Krok 3: niezawodność Przelewy24

#### Odpowiedzi na pytania

**Co, jeśli P24 wyśle ten sam status dwa razy?**

* **Jedna po drugiej** (druga przychodzi po commicie pierwszej): abonament przedłużony raz — `completeOrder`
  widzi `PAID` i kończy. Ale wcześniej webhook już **drugi raz zawołał `verify` w P24**, bo sprawdza stan
  zamówienia dopiero po weryfikacji (P2). Jeśli P24 odrzuca ponowną weryfikację, każdy duplikat dostaje
  500 i P24 ponawia w kółko.
* **Równolegle** (ponowienie przychodzi, zanim pierwsza obsługa skończyła — a obsługa zawiera wywołanie
  HTTP do P24 bez timeoutu, więc okno jest realne): **tak, przedłużamy dwa razy.** Obie transakcje czytają
  zamówienie jako PENDING; druga czyta studio już po commicie pierwszej i dolicza kolejne 30 dni od nowej
  daty. UPDATE zamówienia nie ma wersji, więc nic go nie zatrzymuje. Odtworzone: +60 dni i dwa wpisy
  `SUBSCRIPTION_RENEWAL` za jedną płatność. Przy zakupie z modułami drugą realizację zatrzyma dopiero
  unikat `uq_studio_add_ons` (D1) — znów constraint ratuje sytuację, którą powinien był rozstrzygnąć kod.

**Co, jeśli webhook przyjdzie, zanim baza zarejestruje intencję płatności?**

Dziś w normalnym przebiegu to niemożliwe, ale **przypadkiem**: `CheckoutService.checkout` rejestruje
transakcję w P24 wewnątrz transakcji bazy, a kupujący dostaje adres płatności dopiero po commicie. Cena
tego „zabezpieczenia" to połączenie z puli trzymane przez czas wywołania HTTP (bez timeoutu — P4) i
osierocona transakcja w P24, gdy commit się nie uda. Gdy notyfikacja jednak nie znajdzie sesji (inne
środowisko na tym samym koncie, ręczne ponowienie z panelu, przyszły refaktor), webhook odpowiada 400
i **nic po nas nie zostaje** — liczymy wyłącznie na harmonogram ponowień P24.

| # | Ważność | Miejsce | Luka | Dowód |
|---|---|---|---|---|
| P1 | **High** | `payments/checkout/CheckoutService.completeOrder` (l. 130–148) | Idempotencja to `if (status == PAID) return` na odczycie bez blokady i bez wersji. Równoległy duplikat realizuje zamówienie dwa razy. | **R** — +60 dni, 2 wpisy w logu |
| P2 | **High** | `payments/Przelewy24WebhookController` (l. 43–65) | `verifyTransaction` przed sprawdzeniem stanu zamówienia. (a) duplikat dla PAID → zbędna weryfikacja, ewentualna pętla 500; (b) **latentnie**: zamówienie w stanie innym niż PENDING (dziś tylko FAILED po niezgodnej kwocie, a po wprowadzeniu wygaszania nieopłaconych zamówień — każde spóźnione) → pieniądze zweryfikowane i rozliczone w P24, realizacja odrzucona `ValidationException`, 500, ponowienia bez końca; (c) druga płatność za ten sam moduł (P7) → płatność skonsumowana bez efektu. | **K** (a, b) + **R** (c) |
| P3 | **Critical** | `payments/p24/Przelewy24Properties` (l. 20, 32), `CheckoutService.checkout` (l. 96) | **Fail-open konfiguracji.** Brak któregokolwiek z czterech poświadczeń → `isConfigured=false` → tryb mock → każde zamówienie PAID i realizowane od ręki, bez płatności. `p24.sandbox` domyślnie `true` (testowe pieniądze). Jedna brakująca zmienna środowiskowa przy wdrożeniu rozdaje FULL za darmo i nic tego nie sygnalizuje poza logiem INFO. | **K** |
| P4 | **High** | `payments/p24/Przelewy24Client` (l. 36), `CheckoutService.checkout` (l. 109) | `RestTemplate()` bez timeoutów połączenia i odczytu; wywołanie `register` wewnątrz transakcji bazy. Wolne P24 = wątki i połączenia z puli wiszą bez limitu — przy kilku równoległych zakupach cała aplikacja przestaje odpowiadać. | **K** |
| P5 | **High** | `Przelewy24WebhookController`, `subscription/management/SubscriptionReconciliationJob` | Brak trwałego zapisu notyfikacji (inbox) i brak aktywnej rekoncyliacji z API P24 — job tylko liczy. Do tego jego miernik `orders.stuck.pending` liczy **każdy porzucony koszyk** (zamówienie z tokenem starsze niż godzina), bo nic nie zamyka nieopłaconych zamówień: alarm, który dzwoni zawsze, przestaje być czytany. | **K** |
| P6 | **Medium** | `payments/checkout/OrderFulfillmentService.fulfill` | „Zapłacone" i „zrealizowane" to jeden stan. Błąd realizacji cofa `PAID` do `PENDING`: fakt otrzymania pieniędzy znika z naszej bazy (zostaje w P24 i w logu), a każde ponowienie P24 weryfikuje transakcję od nowa. | **K** |
| P7 | **Critical** | `CheckoutService.checkout`, `prepareAddOnPurchase` | Nic nie zabrania dwóch otwartych zamówień na ten sam produkt (dwie karty, podwójne kliknięcie, powrót z P24 i ponowny zakup). Oba do opłacenia; drugie po opłaceniu przepada bez efektu albo wywraca się na unikacie (D1). | **R** — dwa `PENDING` na ten sam moduł; oba `PAID`, jeden moduł |
| P8 | **Low** | `Przelewy24Client.notificationSign` | Podpis liczony ze sklejonego stringa bez escapowania JSON. Cudzysłów lub ukośnik odwrotny w `statement` daje inny skrót niż P24 → odrzucona poprawna notyfikacja (błąd w bezpieczną stronę, ale bez alarmu). | **K** |
| P9 | **Info** | całość | Płatności cykliczne (karta / BLIK recurring) nie istnieją. `RENEWAL` to ręczna płatność jednorazowa, nie ma przypomnień przed końcem okresu ani windykacji (dunning). Jeśli biznes zakłada automatyczne odnawianie, to jest nowy zakres, nie naprawa. | **K** |

### 1.4 Krok 4: wygasanie i schedulery

**Czy awaria jednego tenanta przerywa pętlę dla reszty?** Tak, w obu schedulerach — w każdym inaczej.

* `PlanDowngradeScheduler.applyDueDowngrades` ma `@Transactional` na całej metodzie i `try/catch` na
  wiersz. Wygląda na izolację (KDoc to obiecuje), ale `EntitlementService.assignPlan` też jest
  `@Transactional` i dołącza do transakcji zewnętrznej. Wyjątek przechodzący przez jego proxy oznacza
  **całą** transakcję jako rollback-only; `catch` go połyka, pętla idzie dalej, a commit na końcu rzuca
  `UnexpectedRollbackException`. Gdy błąd pochodzi z bazy, Postgres dodatkowo odrzuca każde kolejne
  polecenie w tej transakcji. Wynik: jedno „zatrute" studio blokuje downgrade'y wszystkich, co godzinę.
* `SubscriptionLifecycleScheduler` → `SubscriptionService.expireTrials/expireSubscriptions`: `forEach`
  bez `try/catch`, wyjątek wychodzi z `runBlocking`, a `expireSubscriptions()` w tym przebiegu nie rusza.
  Studia przed zatrutym zostały zapisane mimo wyjątku, czyli pętli **nie obejmuje żadna transakcja** —
  `@Transactional` na funkcji `suspend` z `withContext(Dispatchers.IO)` (ten sam wzorzec, który
  `StudioProvisioningService` opisuje jako „silently provides no such guarantee"), a każdy `save` idzie
  we własnej transakcji repozytorium.
  `findExpiredTrials` nie ma `ORDER BY`, więc to, które studia „przejdą", zależy od planu zapytania.

| # | Ważność | Miejsce | Luka | Dowód |
|---|---|---|---|---|
| J1 | **High** | `subscription/management/PlanDowngradeScheduler` (l. 57–87) | Jak wyżej: `UnexpectedRollbackException`, zdrowe studio zostaje na FULL, w każdym kolejnym przebiegu to samo. | **R** |
| J2 | **High** | `subscription/SubscriptionService.expireTrials/expireSubscriptions` (l. 136–160), `SubscriptionLifecycleScheduler` (l. 31) | Jak wyżej: studia 1–2 EXPIRED, zatrute 3. i dalsze TRIALING, płatne po terminie — ACTIVE. | **R** |
| J3 | **Medium** | oba schedulery | Brak blokady między instancjami (ShedLocka w projekcie nie ma), brak stronicowania, brak miernika niepowodzeń dla wygaszania; ładowanie pełnych encji dla wszystkich należnych studiów naraz. | **K** |
| J4 | **Medium** | `SubscriptionLifecycleScheduler`, `Studio.isAccessible` | Scheduler „zdejmujący uprawnienia" nie istnieje: zmienia tylko status, plan i moduły zostają (S3). Blokada konta to wyłącznie interceptor HTTP liczący dostęp z dat. Grace period — brak (S5). | **K** |
| J5 | **Low** | `config/CacheConfig` (l. 88), `EntitlementService.hasFeature` (l. 64) | `RedisCacheManager` bez `transactionAware()`: `@CacheEvict` czyści cache **przed** commitem transakcji zewnętrznej (scheduler, webhook); równoległe żądanie wpisuje do cache stan sprzed zmiany na 5 minut. `@Cacheable` wewnątrz transakcji wpisuje stan niezatwierdzony. `FeatureAuthorizationAspect` woła `hasFeature`, który przez wywołanie wewnętrzne omija cache — każde żądanie z `@RequiresFeature` idzie do bazy, wbrew KDoc. | **K** |

---

## CZĘŚĆ 2 — Plan naprawczy

### 2.0 Inwarianty, których pilnujemy (kryteria akceptacji każdej zmiany)

1. **Jedna płatność P24 (`orderId`) wywołuje co najwyżej jeden efekt biznesowy** — pilnuje tego baza
   (unikat), nie `if` w pamięci.
2. **Każda mutacja subskrypcji studia jest serializowana** blokadą wiersza `studios` (kolejność blokad:
   studio → plan → moduły → zamówienie), a decyzja zapada na stanie odczytanym **pod** blokadą.
3. **Fakt otrzymania pieniędzy nigdy nie jest wycofywany** razem z nieudaną realizacją.
4. **Scheduler: jeden tenant = jedna transakcja.** Błąd tenanta to metryka i wpis do ponowienia, nigdy
   koniec przebiegu.
5. **Uprawnienia efektywne = plan × status rozliczeniowy.** Każdy punkt egzekucji (HTTP, zadania w tle)
   pyta o to samo.
6. **Konfiguracja płatności zawodzi w stronę zamkniętą** na produkcji.
7. **Żaden constraint nie znika.** Zmiana ograniczenia = zastąpienie silniejszym w tej samej migracji.

### 2.1 Faza 0 — natychmiast (do 3 dni roboczych)

| Krok | Co | Zamyka | Zdejmuje `@Disabled` |
|---|---|---|---|
| 0.1 | Zapytania kontrolne z części 4 na produkcji: realne constrainty, sieroty, podwójne wpisy, zaległe downgrade'y. Wynik decyduje o treści migracji z fazy 1. | D7 | — |
| 0.2 | Fail-closed: przy aktywnym profilu produkcyjnym brak poświadczeń P24 albo `sandbox=true` zatrzymuje start aplikacji (`@PostConstruct` / `ApplicationRunner` w `Przelewy24Config`). Mock wyłącznie jawnym `P24_MOCK_MODE=true`, nigdy domyślnie. | P3 | — |
| 0.3 | Timeouty w `Przelewy24Client` (np. connect 3 s, read 10 s) przez `RestTemplateBuilder`. | P4 | — |
| 0.4 | `PlanDowngradeScheduler`: pętla bez `@Transactional`, każdy wiersz w osobnym beanie z `@Transactional(propagation = REQUIRES_NEW)`; zastosowanie przez warunkowy UPDATE (patrz 2.4). | J1, S2 | J1, S2 |
| 0.5 | Wygaszanie: metody niesuspendowane, pobranie samych ID, każde studio w `REQUIRES_NEW` z `try/catch` i miernikiem (patrz 2.4). | J2 | J2 |
| 0.6 | Webhook: blokada wiersza zamówienia (`SELECT … FOR UPDATE`) na wejściu `completeOrder`; zamówienie `PAID` z tym samym `p24OrderId` → `200 OK` **bez** `verify`. | P1, P2a | P1 |
| 0.7 | `PLAN_UPGRADE` dozwolony tylko dla `ACTIVE` z przyszłym `subscription_ends_at` i dla `TRIALING`. | S4 | S4 |

Szkic 0.4 (ten sam kształt w 0.5):

```kotlin
@Component
class PlanDowngradeScheduler(
    private val pendingRepository: PendingPlanChangeRepository,
    private val applier: PlanDowngradeApplier,
    private val clock: Clock,
    meterRegistry: MeterRegistry
) {
    private val failures = meterRegistry.counter("subscription.downgrade.failures")

    // BEZ @Transactional: pętla tylko rozdziela pracę; każda transakcja dotyczy jednego studia.
    @Scheduled(cron = "0 0 * * * *")
    fun applyDueDowngrades() {
        val now = clock.instant()
        pendingRepository.findDueIds(now).forEach { id ->
            try {
                applier.apply(id, now)
            } catch (e: Exception) {
                failures.increment()
                logger.error("Downgrade {} nie został zastosowany — ponowienie w kolejnym przebiegu", id, e)
            }
        }
    }
}

@Service
class PlanDowngradeApplier(/* … */) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun apply(pendingId: UUID, now: Instant) {
        val pending = pendingRepository.findById(pendingId).orElse(null) ?: return
        studioRepository.lockById(pending.studioId) ?: return          // inwariant 2
        // Warunkowe przejście: 0 wierszy = ktoś anulował albo zastosował przed nami (S2).
        if (pendingRepository.transition(pendingId, from = PENDING, to = APPLIED, at = now) == 0) return
        entitlementService.assignPlan(StudioId(pending.studioId), pending.toPlanKey)   // po fazie 1: changePlan
        paymentLog.record(/* … */)
    }
}
```

```kotlin
// PendingPlanChangeRepository
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""UPDATE PendingPlanChangeEntity p SET p.status = :to, p.appliedAt = :at
          WHERE p.id = :id AND p.status = :from""")
fun transition(id: UUID, from: PendingPlanChangeStatus, to: PendingPlanChangeStatus, at: Instant): Int
```

`cancelPendingDowngrade` używa tego samego warunku i zwraca `false`, gdy downgrade został już zastosowany
— wtedy UI mówi „za późno, plan został zmieniony", a nie „anulowano".

### 2.2 Faza 1 — spójność danych: JPA i schemat (1–2 tygodnie)

#### 2.2.1 Agregat planu studia: mutowalny, wersjonowany, bez „nowej instancji z tym samym id"

```kotlin
@Entity
@Table(
    name = "studio_subscription_plans",
    uniqueConstraints = [UniqueConstraint(name = "uq_studio_subscription_plans_studio", columnNames = ["studio_id"])]
)
class StudioSubscriptionPlanEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, updatable = false)
    val studioId: UUID,

    plan: PlanEntity,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {
    // null = encja nowa → Spring Data robi persist(), nie merge() (D4); potem optymistyczna blokada (D2).
    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    var plan: PlanEntity = plan
        protected set

    @Column(name = "activated_at", nullable = false)
    var activatedAt: Instant = Instant.now()
        protected set

    // LAZY: kolekcję czyta ten, kto jej potrzebuje, po założeniu blokady — nie ktoś wcześniej (D5).
    @OneToMany(mappedBy = "studioSubscriptionPlan", cascade = [CascadeType.ALL], orphanRemoval = true)
    val activeAddOns: MutableSet<StudioAddOnEntity> = mutableSetOf()

    /** Zmiana planu na MIEJSCU — ta sama instancja i ta sama kolekcja, którą śledzi Hibernate (D3). */
    fun changePlan(newPlan: PlanEntity, now: Instant) {
        if (plan.id == newPlan.id) return
        plan = newPlan
        activatedAt = now
        activeAddOns.clear()
    }
}
```

Wzorzec mutacji w `EntitlementService` (ten sam dla `activateAddOn`, `deactivateAddOn`, `changePlan`):

```kotlin
@Transactional
fun activateAddOn(studioId: StudioId, addOnKey: AddOnKey) {
    studioRepository.lockById(studioId.value)                        // inwariant 2
    val subscription = subscriptionPlanRepository.findByStudioId(studioId.value)
        ?: throw EntityNotFoundException("…")
    // refresh z blokadą: świeży stan wiersza I kolekcji (CascadeType.ALL ⊃ REFRESH), nawet jeśli
    // ta transakcja wczytała plan wcześniej — dokładnie scenariusz testu D1.
    entityManager.refresh(subscription, LockModeType.PESSIMISTIC_WRITE)
    if (subscription.activeAddOns.any { it.addOn.key == addOnKey }) return
    subscription.activeAddOns += StudioAddOnEntity(
        studioSubscriptionPlan = subscription,
        addOn = addOnRepository.findByKey(addOnKey) ?: throw EntityNotFoundException("…")
    )
}
```

```kotlin
// StudioRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
@Query("SELECT s FROM StudioEntity s WHERE s.id = :id")
fun lockById(@Param("id") id: UUID): StudioEntity?
```

`ensurePlanAssigned` — idempotencja w bazie zamiast SELECT-a:

```kotlin
@Modifying
@Query(
    value = """INSERT INTO studio_subscription_plans (id, studio_id, plan_id, activated_at, created_at, version)
               VALUES (gen_random_uuid(), :studioId, :planId, now(), now(), 0)
               ON CONFLICT (studio_id) DO NOTHING""",
    nativeQuery = true
)
fun insertIfAbsent(studioId: UUID, planId: UUID): Int
```

Pozostałe zmiany mapowania: `@Version` w `PendingPlanChangeEntity` i `PaymentOrderEntity`;
`@Modifying(flushAutomatically = true, clearAutomatically = true)` na każdym masowym UPDATE (D6);
`DemoAccountService` przez `StudioProvisioningService`, `DemoCleanupJob` z krokami dla tabel
rozliczeniowych (D8). Na `StudioEntity` **nie** dokładamy `@Version` — encję zapisują dziesiątki
niezwiązanych ścieżek (nazwa, alias), więc serializacja mutacji subskrypcji idzie przez blokadę
pesymistyczną, nie przez optymistyczną.

#### 2.2.2 Migracja: schemat rozliczeń pod kontrolą Flyway, constrainty DODANE

Kolejność: zapytania z części 4 zwracają zero wierszy (albo dane są naprawione osobnym, przejrzanym
skryptem) → migracja. Szkic (numer nadać przy wdrożeniu; ostatnia to V171):

```sql
-- Stan sprzed Flyway: tabele zakładał ddl-auto. Od teraz kształt opisuje migracja,
-- a test schematu (część 3) pilnuje, żeby żadne z tych ograniczeń nie zniknęło.

-- Wersje dla @Version (istniejące wiersze startują od 0).
ALTER TABLE studio_subscription_plans ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pending_plan_changes      ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_orders            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Unikat, który KDoc PendingPlanChangeEntity obiecuje od dawna: jeden PENDING na studio.
-- Częściowy — historia (APPLIED, CANCELLED) może mieć dowolnie wiele wierszy.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_plan_changes_one_pending
    ON pending_plan_changes (studio_id) WHERE status = 'PENDING';

-- Jedna płatność P24 = jedno zamówienie.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_orders_p24_order_id
    ON payment_orders (p24_order_id) WHERE p24_order_id IS NOT NULL;

-- Księga: jeden efekt danego typu na zamówienie (P1 jako błąd bazy, nie cichy dubel).
ALTER TABLE subscription_payment_log ADD COLUMN IF NOT EXISTS order_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_log_order_event
    ON subscription_payment_log (order_id, event_type) WHERE order_id IS NOT NULL;

-- Ograniczenia biznesowe (NIE enumowe listy — NoEnumCheckConstraintsTest ich nie dotyczy).
ALTER TABLE payment_orders ADD CONSTRAINT chk_payment_orders_amount_non_negative
    CHECK (amount_cents >= 0) NOT VALID;
ALTER TABLE payment_orders ADD CONSTRAINT chk_payment_orders_paid_has_paid_at
    CHECK (status <> 'PAID' OR paid_at IS NOT NULL) NOT VALID;
ALTER TABLE pending_plan_changes ADD CONSTRAINT chk_pending_plan_changes_real_change
    CHECK (from_plan_key <> to_plan_key) NOT VALID;

-- Klucze obce do studios (D7, D8). NOT VALID + VALIDATE: bez długiej blokady tabeli,
-- a VALIDATE i tak wywróci migrację, jeśli sieroty z zapytania Q3 nie zostały usunięte.
ALTER TABLE studio_subscription_plans ADD CONSTRAINT fk_studio_subscription_plans_studio
    FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE CASCADE NOT VALID;
ALTER TABLE studio_subscription_plans VALIDATE CONSTRAINT fk_studio_subscription_plans_studio;
ALTER TABLE pending_plan_changes ADD CONSTRAINT fk_pending_plan_changes_studio
    FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE CASCADE NOT VALID;
ALTER TABLE pending_plan_changes VALIDATE CONSTRAINT fk_pending_plan_changes_studio;
-- Pieniądze nie znikają kaskadą: usunięcie studia z zamówieniami ma się wywrócić.
ALTER TABLE payment_orders ADD CONSTRAINT fk_payment_orders_studio
    FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE payment_orders VALIDATE CONSTRAINT fk_payment_orders_studio;
```

Dwie rzeczy do zrobienia w tej samej migracji, zależnie od wyniku Q1:

* jeśli `studio_subscription_add_ons.studio_subscription_plan_id` ma FK wygenerowany przez Hibernate bez
  `ON DELETE CASCADE`, **zastąpić** go (DROP + ADD w jednej transakcji migracji) wersją z kaskadą —
  inaczej kaskada z `studios` zatrzyma się na modułach. To wymiana na silniejszy, nie usunięcie;
* `DemoCleanupJob` i `RolePreviewSandboxEraser` muszą kasować `payment_orders` przed `studios`
  (RESTRICT) — sandbox już to robi, demo nie.

Opcjonalnie, jako odpowiedź na spór z V101 („walidacja wartości enuma"): zamiast listy w CHECK —
klucz obcy `payment_orders.plan_key → subscription_plans(plan_key)`. Katalog uzupełnia
`EntitlementDataSeeder` przy każdym starcie z enuma, więc ograniczenie rośnie razem z kodem i nie ma jak
się rozjechać.

### 2.3 Faza 2 — płatności: idempotencja P24 (2–3 tygodnie)

#### 2.3.1 Stany zamówienia

```
            ┌──────────── notyfikacja OK + verify OK ─────────────┐
PENDING ────┤                                                     ▼
   │        └─ kwota/waluta niezgodna ─▶ REJECTED (bez verify)   PAID ──realizacja──▶ FULFILLED
   │                                                              │
   └─ timeLimit + 15 min bez notyfikacji ─▶ EXPIRED ──spóźniona──┘   └─ efekt niemożliwy / dubel ─▶ REFUND_REQUIRED
```

* `PAID` = mamy pieniądze (zapisywane w osobnej, krótkiej transakcji — inwariant 3).
* `FULFILLED` = efekt zastosowany. Błąd realizacji zostawia `PAID`; ponawia worker, alarmuje miernik
  `orders.paid.unfulfilled` (zastępuje mylący dziś `orders.stuck.pending`, P5).
* `EXPIRED` zamiast `FAILED` dla porzuconych koszyków: spóźniona płatność **nadal** jest przyjmowana.
* `REFUND_REQUIRED` = świadomy stan dla płatności, której nie da się zrealizować (moduł już aktywny,
  druga płatność za sesję) — trafia do operatora, nigdy nie przepada po cichu.

#### 2.3.2 Inbox notyfikacji

```sql
CREATE TABLE payment_notifications (
    id                UUID PRIMARY KEY,
    provider          VARCHAR(20)  NOT NULL,
    provider_order_id BIGINT       NOT NULL,          -- P24 orderId: klucz idempotencji
    session_id        VARCHAR(100) NOT NULL,
    amount_cents      BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    payload           JSONB        NOT NULL,          -- surowa treść, do audytu i ponownej obróbki
    source            VARCHAR(20)  NOT NULL,          -- WEBHOOK | RECONCILIATION
    status            VARCHAR(20)  NOT NULL,          -- RECEIVED | PROCESSED | UNMATCHED | REJECTED | NEEDS_REVIEW
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    last_error        TEXT,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    CONSTRAINT uq_payment_notifications_provider_order UNIQUE (provider, provider_order_id)
);
CREATE INDEX idx_payment_notifications_retry ON payment_notifications (next_attempt_at) WHERE status IN ('RECEIVED', 'UNMATCHED');
```

#### 2.3.3 Przebieg obsługi

```kotlin
@PostMapping("/status")
fun handleStatusNotification(@RequestBody n: P24Notification): ResponseEntity<String> {
    if (!p24Client.isNotificationSignValid(n)) return ResponseEntity.badRequest().body("invalid signature")
    // INSERT … ON CONFLICT (provider, provider_order_id) DO NOTHING — duplikat dostaje istniejący wiersz.
    // Wyjątek tutaj (baza leży) = 5xx = P24 ponowi; to jedyny przypadek, w którym zależymy od ponowień P24.
    val notificationId = inbox.recordOnce(n, source = WEBHOOK)
    paymentProcessor.process(notificationId)   // błędy przejściowe → next_attempt_at, nie wyjątek
    return ResponseEntity.ok("OK")             // od momentu zapisu w inbox ponowienia są NASZE
}
```

```kotlin
fun process(notificationId: UUID) {
    // 1. Krótka transakcja: blokady, decyzja na stanie spod blokady. Bez HTTP w środku.
    val decision = tx.execute {
        val n = inbox.lockForUpdate(notificationId)
        if (n.status == PROCESSED) return@execute Decision.Done
        val order = orders.lockBySessionId(n.sessionId)                 // SELECT … FOR UPDATE
            ?: return@execute Decision.Done.also { n.markUnmatched() }  // worker dopasuje później
        when {
            order.isPaidBy(n.providerOrderId) -> Decision.Done.also { n.markProcessed() }        // duplikat: bez verify
            order.isPaid() -> Decision.Done.also { n.markNeedsReview("druga płatność za zamówienie ${order.id}") }
            !order.matches(n.amountCents, n.currency) -> Decision.Done.also { order.reject(); n.markRejected() }
            else -> Decision.Verify(order.id, n.sessionId, n.providerOrderId, n.amountCents)   // PENDING albo EXPIRED
        }
    }
    if (decision !is Decision.Verify) return

    // 2. Weryfikacja w P24 poza transakcją bazy (P4). Błąd → ponowienie z backoffem.
    runCatching { p24Client.verifyTransaction(decision.sessionId, decision.providerOrderId, decision.amountCents) }
        .onFailure { inbox.scheduleRetry(notificationId, it); return }

    // 3. Fakt płatności: warunkowe przejście PENDING|EXPIRED → PAID + wpis w księdze z order_id.
    //    Unikat (order_id, event_type) i uq_payment_orders_p24_order_id to ostatnia linia obrony.
    tx.execute { orders.markPaid(decision.orderId, decision.providerOrderId, clock.instant()); inbox.markProcessed(notificationId) }

    // 4. Realizacja w osobnej transakcji, idempotentna po order_id. Błąd zostawia PAID (inwariant 3).
    fulfillment.fulfillIfPaid(decision.orderId)
}
```

`fulfillIfPaid` zaczyna od `studios.lockById` i warunkowego `PAID → FULFILLED`; przedłużenie liczy od
`max(subscription_ends_at, paid_at)` zapisanego w zamówieniu, nie od „teraz" — ponowienie po godzinie daje
ten sam wynik.

#### 2.3.4 Intencja przed P24 i aktywna rekoncyliacja

* `checkout`: transakcja 1 zapisuje zamówienie PENDING i commituje; wywołanie `register` w P24 **poza**
  transakcją; transakcja 2 dopisuje token. Webhook zawsze znajdzie zamówienie, a połączenie z puli nie
  czeka na HTTP.
* Jedno otwarte zamówienie na produkt (P7): `checkout` zwraca istniejące zamówienie PENDING, dopóki jego
  token P24 jest ważny, a starsze najpierw wygasza (EXPIRED). Ostatnią linią obrony jest indeks:

  ```sql
  CREATE UNIQUE INDEX uq_payment_orders_one_open_per_product
      ON payment_orders (studio_id, order_type, COALESCE(plan_key, ''), add_on_keys) WHERE status = 'PENDING';
  ```

  Zakłada się go dopiero w tej fazie: dziś nic nie zamyka porzuconych koszyków, więc na produkcji
  wiszą dziesiątki starych PENDING (zapytanie Q5). Kolejność: stan EXPIRED i job wygaszający →
  rekoncyliacja starych PENDING z API P24 (opłacone → realizacja, reszta → EXPIRED) → indeks.
* Worker co 5 minut: inbox `RECEIVED/UNMATCHED` z `next_attempt_at <= now()` (z `FOR UPDATE SKIP LOCKED`)
  oraz zamówienia PENDING/EXPIRED starsze niż 20 minut → zapytanie o stan transakcji w API P24 po
  `sessionId` (endpoint `transaction/by/sessionId`; kody stanów potwierdzić w dokumentacji P24) → wynik
  „opłacona" wchodzi do inboxu jako `source = RECONCILIATION` i idzie tą samą ścieżką. Zgubiona
  notyfikacja przestaje być incydentem.
* Podpis notyfikacji liczony przez Jackson (`LinkedHashMap` w kolejności z dokumentacji P24) zamiast
  sklejania stringa (P8). Opcjonalnie lista adresów IP serwerów P24 jako druga warstwa.

### 2.4 Faza 3 — cykl życia: maszyna stanów, grace period, schedulery (2–3 tygodnie)

#### 2.4.1 Jedna maszyna stanów

Czysta funkcja domenowa (bez Springa, testowana tabelą przejść), wołana przez każdy komponent, który
dziś przestawia status sam:

| Z | Zdarzenie | Do | Uwagi |
|---|---|---|---|
| NO_PLAN | start triala | TRIALING | tylko gdy `trial_used = false` |
| TRIALING | `trial_ends_at` minął | EXPIRED | bez grace (decyzja produktu) |
| NO_PLAN, TRIALING, EXPIRED | opłacony INITIAL_PURCHASE | ACTIVE | okres od `max(now, trial_ends_at)` — nie przepalamy triala (S9, do decyzji) |
| ACTIVE | `period_end` minął | PAST_DUE | `grace_ends_at = period_end + N dni` (N z konfiguracji) |
| PAST_DUE | opłacony RENEWAL | ACTIVE | nowy okres od starego `period_end` |
| PAST_DUE | `grace_ends_at` minął | EXPIRED | |
| EXPIRED | opłacony RENEWAL / INITIAL | ACTIVE | okres od teraz |

Macierz dostępu (jedna funkcja, używana przez interceptor **i** `CapabilityService`):

| Stan | API biznesowe | Zadania w tle (SMS, kampanie, Instagram, AI) | Rozliczenia (`/subscription/**`, `/payments/**`) |
|---|---|---|---|
| TRIALING, ACTIVE | tak | tak | tak |
| PAST_DUE | tak + baner | wg decyzji produktu (rekomendacja: tak — klient nie traci automatyzacji w czasie przypomnień) | tak |
| EXPIRED, NO_PLAN | nie (403 `SUBSCRIPTION_INACTIVE`) | **nie** | tak |

`EntitlementService` zwraca uprawnienia efektywne (plan × stan); klucz cache zawiera stan albo jest
unieważniany przy każdym przejściu. To jednym ruchem zamyka S3, S5, J4 — wszystkie punkty egzekucji już
pytają `CapabilityService`.

Powiązane decyzje produktowe z tej fazy: downgrade wiązany z okresem (`pending.period_end`), a cena
odnowienia = plan i moduły **następnego** okresu (S1); dezaktywacja modułu jako „anuluj z końcem okresu"
(`studio_subscription_add_ons.cancel_at`, S6); upgrade z zaliczeniem opłaconych modułów,
`max(0, (FULL − BASIC − moduły) × dni / 30)` (S7); stawka dzienna liczona na końcu (S9).

#### 2.4.2 Schedulery

```kotlin
@Component
class SubscriptionLifecycleJob(
    private val studios: StudioRepository,
    private val transitions: SubscriptionTransitionProcessor,
    private val clock: Clock,
    meterRegistry: MeterRegistry
) {
    private val failures = meterRegistry.counter("subscription.lifecycle.failures")

    @Scheduled(cron = "0 */10 * * * *")
    fun run() {
        val now = clock.instant()
        // Same ID, ORDER BY id, porcjami: pamięć stała, kolejność deterministyczna (J3).
        studios.findIdsDueForTransition(now, limit = 500).forEach { id ->
            try {
                transitions.advance(id, now)
            } catch (e: Exception) {
                failures.increment()
                logger.error("Przejście stanu subskrypcji studia {} nieudane — ponowienie za 10 min", id, e)
            }
        }
    }
}

@Service
class SubscriptionTransitionProcessor(/* … */) {
    // Zwykła metoda, NIE suspend — inaczej @Transactional nic nie daje (J2).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun advance(studioId: UUID, now: Instant) {
        val studio = studios.tryLockById(studioId) ?: return     // FOR UPDATE SKIP LOCKED: druga instancja pomija
        val next = SubscriptionLifecycle.next(studio.state(), now) ?: return   // ponowna decyzja pod blokadą
        studio.apply(next)
        events.publishEvent(SubscriptionStateChanged(studioId, next))          // @TransactionalEventListener(AFTER_COMMIT)
    }
}
```

`tryLockById` to `lockById` z podpowiedzią `jakarta.persistence.lock.timeout = -2` (w Hibernate 6:
`SKIP LOCKED`). Dzięki blokadzie wiersza i ponownej decyzji pod nią dwie instancje mogą iść równolegle
bez ShedLocka — każde przejście wykona się raz. ShedLock (dostawca JDBC) można dołożyć, gdy dojdą joby
niemające naturalnego wiersza do zablokowania.

Cache: `RedisCacheManager.builder(...).transactionAware()` — wpisy i unieważnienia wykonywane po commicie
(J5). `FeatureAuthorizationAspect` niech woła `CapabilityService`/`getEntitlements` przez proxy, nie
`hasFeature` z wywołaniem wewnętrznym.

### 2.5 Kolejność i zależności

| # | Krok | Faza | Zależy od | Zielone testy |
|---|---|---|---|---|
| 1 | Zapytania kontrolne + sprzątanie danych | 0 | — | — |
| 2 | Fail-closed P24, timeouty | 0 | — | — |
| 3 | Schedulery per tenant, warunkowe przejścia downgrade'u | 0 | — | J1, J2, S2 |
| 4 | Blokada zamówienia w webhooku, PAID przed verify | 0 | — | P1 |
| 5 | Guard statusu w PLAN_UPGRADE | 0 | — | S4 |
| 6 | Agregat JPA, `lockById`, `@Version`, idempotentne wstawienia | 1 | 3 | D1 ×2, GUARD |
| 7 | Migracja schematu rozliczeń z constraintami | 1 | 1, 6 | test schematu |
| 8 | Stany zamówienia, inbox, rekoncyliacja, jedno otwarte zamówienie | 2 | 7 | P7 |
| 9 | Maszyna stanów, grace, uprawnienia efektywne, `transactionAware` | 3 | 6 | S1 + nowe IT |

---

## CZĘŚĆ 3 — Testy integracyjne

### 3.1 Co już jest

`SubscriptionKnownDefectsTest` — 9 testów defektów (`@Disabled` z identyfikatorem) i 1 strażnik
refaktoru JPA (zielony, ma zostać zielony). Uruchomione na PostgreSQL 16: po wyłączeniu `@Disabled`
dziewięć pada dokładnie z powodów z części 1:

| Test | Wynik dziś |
|---|---|
| J1 | `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` |
| J2 | studio za zatrutym: `expected: <EXPIRED> but was: <TRIALING>` |
| P1 | `expected: <30> but was: <60>` (dni przedłużenia) |
| S2 | `expected: <FULL> but was: <BASIC>` po potwierdzonym anulowaniu |
| S1 | `expected: <9900> but was: <29900>` |
| S4 | `studio EXPIRED dostało FULL za 0 zł` |
| P7 | drugie zamówienie na ten sam moduł utworzone |
| D1 (moduł) | `duplicate key value violates unique constraint "uq_studio_add_ons"` |
| D1 (plan) | 3× `DataIntegrityViolationException` przy 4 równoległych wywołaniach |

### 3.2 Docelowa struktura

```
src/test/kotlin/pl/detailing/crm/subscription/
├── lifecycle/SubscriptionLifecycleTest.kt        unit: tabela przejść 2.4.1, każda para (stan, zdarzenie)
├── it/SubscriptionIntegrationTestBase.kt         wspólna baza (3.3)
├── it/SubscriptionExpiryIT.kt                    wygasanie, grace, izolacja tenantów, dwie instancje
├── it/PlanUpgradeIT.kt                           natychmiastowy upgrade
├── it/PlanDowngradeIT.kt                         odroczony downgrade, anulowanie, odnowienie
├── it/AccountSuspensionIT.kt                     blokada po braku opłaty: HTTP + zadania w tle
├── it/P24WebhookIT.kt                            idempotencja, kolejność zdarzeń, verify
├── it/SubscriptionSchemaConstraintsIT.kt         constrainty istnieją — strażnik zakazu ich usuwania
└── SubscriptionKnownDefectsTest.kt               (istnieje) — testy trafiają do plików wyżej po naprawie
```

Wszystkie klasy `it/` z `@Tag("testcontainers")`, jak `UpdateAppointmentHandlerPersistenceTest`.

### 3.3 Wzorce techniczne — dlaczego tak, a nie `@DataJpaTest` z domyślnymi ustawieniami

1. **Bez transakcji testowej.** Domyślny `@DataJpaTest` owija test w transakcję wycofywaną na końcu —
   wtedy nie ma commitu, nie ma flushu przy commicie, nie ma `UnexpectedRollbackException` i nie ma drugiej
   transakcji, z którą można się ścigać. **Żaden z defektów J1, J2, P1, S2, D1 nie jest w takim teście
   widoczny.** Na klasie `@Transactional(propagation = NOT_SUPPORTED)`, granice transakcji wyznacza
   `TransactionTemplate` — tak jak w produkcji robi to proxy.
2. **Schemat z migracji, nie z `ddl-auto`.** Test constraintów na schemacie wygenerowanym z encji testuje
   encje, nie produkcję. Do czasu, aż tabele rozliczeniowe będą miały `CREATE TABLE` w migracjach (krok 7),
   baza testowa = `ddl-auto=create` + wykonanie skryptu migracji z kroku 7 (`ScriptUtils.executeSqlScript`)
   w `@BeforeAll`; potem `spring.flyway.enabled=true` i `ddl-auto=validate`.
3. **Czas jako zależność.** Wstrzykiwany `java.time.Clock` (dziś wszędzie `Instant.now()`), w testach
   `MutableClock`: „minął koniec okresu" to `clock.advance(Duration.ofDays(31))`, a nie UPDATE dat w SQL.
4. **Bramki do wyścigów.** Dwie transakcje + `CountDownLatch` wstrzymujący jedną w konkretnym miejscu
   (delegujące repozytorium `object : Repo by real { … }`) — wyścig deterministyczny, nie „100 wątków
   i nadzieja". Wzór: testy P1 i S2.
5. **Trucizna tenanta.** Wyzwalacz Postgresa rzucający wyjątek dla jednego `studio_id` — model dowolnego
   błędu bazy dla jednego wiersza. Wzór: testy J1 i J2.
6. **P24 jako zaślepka HTTP.** `MockRestServiceServer` na `RestTemplate` klienta P24 (wymaga wstrzykiwania
   `RestTemplate` z `RestTemplateBuilder` — przy okazji kroku 0.3): `verify` → sukces, 500, timeout,
   „już zweryfikowana". Podpisy notyfikacji liczone tym samym CRC co w konfiguracji testu.
7. **Redis i sesje.** Dla testów HTTP (`@SpringBootTest` + `@AutoConfigureMockMvc`) kontener
   `redis:7-alpine` z `@ServiceConnection`; asercje na cache (świeżość po commicie) idą przez prawdziwy
   `RedisCacheManager`, nie przez `NoOpCacheManager`.

### 3.4 Scenariusze

**Wygasanie — `SubscriptionExpiryIT`**

| # | Given | When | Then |
|---|---|---|---|
| E1 | TRIALING, `trial_ends_at = T` | `clock = T + 1 s`, przebieg joba | EXPIRED; jedno zdarzenie przejścia |
| E2 | ACTIVE, `period_end = T` | `clock = T + 1 s`, przebieg | PAST_DUE, `grace_ends_at = T + N` |
| E3 | PAST_DUE | opłacony RENEWAL | ACTIVE, `period_end = T + 30 dni` (od starego końca, nie od „teraz") |
| E4 | PAST_DUE | `clock = grace_ends_at + 1 s`, przebieg | EXPIRED |
| E5 | 50 studiów należnych, trucizna na 1 | przebieg | 49 przetworzonych, `subscription.lifecycle.failures = 1`; kolejny przebieg dotyka tylko zatrutego |
| E6 | 50 studiów należnych | dwa przebiegi równolegle (dwa wątki = dwie instancje) | każde przejście dokładnie raz |
| E7 | `period_end == clock` | przebieg + żądanie HTTP | ta sama odpowiedź z interceptora i joba (spójna granica, S8) |

**Natychmiastowy upgrade — `PlanUpgradeIT`**

| # | Given | When | Then |
|---|---|---|---|
| U1 | ACTIVE BASIC, 20 dni do końca | checkout PLAN_UPGRADE + notyfikacja | FULL w tej samej transakcji co PAID → FULFILLED; `GET /me/entitlements` zaraz po odpowiedzi webhooka pokazuje FULL (cache po commicie) |
| U2 | ACTIVE BASIC + 2 moduły | upgrade | kwota = `max(0, (FULL − BASIC − moduły) × 20/30)`; moduły nieaktywne |
| U3 | ACTIVE FULL z zaplanowanym downgrade'em | upgrade (wariant: zmiana zdania) | downgrade CANCELLED; scheduler po `period_end` niczego nie zmienia |
| U4 | EXPIRED / NO_PLAN | checkout PLAN_UPGRADE | odrzucony (S4) |
| U5 | upgrade opłacony | ta sama notyfikacja 2× po kolei i 2× równolegle | jedno przypisanie planu, jeden wpis w księdze, drugi `verify` nie wysłany |
| U6 | realizacja rzuca błąd | notyfikacja | zamówienie PAID (nie PENDING), miernik `orders.paid.unfulfilled = 1`; po naprawie worker → FULFILLED |

**Blokada konta po braku opłaty — `AccountSuspensionIT`** (`@SpringBootTest` + MockMvc, prawdziwy
`SubscriptionInterceptor` i `WebMvcConfig`)

| # | Given | When | Then |
|---|---|---|---|
| B1 | ACTIVE, brak RENEWAL | `clock` za `grace_ends_at`, przebieg joba | `GET /api/visits` → 403 `SUBSCRIPTION_INACTIVE`; `GET /api/v1/subscription/my-plan` → 200; `POST /api/v1/subscription/checkout` → 200; `POST /api/v1/payments/p24/status` → przetworzony |
| B2 | jak B1, studio z włączoną automatyzacją SMS i modułem kampanii | przebieg dyspozytora wysyłek | `OutboundCommunicationGateway` odmawia, kredyty SMS nietknięte (S3) |
| B3 | EXPIRED z planem FULL w tabeli | `CapabilityService.resolve` | wszystkie capability płatne: `enabled = false` |
| B4 | EXPIRED | opłacony RENEWAL | 200 na `GET /api/visits` w następnym żądaniu po commicie |
| B5 | studio ze statusem PAST_DUE ustawionym ręcznie, `grace_ends_at` w przeszłości | żądanie HTTP | 403 — PAST_DUE nie jest przepustką bez daty (S5) |

**Webhook — `P24WebhookIT`**: zły podpis → 400, nic w inboxie; notyfikacja dla nieznanej sesji →
UNMATCHED → dopasowana, gdy zamówienie się pojawi (worker); niezgodna kwota → REJECTED, `verify` nie
wywołany; `verify` → 500 → `next_attempt_at` ustawione, brak realizacji, potem sukces; płatność za
zamówienie EXPIRED → realizacja; druga płatność za opłaconą sesję → NEEDS_REVIEW / REFUND_REQUIRED.

**Schemat — `SubscriptionSchemaConstraintsIT`**: zapytanie do `pg_constraint` / `pg_indexes`
z listą oczekiwanych nazw z migracji kroku 7. Usunięcie któregokolwiek ograniczenia = czerwony build
z komunikatem odsyłającym do tego raportu.

---

## CZĘŚĆ 4 — Zapytania kontrolne na produkcję (przed migracją z kroku 7)

Tylko odczyt. Wynik Q1 decyduje o treści migracji; Q2–Q4 i Q6 muszą zwrócić zero wierszy przed
założeniem odpowiadających im constraintów w kroku 7 (inaczej `VALIDATE` / `CREATE UNIQUE INDEX` wywróci
wdrożenie; wiersz `subscription_payment_log` w Q3 jest informacyjny — księga nie dostaje FK). Q5 dotyczy
indeksu z fazy 2. Q7–Q10 mierzą skalę problemów z części 1.

```sql
-- Q1. Jakie ograniczenia i indeksy NAPRAWDĘ są na tabelach rozliczeniowych.
SELECT conrelid::regclass AS tabela, conname, pg_get_constraintdef(oid) AS definicja
FROM pg_constraint
WHERE conrelid::regclass::text IN ('studios', 'studio_subscription_plans', 'studio_subscription_add_ons',
                                   'pending_plan_changes', 'payment_orders', 'subscription_payment_log')
ORDER BY 1, 2;
SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE tablename IN ('studio_subscription_plans', 'studio_subscription_add_ons', 'pending_plan_changes',
                    'payment_orders', 'subscription_payment_log')
ORDER BY 1, 2;

-- Q2. Więcej niż jeden PENDING downgrade na studio (blokuje uq_pending_plan_changes_one_pending).
SELECT studio_id, count(*) FROM pending_plan_changes WHERE status = 'PENDING' GROUP BY 1 HAVING count(*) > 1;

-- Q3. Sieroty bez studia (blokują klucze obce; źródło: DemoCleanupJob).
SELECT 'studio_subscription_plans' AS tabela, count(*) FROM studio_subscription_plans x
  WHERE NOT EXISTS (SELECT 1 FROM studios s WHERE s.id = x.studio_id)
UNION ALL SELECT 'pending_plan_changes', count(*) FROM pending_plan_changes x
  WHERE NOT EXISTS (SELECT 1 FROM studios s WHERE s.id = x.studio_id)
UNION ALL SELECT 'payment_orders', count(*) FROM payment_orders x
  WHERE NOT EXISTS (SELECT 1 FROM studios s WHERE s.id = x.studio_id)
UNION ALL SELECT 'subscription_payment_log', count(*) FROM subscription_payment_log x
  WHERE NOT EXISTS (SELECT 1 FROM studios s WHERE s.id = x.studio_id);

-- Q4. Ta sama płatność P24 przypięta do dwóch zamówień.
SELECT p24_order_id, count(*) FROM payment_orders WHERE p24_order_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;

-- Q5. Więcej niż jedno otwarte zamówienie na ten sam produkt (spodziewane: porzucone koszyki — patrz 2.3.4).
SELECT studio_id, order_type, plan_key, add_on_keys, count(*) FROM payment_orders
WHERE status = 'PENDING' GROUP BY 1, 2, 3, 4 HAVING count(*) > 1;

-- Q6. Kwoty ujemne i PAID bez daty zapłaty.
SELECT id FROM payment_orders WHERE amount_cents < 0 OR (status = 'PAID' AND paid_at IS NULL);

-- Q7. Podwójnie zrealizowane płatności (ślad P1): ten sam transaction_id i typ zdarzenia więcej niż raz.
SELECT transaction_id, event_type, count(*), min(created_at), max(created_at)
FROM subscription_payment_log WHERE transaction_id IS NOT NULL
GROUP BY 1, 2 HAVING count(*) > 1;

-- Q8. Dwie opłacone płatności za ten sam moduł w odstępie krótszym niż okres (ślad P7) — do ręcznej weryfikacji.
SELECT a.studio_id, a.add_on_keys, a.id AS pierwsze, b.id AS drugie, a.paid_at, b.paid_at
FROM payment_orders a JOIN payment_orders b
  ON b.studio_id = a.studio_id AND b.add_on_keys = a.add_on_keys AND b.id <> a.id
 AND b.paid_at > a.paid_at AND b.paid_at < a.paid_at + interval '30 days'
WHERE a.order_type = 'ADD_ON_PURCHASE' AND b.order_type = 'ADD_ON_PURCHASE'
  AND a.status = 'PAID' AND b.status = 'PAID';

-- Q9. Zaległe downgrade'y (ślad J1) i statusy rozjechane z datami (ślad J2).
SELECT count(*), min(effective_at) FROM pending_plan_changes
WHERE status = 'PENDING' AND effective_at < now() - interval '2 hours';
SELECT subscription_status, count(*) FROM studios
WHERE (subscription_status = 'TRIALING' AND trial_ends_at < now() - interval '2 hours')
   OR (subscription_status = 'ACTIVE' AND subscription_ends_at < now() - interval '2 hours')
GROUP BY 1;

-- Q10. Ekspozycja S3/S4/S5: studia bez dostępu z płatnym planem lub modułami, oraz PAST_DUE.
SELECT s.subscription_status, count(*) FROM studios s
JOIN studio_subscription_plans p ON p.studio_id = s.id
JOIN subscription_plans sp ON sp.id = p.plan_id
WHERE s.subscription_status IN ('EXPIRED', 'NO_PLAN')
  AND (sp.plan_key = 'FULL' OR EXISTS (SELECT 1 FROM studio_subscription_add_ons a WHERE a.studio_subscription_plan_id = p.id))
GROUP BY 1;
SELECT count(*) FROM studios WHERE subscription_status = 'PAST_DUE';
```
