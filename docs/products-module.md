# Moduł Produktów — strona backendowa

> **Kanoniczny dokument architektury:** `detailing-crm-v2/docs/products-module-architecture.md`
>
> Tu są **wyłącznie** rzeczy lokalne dla tego repozytorium: układ pakietów, punkty
> rejestracji w istniejących katalogach, klucze konfiguracji i kolejność wdrożenia
> schematu. Reguł z tamtego dokumentu nie powtarzamy — dwie kopie tych samych zasad
> rozjeżdżają się w pierwszym tygodniu (patrz `AGENTS.md`).

---

## 1. Układ pakietu

```
pl.detailing.crm.product/
├── ProductController.kt              @RequiresCapability(PRODUCTS_ACCESS) na klasie
├── domain/
│   ├── Product.kt                    ProductSpec, Provenance, VerificationLevel
│   ├── Gtin.kt                       walidacja sumy kontrolnej + normalizacja do GTIN-14
│   └── UnitOfMeasure.kt              ML, L, G, KG, PIECE, PAIR, M, M2
├── port/
│   └── ProductDataProvider.kt        jeden interfejs na dostawcę danych
├── adapter/
│   ├── local/LocalCatalogProvider.kt
│   ├── ai/AiProductProvider.kt       + AiProductConfig (ChatClient, temperature = 0.0)
│   └── gs1/Gs1ProductProvider.kt     + GepirFallbackProvider
├── application/
│   └── ProductResolutionService.kt   łańcuch, limity, cache, negatywny cache
├── create/ update/ list/ get/ archive/    handlery + walidatory (konwencja z `service/`)
├── notes/                            notatki studia
├── rating/                           ocena studia
├── usage/                            VisitProductsController + handlery (relacja M:N)
├── scan/                             ProductScanSessionService, MobileProductScanController
├── moderation/                       propozycje korekt (kolejka; panel dopiero w fazie 5)
└── infrastructure/                   encje + repozytoria JPA
```

Wzorce do skopiowania, sprawdzone w tym repo:

