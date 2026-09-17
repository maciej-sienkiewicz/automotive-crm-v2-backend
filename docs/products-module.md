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
│   ├── ai/BarcodeImageExtractionService.kt  odczyt CYFR kodu ze zdjęcia (zapas)
│   └── web/OpenAiWebSearchClient.kt         Responses API + narzędzie web_search
├── application/
│   └── ProductResolutionService.kt   katalog -> sieć, progi, negatywny cache
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

Rozpoznanie ma DWA kroki: **nasz katalog → wyszukiwanie w sieci**. Nic więcej.

```properties
# ── Progi ────────────────────────────────────────────────────────────────────
# Powyżej min-confidence wynik jest trafieniem; poniżej wraca jako SZKIC do
# ręcznego potwierdzenia zamiast NOT_FOUND. Poniżej draft-min-confidence jest
# odsiewany (0.0 = nie odsiewaj niczego, co wyszukiwanie naprawdę zwróciło).
crm.products.lookup.min-confidence=0.90
crm.products.lookup.draft-min-confidence=0.0
crm.products.lookup.negative-cache-ttl-days=7

# ── Wyszukiwanie w sieci ─────────────────────────────────────────────────────
# Hostowane narzędzie web_search w Responses API — model PRZED odpowiedzią naprawdę
# szuka. Działa na tym samym OPENAI_API_KEY co reszta systemu: bez nowego dostawcy
# i bez osobnego klucza do wyszukiwarki. Włączone domyślnie.
crm.products.web.search.enabled=true
# Model NIE musi być specjalny — narzędzie doczepia się do zwykłego modelu.
# gpt-4.1 jest w dokumentacji wymieniony jako wspierany (kontekst wyszukiwania 128k).
crm.products.web.search.model=${PRODUCT_WEB_SEARCH_MODEL:gpt-4.1}
crm.products.web.search.context-size=MEDIUM
# Oferty tego samego kodu są lokalne — bez tego wyniki przychodzą z innego rynku.
crm.products.web.search.country=PL

# ── Odczyt cyfr kodu ZE ZDJĘCIA (zapas dekodera w przeglądarce) ─────────────
crm.ai.product-lookup.image-model=${PRODUCT_LOOKUP_IMAGE_MODEL:gpt-4.1}

# ── Sesje skanowania telefonem ──────────────────────────────────────────────
crm.products.scan-session.ttl-minutes=15
crm.products.scan-session.max-codes-per-minute=60
```

**Do modelu trafia wyłącznie kod.** Nigdy nazwa studia, klienta ani kontekst wizyty —
to wymóg bezpieczeństwa, nie optymalizacja tokenów. Dlatego pełne logowanie promptu i
surowej odpowiedzi (`[PRODUCT_WEB]`) nie niesie danych osobowych.

### Czego tu nie ma i dlaczego

Moduł miał pierwotnie łańcuch *baza → LLM z pamięci → GS1*. Produkcja go zweryfikowała:

- **LLM pytany „z pamięci"** — usunięty. Kod kreskowy to numer nadany przez GS1; nazwa
  produktu nie jest z niego wyprowadzalna, a model nie ma w wagach tablicy EAN → produkt.
  Dla realnego kodu zwracał `confidence: 0.0` z pustymi polami — zgodnie z własną regułą
  „NIE ZGADUJ", więc działał poprawnie i bezużytecznie zarazem. Mocniejszy model tego nie
  naprawiał: problemem nie była siła modelu, tylko brak dostępu do danych.
- **GS1** — usunięty. „Verified by GS1" wymaga umowy licencyjnej, której nie ma; adapter
  zwracał `null` i tylko udawał ogniwo łańcucha.
- **Otwarte bazy kodów i własne zapytania do wyszukiwarki (Google CSE / Brave)** —
  usunięte. Dublowały to, co model wyszukujący robi jednym wywołaniem, a wymagały
  osobnych kluczy i własnego klienta HTTP.

### Dwie pułapki, obie obsłużone

- **`temperature` jest zabroniona** dla modeli `*-search-preview` (błąd 400), więc
  `WebLookupConfig` jej nie ustawia. Determinizm bierze się z promptu.
- **Kod wychodzi w postaci DRUKOWANEJ.** Wewnątrz katalog kluczujemy GTIN-em-14, ale
  `05902806493015` nie znajduje w sieci niczego, a `5902806493015` znajduje produkt.
  Służy do tego `Gtin.displayValue`; pilnuje go `GtinDisplayValueTest`.

### Negatywny cache niesie sygnaturę źródła

