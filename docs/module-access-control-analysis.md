# Analiza architektoniczna: szczelna kontrola dostępu do modułów SaaS (Faza: Analiza i Planning)

> Dokument analityczno-projektowy. Zero kodu implementacyjnego — wyłącznie architektura, wzorce i pseudokod.
> Zakres: `automotive-crm-v2-backend` (Kotlin/Spring) + `detailing-crm-v2` (React/TS).

---

## 0. Diagnoza stanu obecnego (Executive Summary)

System **nie ma braku mechanizmu** kontroli dostępu — ma ich **za dużo i żaden nie jest domknięty**. To klasyczny przypadek „czterech półśrodków zamiast jednego standardu":

**Backend — trzy nierówne warstwy:**

1. `SubscriptionInterceptor` (`config/SubscriptionInterceptor.kt:30-63`) — gruboziarnisty check statusu billingowego (`isAccessible()`), **fail-open**: każdy wyjątek w trakcie sprawdzania przepuszcza żądanie (`:46-47`, `:59-61`).
2. `@RequiresFeature` + `FeatureAuthorizationAspect` → HTTP 402 — kompletny, dobrze udokumentowany mechanizm paywalla, **użyty dokładnie 1 raz** w całej aplikacji (`visitcard/VisitCardSettingsController.kt:60`) na ~500 endpointów.
3. Gating modułów jako **efekt uboczny RBAC** — `PermissionCheckService.getPermissions()` filtruje uprawnienia przez `entitlementService.hasFeature()` (`role/permission/PermissionCheckService.kt:47-57`). To jest faktyczna, „przypadkowa" ścieżka egzekwowania.

**Najpoważniejsza luka:** właściciel studia **całkowicie omija** warstwę 3 (`PermissionCheckService.kt:49` — `if (snapshot.owner) return null`). Ponieważ to jedyna realnie działająca warstwa, właściciel na planie BASIC ma na poziomie API pełny dostęp do Finansów, KSeF, podpisów, kampanii i statystyk. W segmencie jednoosobowych studiów detailingowych właściciel to główny użytkownik — **płatne moduły są dziś na backendzie de facto opcjonalne**.

Inne krytyczne dziury (dowody w sekcji 4 i w raportach szczegółowych):