| Co budujemy | Skąd bierzemy wzorzec |
|---|---|
| Port + adaptery do rejestru zewnętrznego | `gus/` (port `CompanyDataProvider`, adapter BIR, `application/GusCompanyService`) |
| Handlery i walidatory CRUD | `service/create`, `service/update` (kompozyt walidatorów + kontekst) |
| Odczyt strukturalny z LLM | `leads/vehicle/LeadVehicleExtractionService.kt` (structured output, `temperature = 0.0`, „NIE ZGADUJ" w prompcie) |
| Notatki z pełnym audytem | `visit/infrastructure/VisitCommentEntity.kt` |
| Sesja handoffu telefon ↔ desktop | `customer_import_sessions` (V99) + `customer/importing/MobileContactImportController.kt` |
| Snapshot cen na pozycji | `visit/infrastructure/VisitEntity.kt` → `VisitServiceItemEntity` |

---

## 2. Punkty rejestracji w istniejących katalogach

Moduł nie działa, dopóki nie zostanie wpisany w pięć miejsc. Kolejność ma znaczenie —
`Permission` odwołuje się do `FeatureKey`, a seeder do obu.

1. **`subscription/entitlement/FeatureKey.kt`** — `PRODUCTS("Produkty i zużycie materiałów")`
   w sekcji modułów dodatkowych.
2. **`subscription/entitlement/domain/EntitlementDomain.kt`** — `AddOnKey.PRODUCTS_MODULE`.
3. **`subscription/entitlement/capability/Capability.kt`** —
   `PRODUCTS_ACCESS("Moduł produktów", setOf(FeatureKey.PRODUCTS))`.
4. **`config/EntitlementDataSeeder.kt`** — wpis w `syncFeatures()` i nowy dodatek
   w `syncAddOns()` (29,00 zł/mies. = `2_900L`). Planu FULL **nie trzeba ruszać**:
   `syncPlans()` daje mu `features.values.toSet()`, więc nowa cecha wchodzi do niego
   automatycznie. `basicFeatures` jest listą jawną i zostaje bez zmian — moduł jest
   dodatkiem, nie częścią pakietu podstawowego. Seeder jest źródłem prawdy dla cennika,
   nie ma tu żadnego SQL-a do ręcznego uruchomienia.
5. **`role/domain/Permission.kt` + `PermissionHierarchy.kt`** — cztery uprawnienia
   (`PRODUCTS_VIEW` jako niezależny KORZEŃ, dzieci `PRODUCTS_USAGE/MANAGE/COSTS`) oraz
   implikacja `PRODUCTS_USAGE → VISITS_VIEW`.

   **Uwaga o module (decyzja wymuszona przez niezmiennik repo).** Produkty NIE są
   osobnym `PermissionModule`. Repo egzekwuje testem
   (`PermissionHierarchyTest.„every non-VISITS module root requires visit creation"`),
   że **każdy korzeń spoza modułu wizyt implikuje `VISITS_CREATE`** — a to ciągnie
   `CUSTOMERS_VIEW` (kartotekę klientów). Wymaganie mówi wprost, że osoba od zaopatrzenia
   ma dostać sam katalog bez kartoteki, więc korzeń produktów musi być niezależny i NIC
   nie implikować. Jedyny sposób w tym repo, żeby mieć taki niezależny korzeń bez
   osłabiania testu, to umieścić go **w module wizyt** — dokładnie jak `BATCH_ORDERS`.
   Cztery uprawnienia siedzą więc w `PermissionModule.VISITS` z
   `featureKeyOverride = FeatureKey.PRODUCTS` i `section = "Produkty w studiu"`.
   „Osobny moduł" z wymagania jest realizowany tam, gdzie ma znaczenie — w
   finansach/abonamencie (FeatureKey/AddOnKey/CapabilityKey). Test katalogu uprawnień
   przechodzi bez żadnej modyfikacji.

Lustro po stronie frontu (`core/permissions/catalog.ts`, `modules/subscription/types`)
jest opisane w dokumencie kanonicznym §7 i §9.

---

## 3. Schemat bazy

Repo ma DWA tryby i migracja musi działać w obu:

- **Lokalnie** (`application.properties`): `spring.flyway.enabled=false` +
  `ddl-auto=update` — schemat tworzy Hibernate z encji, a plik migracji się NIE wykonuje.
- **Wdrożenie** (`application-docker-props.properties`): `spring.flyway.enabled=true` +
  `ddl-auto=validate` — **Flyway buduje schemat**, a Hibernate go tylko WERYFIKUJE.

Dlatego `src/main/resources/db/migration/V138__products_module.sql` jest **pełną
migracją tworzącą tabele** (`CREATE TABLE IF NOT EXISTS` dla products, product_studio,
product_notes, product_ratings, visit_products, product_correction_proposals) —
z kolumnami DOKŁADNIE jak w encjach, bo inaczej `validate` wywali start aplikacji.
Poza tabelami niesie też:

- indeksy częściowe i funkcyjne (`uq_products_gtin WHERE gtin IS NOT NULL`,
  `uq_products_natural_key`, GIN po `to_tsvector`) — w trybie `validate` Hibernate ich
  nie tworzy, więc muszą być w migracji;
- ograniczenia `CHECK` (kompletność pary cena/kierunek, zakres oceny 1..5, dodatnia
  wielkość opakowania, dozwolone stawki VAT z −1);
- `COMMENT ON TABLE products` — zdanie wyjaśniające brak `studio_id`. To jedyna taka
  tabela w systemie i każdy przyszły przegląd bezpieczeństwa ma trafić na to wyjaśnienie
  w bazie, a nie szukać go w dokumentacji.

**Numer migracji sprawdzaj sortowaniem wersji, nie `ls`** (`ls … | sort -V | tail -1`):
`ls` stawia „V100" przed „V99", co raz już wygenerowało kolizję. `UniqueMigrationVersionsTest`
pilnuje unikalności, a `StudioResetCoverageTest` — że każda nowa encja jest objęta
„Wyczyść konto" (dane prywatne produktów czyści `StudioDataPurger`, globalny katalog jest
świadomie zachowany).

Pełny DDL: dokument kanoniczny §2.3.

---

## 4. Konfiguracja

Nazewnictwo idzie za istniejącą konwencją `crm.ai.*` z `application.properties`.