Klucz zawiera nazwę modelu wyszukującego. „Miss" jest wnioskiem konkretnej konfiguracji,
nie faktem o kodzie — po zmianie modelu kod jest pytany od nowa. Bez tego każda poprawka
rozpoznawania była niewidoczna przez cały TTL (7 dni) dla wszystkich już zeskanowanych
kodów, bo `isNegativelyCached` ucinał zapytanie przed wywołaniem dostawcy. Dokładnie taki
objaw („nadal NOT_FOUND") zgłoszono z produkcji.

### Szkic to nie wpis do katalogu

Wynik poniżej progu wraca jako SZKIC: formularz wstępnie wypełniony, baner „sprawdź z
etykietą", poziom `AI_SUGGESTED`. Nic nie zapisuje się samo i nic nie awansuje na
„zweryfikowane" bez człowieka — niezmiennik „nie zmyślamy produktu do katalogu" obowiązuje
tak samo jak wcześniej, bo katalog jest współdzielony przez wszystkie warsztaty.
---

## 5. Budowanie i testy

Środowiska bez PAT-a do GitHub Packages: `./gradlew test -PksefStub` (patrz `CLAUDE.md` §2).
Testy nowego modułu nie dotykają KSeF, ale build i tak przerwie się na rozwiązywaniu
zależności bez tej flagi.

Testy backendowe wymagane przed wydaniem (opis i uzasadnienie — dokument kanoniczny §12):

- `ProductResolutionChainTest` — katalog przed siecią, próg 0,90 i ścieżka szkicu,
- `GtinDisplayValueTest` — kod wychodzi na zewnątrz w postaci drukowanej,
- `ProductTenantIsolationTest` — izolacja danych prywatnych przy wspólnym katalogu,
- `ProductProvenanceTest` — brak awansu poziomu weryfikacji bez potwierdzenia,
- `VisitProductsCostTest` — koszt materiału poza `totalCost`, niezmienność snapshotu,
- `ProductCostsVisibilityTest` — brak pól cenowych w JSON bez `PRODUCTS_COSTS`,
- rozszerzenie istniejącego testu niezmienników katalogu uprawnień.

### Wyszukiwanie: hostowane narzędzie `web_search` w Responses API

Rozpoznanie idzie przez **Responses API** z narzędziem `{"type": "web_search"}`, wołane
**wprost po HTTP** — Spring AI 1.0.0 zna wyłącznie Chat Completions.

Dlaczego nie Chat Completions: wyszukiwanie było tam dostępne tylko przez modele
`gpt-4o-search-preview` / `gpt-4o-mini-search-preview`, które OpenAI **wycofało
(shutdown 2026-07-23)**. Produkcja dostała `404 model_not_found`. Gdyby trzeba było
zostać przy Chat Completions, ścieżką byłby `gpt-5-search-api` — ale wtedy odpadają
filtry domen, pełna lista źródeł i kontrola dostępu do sieci.

**Model nie musi być specjalny.** Narzędzie doczepia się do zwykłego modelu;
dokumentacja wymienia `gpt-4.1` jako wspierany (kontekst wyszukiwania 128k), a ten jest
już używany w tym systemie. To celowe: nie wiążemy się z nazwą modelu o krótkim życiu —
dokładnie na tym wyłożyła się poprzednia wersja.

Trzy rzeczy, które trzeba zrobić dobrze:

- **`tool_choice: "required"`.** Przy `auto` wyszukiwanie jest OPCJONALNE i model może
  odpowiedzieć z pamięci — a z pamięci nie mapuje EAN-u na produkt i oddaje pustkę.
  `required` wymusza realne wyszukiwanie przed odpowiedzią.
- **`user_location`** z dwuliterowym kodem kraju (domyślnie `PL`). Oferty tego samego
  kodu są lokalne.
- **Cytowania są wymogiem, nie ozdobą.** Dokumentacja: gdy pokazujesz użytkownikowi dane
  z wyników wyszukiwania, źródła muszą być widoczne i klikalne. Dlatego `url_citation`
  z adnotacji wędruje przez `ProductDraft.sourceUrl` aż do banera szkicu w formularzu.

Odpowiedź to LISTA pozycji: `web_search_call` (ślad wyszukiwania) i `message` z treścią
w `content[].text` oraz adnotacjami w `content[].annotations`.

**Czego NIE używamy:** deep research (`gpt-5.5` z wysokim reasoningiem). Liczy minutami
i jest do wielostronicowych raportów — tu człowiek stoi z telefonem nad opakowaniem.