| # | Luka | Dowód |
|---|------|-------|
| 1 | `E_SIGNATURES` (29 zł) nie jest sprawdzany **nigdzie** na backendzie; na froncie gatuje tylko `/consents` | `signing/SignatureRequestController.kt:28` = `VISITS_CREATE`; frontend `router.tsx:307` |
| 2 | Warstwa wysyłki SMS/E-mail i **wszystkie schedulery tła** nie sprawdzają modułu | `communication/OutboundCommunicationGateway.kt:49-95`; 6 schedulerów |
| 3 | Zamknięcie wizyty wystawia dokument finansowy i wysyła SMS bez sprawdzenia `FINANCE`/`SMS_EMAIL` | `visit/VisitTransitionController.kt:29,57-58,71-80` |
| 4 | UI wysyła `sms: true` mimo zablokowanego modułu (`LockedSection` tylko blurruje, nie neutralizuje stanu) | `MarkReadyDialog.tsx:164` → `useMarkReady.ts:24` |
| 5 | Konfiguracja KSeF dostępna bez modułu FINANCE (tylko RBAC `OWNER_ONLY`) | `SettingsView.tsx:307` → `KsefCredentialsPanel` |
| 6 | Dwa moduły odblokowują się nawzajem: `CAMPAIGNS` ↔ `SMS_EMAIL`, Instagram → `CAMPAIGNS` | `CampaignController` = `COMMUNICATION_SEND`; Instagram = `MARKETING_MANAGE` |
| 7 | 6 z 13 `FeatureKey` nigdy niczego nie blokuje (w tym 3 płatne add-ony) | `CALENDAR`, `VEHICLES`, `DOCUMENTS`, `AI_LEADS`*, `INSTAGRAM_MONITORING`, `E_SIGNATURES` |
| 8 | Równoległe, niespójne źródło prawdy na froncie: `smsModuleActive` z `visitCardApi` obok `useFeature('SMS_EMAIL')` | `useVisitCardSettings.ts:21` |
| 9 | Kod 402 ma już **inne znaczenie** („brak kredytów SMS") — konflikt semantyczny z paywallem | `useMarkReady.ts:31-40` vs `GlobalExceptionHandler.kt:156-167` |

\* `AI_LEADS` jest sprawdzany w 1 miejscu na froncie, na backendzie `PermissionModule.LEADS` ma `featureKey = null`.

**Wniosek strategiczny:** problemem nie jest brak narzędzi, lecz brak **jednego modelu pojęciowego** (co jest „zdolnością" i gdzie się ją egzekwuje) oraz brak **wymuszenia pokrycia** (nic nie pilnuje, by nowy endpoint/przycisk był zgaty). Rozwiązanie musi być architektoniczne, nie punktowe.

---

## 1. Macierz Funkcjonalności (Feature / Capability Matrix)

### 1.1. Model pojęciowy: trzy poziomy zamiast dwóch

Obecny model ma dwa poziomy (`AddOnKey` → `FeatureKey`) i to jest za mało — dlatego reguły krzyżowe „nie mają gdzie mieszkać". Proponuję trójwarstwowy słownik:

```
MODUŁ (produkt, jednostka sprzedaży)     AddOnKey / PlanKey — to kupuje klient
   └─ FEATURE (flaga techniczna)         FeatureKey — to, co plan/add-on „włącza"
        └─ CAPABILITY (zdolność = akcja) CapabilityKey — to, co system EGZEKWUJE
```

**Capability** to atomowa akcja biznesowa („wyślij SMS przypominający", „wystaw fakturę", „wyślij prośbę o podpis na urządzenie klienta"), zdefiniowana jako **wyrażenie logiczne nad featurami**. Egzekwujemy wyłącznie capabilities — nigdy „modułu" wprost. Dzięki temu:

- reguła krzyżowa (podpis zdalny = `E_SIGNATURES ∧ SMS_EMAIL`) jest zwykłym wpisem w katalogu, nie wyjątkiem w kodzie;
- repackaging cennika (przenoszenie feature'ów między add-onami) nie dotyka kodu egzekwującego;
- macierz poniżej jest generowalna z katalogu — dokumentacja przestaje się rozjeżdżać z kodem.

### 1.2. Macierz docelowa (rdzeń — na bazie znanych reguł + audytu kodu)

Legenda kolumny „UI przy braku": **UPSELL** = powierzchnia sprzedażowa (overlay/modal z CTA), **DISABLE** = widoczne, nieaktywne + tooltip, **HIDE** = niewidoczne, **NEUTRALIZE** = ukryj i wyzeruj stan (checkbox nie może zostać wysłany jako `true`).

| Capability | Wyrażenie (features) | Punkty egzekwowania — backend | Punkty egzekwowania — UI | UI przy braku | Stan obecny |
|---|---|---|---|---|---|
| `COMM_SEND_TRANSACTIONAL` (SMS/e-mail przy rezerwacji, po wizycie, wysyłka karty wizyty) | `SMS_EMAIL` | **`OutboundCommunicationGateway`** (punkt skutku — łapie też schedulery), `AppointmentController.sendVisitCard`, `VisitTransitionController.markReadyForPickup` | `QuickEventModal`, `MarkReadyDialog`, `VisitCardLinkModal`, `NotificationSection` (check-in), `SmsReminderModal`, `CloseMonthModal` | DISABLE + NEUTRALIZE (inline), UPSELL w ustawieniach | Dziurawy: gateway i 4 z 6 miejsc UI bez checku; `MarkReadyDialog` wysyła `sms:true` mimo blokady |
| `COMM_SEND_CAMPAIGN` (kampanie marketingowe) | `CAMPAIGNS` | `CampaignController`, `CampaignEngine`, `AutomaticCampaignEnroller`, schedulery kampanii | routing `/campaigns*` (jest), akcje wewnątrz modułu | UPSELL (całe widoki) | Pomieszany z `SMS_EMAIL` — wzajemne odblokowanie dwóch płatnych add-onów |
| `SIGNATURE_LOCAL` (podpis na urządzeniu firmowym / tablecie sparowanym) | `E_SIGNATURES` | `SignatureRequestController` (kanał TABLET), `TabletSignatureController` (walidacja przy wydaniu tokenu sesji, nie per-request) | `ProtocolSection` („na tablecie"), `SigningRequirementModal`, `TabletsSection` (parowanie) | UPSELL (sekcja podpisu w protokole) | **Brak jakiegokolwiek checku** |
| `SIGNATURE_REMOTE_REQUEST` (prośba o podpis na urządzenie klienta) | `E_SIGNATURES ∧ SMS_EMAIL` | `SignatureRequestController` (kanał SMS/`deliveryChannel`) + `OutboundCommunicationGateway` (druga linia) | `ProtocolSection` („na telefon klienta") | DISABLE + tooltip wskazujący **brakujący** moduł | Brak; to jest reguła krzyżowa z pkt 3 zadania |
| `FINANCE_INVOICE_ISSUE` (faktura/paragon, w tym z zamknięcia wizyty) | `FINANCE` | `FinanceController`* , `IncomeDocumentsController`*, **`CompleteVisitInvoiceOrchestrator`** (dziś omija check), `VisitTransitionController.complete` | `HandoverSheet`/`InvoiceSection`, `InvoiceSummary` (appointments) | **UPSELL kontekstowy** (pkt 3.4) | Route `/finance` zgaty; ścieżka z wizyty całkowicie otwarta |
| `FINANCE_KSEF_CONFIGURE` / `FINANCE_KSEF_SEND` | `FINANCE` | `KsefController`*, `KsefRevenueController`*, schedulery KSeF (retry/sync) | `KsefCredentialsPanel` (Ustawienia → Faktury), `KsefSyncWidget` | HIDE (konfiguracja), UPSELL (widok modułu) | Konfiguracja KSeF dostępna bez modułu; schedulery bez checku |
| `AI_LEAD_ASSIST` | `AI_LEADS` | endpointy leadów AI (dziś `featureKey = null`) | `OfferComposerModal` (jest `FeatureGate`) | UPSELL inline | Backend w ogóle nie egzekwuje |
| `INSTAGRAM_MONITOR` | `INSTAGRAM_MONITORING` | 4 kontrolery Instagrama (dziś mapują na `CAMPAIGNS`) | routing `/instagram` (jest) | UPSELL (widok) | Zły klucz — kupno `CAMPAIGNS` odblokowuje Instagram |
| `STATS_VIEW` | `STATISTICS` | kontrolery statystyk (działa przez RBAC — z wyjątkiem właściciela) | routing `/statistics*`, `/reports` (jest) | UPSELL (widok) | OK dla pracowników, dziura właściciela |
| `CORE_*` (kalendarz, wizyty, klienci, pojazdy, dokumenty, galeria) | plan BASIC | `SubscriptionInterceptor` (billing) + RBAC | routing + RBAC | n/d (rdzeń produktu) | OK — ale 6 `FeatureKey` to martwe wpisy do wyczyszczenia lub użycia |

\* — jedyny dziś poprawnie zgaty obszar (przez `PermissionModule.FINANCE`), z zastrzeżeniem dziury właściciela.

**Zasada utrzymania macierzy:** macierz nie jest dokumentem w Confluence — jest **artefaktem generowanym z katalogu capabilities w kodzie** (single source of truth). Dokument, który trzeba aktualizować ręcznie, już się w tym projekcie rozjechał (`architecture.docs` opisuje nieistniejący model RBAC i `company_id` zamiast `studio_id`).

### 1.3. Architektura egzekwowania: „defense in depth" z jednym mózgiem

Zasada nadrzędna: **decyzja w jednym miejscu, egzekwowanie w wielu**. Wszystkie warstwy pytają ten sam serwis (`CapabilityService`, ewolucja obecnego `EntitlementService`); żadna warstwa nie liczy logiki samodzielnie.

```
┌────────────────────────────────────────────────────────────────┐
│  KATALOG CAPABILITIES (kod, wersjonowany, jedno źródło prawdy) │
│  capability → wyrażenie nad FeatureKey → metadane upsell       │
└──────────────────────────┬─────────────────────────────────────┘
                           │ resolve(studioId) → zbiór dozwolonych capabilities
        ┌──────────────────┼──────────────────────┬─────────────────────┐
        ▼                  ▼                      ▼                     ▼
  W1: DOMENA          W2: API               W3: JOBY TŁA           W4: UI
  punkt skutku        @RequiresCapability   check per-studio       GET /me/entitlements
  (gateway wysyłki,   (aspekt → 402         w pętli dispatcha      (rozszerzony o
  orkiestrator        z payloadem           (fail-closed,          capabilities +
  faktur, serwis      upsellowym)           skip + metryka)        powody blokady)
  podpisów)
```

**W1 — punkt skutku (najważniejsza, dziś nieistniejąca):** check w usłudze domenowej wykonującej efekt uboczny (`OutboundCommunicationGateway`, orkiestrator faktur, serwis żądań podpisu). To jedyna warstwa, której nie da się ominąć nowym endpointem, jobem ani refaktorem kontrolera. Backend audit pokazał, że 6 schedulerów wysyła SMS-y całkowicie poza jakąkolwiek kontrolą — tylko W1 to domyka.

**W2 — API, deklaratywnie:** istniejący `@RequiresFeature`/aspekt/handler 402 przemianować na `@RequiresCapability` i **faktycznie stosować**. Kluczowe: naprawić dziurę właściciela — check capability musi być **niezależny od RBAC** (właściciel omija uprawnienia, ale nigdy entitlementy). Kolejność: tenant-resolution → capability (402) → RBAC (403).

**W3 — joby tła:** każdy dispatcher iterujący po studiach pyta o capability danego studia przed wykonaniem skutku; brak → skip + licznik metryki (nie wyjątek — jedno studio nie może zatrzymać pętli dla wszystkich).

**W4 — UI:** frontend **nie liczy wyrażeń** — konsumuje wynik. `/api/v1/me/entitlements` rozszerzyć o rozstrzygnięte capabilities z powodem blokady i danymi upsellowymi (frontend już ma typ `FeatureStatus { enabled, source, upsell }` — to właściwy kierunek, wystarczy podnieść go z poziomu feature na poziom capability).

**Wymuszenie pokrycia (mechanizm, nie dyscyplina):** rozszerzyć istniejący `AuthorizationSurfaceScanTest` (już skanuje cały REST!) o regułę: *każdy mutujący endpoint musi deklarować `@RequiresCapability` albo znajdować się na jawnej, komentowanej allowliście*. Nowy endpoint płatnego modułu bez bramki = czerwony build. Analogicznie na froncie: lint rule/test, że komponenty z katalogu „akcje skutkowe" (wysyłka, wystawienie, podpis) używają `useCapability`.

**Sprzątanie:** zlikwidować równoległe mechanizmy — `smsModuleActive` z `visitCardApi` (Layer D), duplikat maszyny stanów unlock w `FeatureGate` vs `useAddOnUnlock`, podwójną listę wykluczeń interceptora (`SubscriptionInterceptor` vs `WebMvcConfig`), martwe `FeatureKey` albo podpiąć, albo usunąć.

---

## 2. Zależności krzyżowe (moduł A ∧ moduł B)

### 2.1. Wzorzec: Capability jako specyfikacja (Specification Pattern) rozstrzygana centralnie

Reguła „podpisy TAK + automatyzacja NIE ⇒ zablokuj wysyłkę prośby na telefon klienta" to nie wyjątek — to dowód, że **jednostką egzekwowania nie może być moduł**. Rozwiązanie: capability = deklaratywne wyrażenie nad featurami, rozstrzygane w jednym miejscu.

```
// Katalog (pseudokod — deklaracja, nie implementacja)
CAPABILITIES = {
  SIGNATURE_LOCAL:          requires(E_SIGNATURES),
  SIGNATURE_REMOTE_REQUEST: requires(E_SIGNATURES) AND requires(SMS_EMAIL),
  COMM_SEND_TRANSACTIONAL:  requires(SMS_EMAIL),
  FINANCE_INVOICE_ISSUE:    requires(FINANCE),
  ...
}

// Rozstrzygnięcie (backend, jedno miejsce)
resolve(studioId):
  features = entitlements(studioId).enabledFeatures
  return for each capability:
    decision  = evaluate(expression, features)          // ALLOWED / BLOCKED
    missing   = featuresMissing(expression, features)    // np. [SMS_EMAIL]
    upsell    = catalog.addOnsProviding(missing)         // co dokupić i za ile
```

Kluczowe decyzje projektowe:

1. **Wynik, nie wyrażenie, idzie do UI.** Frontend dostaje `{ SIGNATURE_REMOTE_REQUEST: { enabled: false, missingFeatures: [SMS_EMAIL], upsell: {...} } }`. Gdyby UI liczył `hasSignatures && hasComms` samodzielnie, logika krzyżowa natychmiast rozjedzie się między warstwami — dokładnie tak powstał dzisiejszy `smsModuleActive`.
2. **`missingFeatures` to nie detal — to sterownik UX.** W scenariuszu z zadania użytkownik ma podpisy, brakuje mu automatyzacji: przycisk „wyślij na telefon klienta" pokazuje disable z komunikatem *„Wymaga modułu Automatyzacja kontaktu"* i upsellem **tego konkretnego** modułu — a nie generyczne „brak dostępu". To zamienia blokadę techniczną w precyzyjny sygnał sprzedażowy.
3. **Wyrażenia trzymać płasko: AND/OR nad featurami, bez zagnieżdżania capabilities w capabilities.** Graf zależności między capabilities to prosta droga do cykli i nieprzewidywalnych rozstrzygnięć. Jeśli kiedyś pojawi się potrzeba OR (np. „raporty wymagają STATISTICS lub FINANCE"), płaska algebra to obsłuży.
4. **Degradacja częściowa zamiast zero-jedynkowej.** Sekcja podpisu w protokole wydania: kanał „tablet" aktywny (ma `SIGNATURE_LOCAL`), kanał „telefon klienta" zablokowany z powodem. Użytkownik widzi, że funkcja istnieje i czego brakuje — to jest jednocześnie poprawność i lejek sprzedażowy.
5. **Dwie linie obrony dla reguł krzyżowych:** nawet jeśli warstwa API przepuści żądanie podpisu z `deliveryChannel: SMS`, wysyłka i tak przejdzie przez `OutboundCommunicationGateway` (W1), który sprawdzi `COMM_SEND_TRANSACTIONAL`. Reguła krzyżowa jest bezpieczna, bo każdy jej „składnik skutkowy" ma własną bramkę w punkcie skutku.

### 2.2. Antywzorce do uniknięcia (obecne w kodzie)

- **Gating przyklejony do RBAC** (`Permission.effectiveFeatureKey`) — sprzęga dwie ortogonalne osie („kim jesteś" vs „co studio kupiło") i stworzył dziurę właściciela. Po wdrożeniu capabilities filtrowanie uprawnień przez feature może zostać jako UX-owy detal (szare checkboxy w edytorze ról), ale przestaje być mechanizmem bezpieczeństwa.
- **Fail-soft w punkcie akcji** (`AppointmentController.sendVisitCard`: cicho pomija wysyłkę i loguje warn) — użytkownik myśli, że karta poszła do klienta. Kontrakt musi być jednoznaczny: albo 402 z upsellem, albo UI nie pozwala wywołać akcji. Nigdy „udało się, ale nic się nie stało".

---

## 3. Strategia Upsellingu — globalny standard obsługi „braku uprawnień"

### 3.1. Najpierw rozdzielić dwie osie odmowy

| Oś | Pytanie | Kod HTTP | Kto może to zmienić | Domyślne UX |
|---|---|---|---|---|
| **Entitlement** (studio nie kupiło) | „czy studio ma moduł?" | **402** + payload upsellowy | właściciel — kupując | powierzchnia sprzedażowa |
| **RBAC** (user nie ma roli) | „czy ten użytkownik może?" | 403 | admin — nadając uprawnienie | HIDE (obecna praktyka `Can`/`RequirePermission` jest OK) |

Upsell dotyczy **wyłącznie** osi entitlement. Mieszanie ich (jak dziś w `PermissionCheckService`) sprawia, że pracownik bez uprawnienia i studio bez modułu wyglądają identycznie — a to zupełnie inne sytuacje produktowe.

**Konflikt do rozwiązania:** 402 jest już zajęty przez „brak kredytów SMS" (`useMarkReady.ts:31-40`). Standard: każdy 402 niesie maszynowy `code` (`MODULE_REQUIRED` vs `INSUFFICIENT_CREDITS`) + dla `MODULE_REQUIRED` payload `{ missingFeatures, upsell }`. Globalny interceptor axios (`apiClient.ts` — dziś 402 wpada do generycznego toastu) mapuje `MODULE_REQUIRED` na standardowy modal upsellowy. To jest **siatka bezpieczeństwa** — normalnie użytkownik nie powinien nigdy zobaczyć „gołego" 402, bo UI zablokuje akcję wcześniej.

### 3.2. Drzewo decyzyjne: HIDE vs DISABLE vs UPSELL

```
Brak dostępu do akcji/widoku
│
├─ Powód: RBAC (rola) ──────────────────────────────► HIDE
│   (upsell do pracownika bez uprawnień nie ma sensu sprzedażowo)
│
└─ Powód: brak modułu (entitlement)
    │
    ├─ Czy to CAŁY obszar produktu (widok/moduł)? ──► UPSELL pasywny
    │   (blur + overlay z korzyściami — obecny ModuleGate; wejście w Sidebar WIDOCZNE)
    │
    ├─ Czy to akcja inline w przepływie pracy?
    │   ├─ moment wysokiej intencji (pkt 3.3)? ─────► UPSELL aktywny (pełny ekran/modal)
    │   └─ zwykły przycisk/checkbox ───────────────► DISABLE + tooltip z nazwą modułu
    │       └─ stan formularza ZAWSZE NEUTRALIZE
    │         (lekcja z MarkReadyDialog: blur ≠ bezpieczeństwo — checkbox
    │          pod blurem nadal wysyła sms:true do API)
    │
    └─ Czy to konfiguracja techniczna modułu? ──────► HIDE
        (np. KsefCredentialsPanel, parowanie tabletów — konfigurowanie
         niekupionego modułu tworzy tylko zdezorientowanych użytkowników)
```

Dodatkowa oś — **rola widza upsellu**: właściciel widzi CTA „Wykup dostęp" (checkout P24 — flow `useAddOnUnlock` już istnieje); pracownik widzi „Ten moduł wymaga aktywacji przez właściciela" + opcjonalnie „wyślij prośbę". Frontend już częściowo to robi (`ModuleGate.tsx:81`) — ustandaryzować wszędzie.

### 3.3. Kiedy blokada jest hakiem sprzedażowym: kryterium intencji

Upsell aktywny (przerwanie flow pełnoekranową propozycją) stosować **tylko** gdy: (a) użytkownik właśnie wyraził intencję o wysokiej wartości, którą moduł zaspokaja, (b) korzyść da się pokazać w kontekście jego bieżących danych, (c) istnieje ścieżka „kontynuuj bez modułu" — nigdy nie wolno zablokować operacji rdzeniowej planu BASIC.

Momenty wysokiej intencji w tym produkcie:
- **zamknięcie wizyty** → FINANCE (pkt 3.4),
- próba wysyłki karty wizyty / SMS-a do klienta → CLIENT_COMMUNICATION,
- próba wysłania prośby o podpis na telefon klienta → brakujący składnik z `missingFeatures`,
- koniec miesiąca w zleceniach zbiorczych → FINANCE.

Wszystko inne = upsell pasywny (overlay widoku) albo DISABLE. **Zasada częstotliwości:** upsell aktywny w danym flow maksymalnie raz na sesję/dobę (lokalna pamięć „dismissed"), inaczej trenujemy użytkownika w klikaniu „zamknij" i wypalamy moment sprzedażowy.

### 3.4. Wzorcowy przepływ: zamknięcie wizyty bez modułu FINANCE

Dziś: `HandoverSheet` (podsumowanie, płatność, faktura, KSeF) renderuje się i działa **bez żadnego checku**. Docelowo:

1. Klik „Wydaj pojazd" → UI pyta o `FINANCE_INVOICE_ISSUE`.
2. **Brak** → zamiast standardowego modalu ekran upsellowy: komunikat o braku modułu, korzyści w kontekście („faktura do KSeF jednym kliknięciem — dla tej wizyty na kwotę X"), CTA „Wykup dostęp" (właściciel → istniejący checkout; pracownik → wariant „poproś właściciela").
3. **Obowiązkowa ścieżka wyjścia:** „Zamknij wizytę bez faktury" — uproszczone zamknięcie (podsumowanie + rejestracja płatności, bez dokumentu finansowego). Zamknięcie wizyty to operacja BASIC; jej zablokowanie to nie upsell, to hostage-taking — generuje churn, nie konwersję.
4. Backend: `VisitTransitionController.complete` przestaje bezwarunkowo wołać `CompleteVisitInvoiceOrchestrator`; orkiestrator faktur ma własny check `FINANCE_INVOICE_ISSUE` (W1).

### 3.5. Standaryzacja komponentowa (frontend)

Jeden hook + jedna rodzina komponentów zamiast obecnych czterech mechanizmów:

- `useCapability(key)` → `{ enabled, missingFeatures, upsell, reason }` (nakładka na rozszerzone `/me/entitlements`);
- `<RequireCapability capability=... mode="upsell|disable|hide|neutralize">` — jedyny dozwolony sposób gatowania w UI (reguła lintera);
- ujednolicić `ModuleGate`/`FeatureGate`/`LockedSection` do jednej implementacji z wariantami; naprawić niespójność fail-open/fail-closed przy ładowaniu (dziś `ModuleGate` przepuszcza podczas loadingu, `useFeature` blokuje → miganie);
- globalny handler 402/`MODULE_REQUIRED` w `apiClient` jako siatka bezpieczeństwa;
- drobiazg o dużym koszcie: naprawić martwy link `/settings?tab=subscription` → `tab=plan` w `ModuleGate` (dziś CTA upsellowe prowadzi donikąd).

---

## 4. Root Cause Analysis: `Błąd aktywacji — Studio nie ma aktywnego planu subskrypcji: <UUID>`

### 4.1. Fakty ustalone w kodzie (to już nie hipotezy)

- Jedyne źródło komunikatu: `EntitlementService.activateAddOn()` (`entitlement/EntitlementService.kt:102`). **UUID w logu to `studioId`** (nie id subskrypcji) — `StudioId.toString()` zwraca gołe `value`.
- Warunek: brak wiersza w `studio_subscription_plans` dla studia (`findByStudioIdWithAddOns` → null).
- Wywołania: `OrderFulfillmentService.fulfillInitialPurchase` (bezpieczne — plan właśnie przypisany) i **`fulfillAddOnPurchase` (`OrderFulfillmentService.kt:95`) — tu leci błąd: po pozytywnym webhooku P24, po weryfikacji podpisu i po oznaczeniu zamówienia jako PAID.** Czyli: **pieniądze pobrane, aktywacja nie nastąpiła.**
- Frontendowy string „Błąd aktywacji" pochodzi z `PlanChangeDialog.tsx:197` i `FirstLoginModal.tsx:215` — ścieżek aktywacji add-onów.
- Asymetria maskująca: `getEntitlements()` przy braku wiersza **nie rzuca** — zwraca `defaultTrialEntitlements()` (pełny BASIC). Odczyt jest fail-open, zapis fail-closed. Studio „bez planu" wygląda w całym UI jak zdrowe, dopóki nie spróbuje dokupić modułu.

### 4.2. Hipotezy architektoniczne (uszeregowane wg prawdopodobieństwa)

**H1 — Luka inwariantu prowizjonowania: trial i świeże studia nie mają wiersza planu (najbardziej prawdopodobna, potwierdzona ścieżka w kodzie).**
Rejestracja (`SignupHandler` → `SubscriptionService.createStudio()`) tworzy studio ze statusem `NO_PLAN` **bez** wiersza w `studio_subscription_plans`. `startTrial()` ustawia `TRIALING` na `StudioEntity` — **również bez wiersza planu**; entitlementy triala żyją wyłącznie w fallbacku `getEntitlements()`. Studio na 60-dniowym trialu jest więc w pełni funkcjonalne, UI (`FirstLoginModal` — ścieżka „BASIC + add-ony", `PlanChangeDialog`) pozwala kupić add-on, checkout przechodzi, płatność się udaje — i fulfillment `ADD_ON_PURCHASE` wybucha, bo add-on nie ma się do czego „przykleić". Błąd jest **deterministyczny** dla każdego studia w trialu kupującego sam add-on.

**H2 — Rozjazd dwóch równoległych modeli subskrypcji (przyczyna systemowa, której H1 jest objawem).**
System utrzymuje **dwa niepowiązane transakcyjnie modele**: (A) lifecycle billingowy na `StudioEntity` (`subscription_status`, daty) i (B) model entitlementowy (`studio_subscription_plans` + add-ony). Bramki i UI decydują na podstawie A („studio aktywne"), fulfillment wymaga B („wiersz planu istnieje"). Nie istnieje żaden inwariant ani mechanizm uzgadniający A z B — każda ścieżka, która zmodyfikuje jeden model bez drugiego (trial, ręczna interwencja w DB, przyszły import tenantów, migracja), produkuje ten błąd. Dodatkowo `expireTrials()`/`expireSubscriptions()` to martwy kod (nigdy niewywoływany), więc statusy w A też nie odzwierciedlają rzeczywistości.

**H3 — Race condition / kolejność fulfillmentu przy zakupach współbieżnych.**
Zakup planu i add-onu jako **osobne zamówienia** (np. użytkownik szybko klika w `SubscriptionSettingsPage`, albo retry webhooka P24) nie ma gwarancji kolejności: webhook `ADD_ON_PURCHASE` może zostać przetworzony przed `INITIAL_PURCHASE`. Webhooki P24 przychodzą asynchronicznie i mogą być ponawiane; brak globalnego uporządkowania per-studio i brak stanu „oczekuje na plan" ⇒ okno wyścigu. Ten wariant tłumaczyłby błędy sporadyczne (w odróżnieniu od deterministycznego H1).

**H4 — Maskowanie przez cache i fail-open czyni błąd niewykrywalnym do momentu płatności.**
Redis (`studio-entitlements`, TTL 5 min) + fallback trialowy sprawiają, że **żaden odczyt nigdzie nie sygnalizuje** braku wiersza planu — system „udaje zdrowy" aż do pierwszego zapisu. To nie jest bezpośrednia przyczyna wyjątku, ale architektoniczny wzmacniacz: usuwa wszystkie wczesne sygnały ostrzegawcze i przesuwa moment wykrycia na najgorszy możliwy — po pobraniu pieniędzy. (Wątek pokrewny: brak migracji Flyway dla tabel entitlementowych — schemat na produkcji z `ddl-auto=validate` zależy od ręcznej synchronizacji, a katalog planów wyłącznie od seedera przy starcie; padnięty seeder = pusty katalog = ta sama klasa błędów przy `assignPlan`.)

### 4.3. Diagnostyka (w kolejności wykonania)

1. **Potwierdzenie H1/H2 jednym zapytaniem:** zestawić studia o statusie `TRIALING`/`ACTIVE` z brakiem wiersza w `studio_subscription_plans`; sprawdzić, czy UUID-y z logów (to `studio_id`) są w tym zbiorze i jaki mają status + `trial_ends_at`.
2. **Skala szkody finansowej:** skorelować wystąpienia błędu z zamówieniami `ADD_ON_PURCHASE` w stanie PAID bez śladu aktywacji w `subscription_payment_log` — to lista klientów, którzy **zapłacili i nie dostali modułu** (do ręcznej naprawy + komunikacji).
3. **Rozstrzygnięcie H3:** timeline webhooków per studio (timestampy zamówień vs fulfillment) — czy błędy występują też u studiów, które *miały* plan chwilę później.
4. **Audyt driftu modeli:** pełne zestawienie rozjazdów A↔B (status vs wiersz planu) jako miara skali H2.

### 4.4. Naprawa na poziomie architektury

1. **Inwariant prowizjonowania: „każde studio od momentu rejestracji ma dokładnie jeden wiersz subskrypcji"** — trial staje się pełnoprawnym planem (`PlanKey.TRIAL` lub wiersz BASIC z flagą okresu próbnego), tworzony transakcyjnie razem ze studiem. Usunąć cichy fallback `defaultTrialEntitlements()` — po wprowadzeniu inwariantu brak wiersza to **błąd danych**, który ma krzyczeć (alert), a nie udawać BASIC. Wzorzec: eliminacja stanu niereprezentowalnego zamiast obsługi go w locie.
2. **Jeden model subskrypcji.** Docelowo scalić lifecycle (A) i entitlementy (B) w jeden agregat `StudioSubscription` (plan + status + okresy + add-ony); przejściowo — inwariant utrzymywany transakcyjnie i test spójności w CI/reconciliation.
3. **Fulfillment odporny na kolejność i awarie:** idempotencja per `orderId`; walidacja wykonalności **przed** przyjęciem płatności (checkout add-onu wymaga istniejącego planu — a po wdrożeniu pkt 1 warunek spełniony zawsze); wzorzec transactional outbox / retry z kolejką dla kroków fulfillmentu, żeby „PAID bez aktywacji" nie był stanem trwałym, tylko przejściowym z automatycznym domknięciem.
4. **Reconciliation job + alerting:** cykliczne uzgadnianie zamówień PAID ↔ stan entitlementów ↔ status studia; każda rozbieżność = alert operacyjny. Przy okazji: przywrócić do życia albo usunąć martwe `expireTrials()`/`expireSubscriptions()` oraz naprawić fail-open w `SubscriptionInterceptor` (wyjątek infrastrukturalny nie może oznaczać „wpuść").
5. **Obserwowalność płatności jako wymóg, nie dodatek:** metryka `fulfillment_failures_total` z podziałem na typ zamówienia + log strukturalny (studioId, orderId, typ) — dzisiejszy komunikat bez kontekstu zamówienia wymusił całe to śledztwo.

---

## 5. Sekwencja wdrożenia (rekomendacja)

| Faza | Zakres | Efekt |
|---|---|---|
| **1. Stop-krwawienie** | Naprawa RCA (inwariant wiersza planu + idempotentny fulfillment + naprawa poszkodowanych zamówień PAID); check `SMS_EMAIL` w `OutboundCommunicationGateway`; neutralizacja `sms:true` w `MarkReadyDialog`; naprawa linku upsellowego | Koniec pobierania pieniędzy bez aktywacji; zamknięcie największego przecieku wysyłki |
| **2. Fundament** | Katalog capabilities + `CapabilityService`; `@RequiresCapability` niezależne od RBAC (dziura właściciela); rozszerzone `/me/entitlements`; kontrakt 402/`MODULE_REQUIRED` | Jeden mózg decyzyjny, obie warstwy pytają to samo źródło |
| **3. Domknięcie macierzy** | Podpięcie E_SIGNATURES (w tym reguła krzyżowa), FINANCE w flow wizyty (z upsellem z pkt 3.4), rozplątanie CAMPAIGNS/SMS_EMAIL/Instagram, joby tła, `useCapability` w miejscach z sekcji 1.2 | Szczelność wszystkich znanych reguł biznesowych |
| **4. Wymuszenie trwałości** | Rozszerzony `AuthorizationSurfaceScanTest` (pokrycie capabilities), lint na froncie, generowanie macierzy z katalogu, usunięcie mechanizmów równoległych, migracje Flyway dla tabel entitlementowych | Nowy moduł nie może powstać „dziurawy" — szczelność pilnowana przez build, nie przez ludzi |

Miary sukcesu: 0 endpointów mutujących bez deklaracji capability (poza allowlistą), 0 zamówień PAID bez aktywacji > 15 min, 100% akcji skutkowych z macierzy zgatych na obu warstwach, konwersja upsellu mierzona per powierzchnia (widok vs moment intencji).