```properties
# ── Rozpoznawanie produktu po kodzie ────────────────────────────────────────
# Kolejność łańcucha. Zmiana na LOCAL,GS1,AI nie wymaga deployu kodu.
crm.products.resolution.order=LOCAL,AI,GS1
# Próg trafienia PEWNEGO (auto-RESOLVED bez zejścia niżej).
crm.products.resolution.ai-min-confidence=0.90
# Próg SZKICU: poniżej ai-min-confidence, a ≥ tego progu, odczyt AI wraca jako szkic do
# ręcznego potwierdzenia zamiast NOT_FOUND (łagodne zejście). 0.0 = pokaż każdą kartę,
# którą model faktycznie wypisał. Patrz ProductResolutionService.
crm.products.resolution.ai-draft-min-confidence=0.0
crm.products.lookup.rate-limit.per-day=100
crm.products.lookup.negative-cache-ttl-days=7
crm.products.lookup.ai-timeout-ms=8000
crm.products.lookup.gs1-timeout-ms=5000

# Model odczytu: fakt, nie twórczość — temperatura 0 jak w pozostałych odczytach.
# Odczyt po kodzie to zadanie na WIEDZĘ modelu, więc czytnik jest mocniejszy.
crm.ai.product-lookup.model=${PRODUCT_LOOKUP_MODEL:gpt-4.1}
# Model weryfikatora: niezależny, MNIEJSZY krytyk „czy na pewno ta karta należy do tego
# kodu". Może tylko OBNIŻYĆ zaufanie — jego „nie" spycha wynik poniżej progu pewności.
crm.ai.product-lookup.verifier-model=${PRODUCT_LOOKUP_VERIFIER_MODEL:gpt-4.1-mini}

# ── GS1 ─────────────────────────────────────────────────────────────────────
# Bez umowy licencyjnej zostaw enabled=false — moduł działa, traci tylko krok 3.
gs1.enabled=${GS1_ENABLED:false}
gs1.api.base-url=${GS1_API_BASE_URL:}
gs1.api.key=${GS1_API_KEY:}
gs1.gepir.enabled=${GS1_GEPIR_ENABLED:true}

# ── Sesje skanowania telefonem ──────────────────────────────────────────────
crm.products.scan-session.ttl-minutes=15
crm.products.scan-session.max-codes-per-minute=60
```

**Prompt do LLM dostaje wyłącznie GTIN.** Nigdy nazwy studia, klienta ani kontekstu
wizyty — to jest wymóg bezpieczeństwa, nie optymalizacja tokenów.

**Zachowanie „łagodne" (decyzja produktowa).** GTIN to numer, którego model nie mapuje
pewnie na produkt, więc trzymanie sztywnego progu 0,90 przy wyłączonym GS1 dawało w
praktyce zawsze `NOT_FOUND`. Dlatego odczyt poniżej progu nie jest wyrzucany: łańcuch
najpierw próbuje kolejnych dostawców, a gdy żaden nie da pewnej karty, oddaje najlepszy
odczyt AI jako **SZKIC** (`RESOLVED`, poziom `AI_SUGGESTED`, jawnie niska pewność). Front
pokazuje go w formularzu z banerem „sprawdź z etykietą". To NIE jest wpis do katalogu —
nic nie zapisuje się samo i nic nie awansuje na „zweryfikowane" bez człowieka; szkic
znika, jeśli operator go nie zatwierdzi. Czytnik jest mocniejszym modelem, weryfikator
mniejszym i niezależnym (`ai-draft-min-confidence` odsiewa najmniej pewne odczyty).

---

## 5. Budowanie i testy

Środowiska bez PAT-a do GitHub Packages: `./gradlew test -PksefStub` (patrz `CLAUDE.md` §2).
Testy nowego modułu nie dotykają KSeF, ale build i tak przerwie się na rozwiązywaniu
zależności bez tej flagi.

Testy backendowe wymagane przed wydaniem (opis i uzasadnienie — dokument kanoniczny §12):

- `ProductResolutionChainTest` — kolejność łańcucha i próg 0,90,
- `ProductTenantIsolationTest` — izolacja danych prywatnych przy wspólnym katalogu,
- `ProductProvenanceTest` — brak awansu poziomu weryfikacji bez potwierdzenia,
- `VisitProductsCostTest` — koszt materiału poza `totalCost`, niezmienność snapshotu,
- `ProductCostsVisibilityTest` — brak pól cenowych w JSON bez `PRODUCTS_COSTS`,
- rozszerzenie istniejącego testu niezmienników katalogu uprawnień.
