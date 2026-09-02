# Audyt bezpieczeństwa backendu — wrzesień 2026

Zakres: cały REST API (`src/main/kotlin/pl/detailing/crm`, 107 kontrolerów), warstwa
repozytoriów (JPQL/native), webhooki, mechanizmy sesji/PIN/rate-limit. Nacisk na izolację
multi-tenant (`studio_id`), IDOR/BOLA, mass assignment, injection i RBAC.

Legenda ważności: **Critical** — bezpośredni dostęp/zapis między tenantami lub eskalacja
uprawnień; **High** — obejście uwierzytelnienia/limitów; **Medium** — spójność danych,
DoS, wyciek pośredni; **Low** — hardening.

---

## CZĘŚĆ 1 — Raport z audytu

### 1.1 Brak weryfikacji tenanta (IDOR / BOLA)

| # | Ważność | Miejsce | Luka | Status |
|---|---------|---------|------|--------|
| 1 | **Critical** | `costs/CostCategoryController.assignItems`, `CostItemAssignmentRepository.sumByCategory/findTimeSeriesByCategory` | Pozycje `ksef_invoice_items` przypinane po samym `id` (`findById`), bez sprawdzenia `ksef_invoices.studio_id`. Studio A wpinało pozycje studia B do własnej kategorii, a agregaty native SQL sumowały ich kwoty w `/breakdown`. | Naprawione |
| 2 | **Critical** | `inbound/InboundController.registerCall` (+ `SecurityConfig` permitAll) | Endpoint anonimowy zapisujący lead + call log do `studioRepository.findAll()[0]` — pierwszego studia w bazie. Zapis między tenantami bez żadnego uwierzytelnienia. | Naprawione |
| 3 | **High** | `worktime/WorkTimeService.getPeriodDetail/approvePeriod/returnPeriod` | Karta czasu pracy szukana po `userId` bez `studioId` (`findByUserIdAndPeriod`) mimo że kontroler przekazywał studio. Menedżer studia A zatwierdzał/zwracał karty studia B. Dodatkowo brak reguły „nie zatwierdzasz własnej karty”. | Naprawione |
| 4 | **Medium** | `vehicle/create/*` (`ownerIds`), `ListVehiclesHandler`, `LookupVehicleByPlateHandler` | Walidowany tylko `ownerIds[0]`; kolejne id (klient studia B) trafiały do `vehicle_owners`, a lista pojazdów rozwiązywała nazwisko cudzego klienta przez `findById`. | Naprawione |
| 5 | **Medium** | `task/create/CreateTaskHandler`, `task/list/ListTasksHandler` | `visibleToUserIds` / `visibleToRoleId` zapisywane bez weryfikacji; lista zadań rozwiązywała nazwiska/nazwy ról `findAllById` bez studia. | Naprawione |
| 6 | **Medium** | `visit/infrastructure/PhotoSessionService.claimPhotosForVisit` | Sesja uploadu zdjęć ładowana `findById` — cudze tymczasowe zdjęcia dało się „zaklepać” do własnej wizyty. | Naprawione |
| 7 | **Low** | `visit/services/ServicesChangePlanner` | `serviceId` z katalogu innego studia (`findAllById`) — odczyt stawki VAT/brutto, referencja między tenantami. | Naprawione |
| 8 | **Low** | `checkin/CreateVisitFromReservationHandler` (`appointmentColorId`), `ksef/revenue/issue/IssueRevenueInvoiceHandler` (`customerId`), `employee/account/ProvisionEmployeeAccountHandler` (`roleId`) | Identyfikatory z żądania zapisywane bez `findByIdAndStudioId` — wiszące referencje między tenantami. | Naprawione |

### 1.2 Mass assignment / manipulacja parametrami / eskalacja uprawnień

| # | Ważność | Miejsce | Luka | Status |
|---|---------|---------|------|--------|
| 9 | **Critical** | `role/create`, `role/update`, `role/assign` | Posiadacz samego `EMPLOYEES_MANAGE` mógł wyedytować własną rolę (lub założyć nową) z kompletem uprawnień i przypisać ją sobie → pełny dostęp do finansów, audytu, kadr. | Naprawione (`RoleGrantGuard`) |
| 10 | **High** | `pin/SwitchUserViaPinHandler` | Licznik nieudanych prób PIN jako read‑modify‑write na wierszu `users` — równoległe żądania omijały blokadę po 3 próbach; 4‑cyfrowy PIN właściciela = 10 000 kombinacji. | Naprawione (atomowy `INCR` w Redis) |
| 11 | **Medium** | `studio/settings/CompanyController.updateSmsSenderConfig` | Właściciel sam ustawiał `smsApiNameConfirmed=true` — flaga decyduje, czy SMS wychodzą z nagłówkiem nadawcy (spoofing np. „InPost”). „Ręczna weryfikacja po naszej stronie” nie istniała w kodzie. | Naprawione (tenant może tylko wycofać; operator potwierdza przez `/api/internal`) |
| 12 | **Medium** | `visit/services/*`, `visit/domain/Visit` | Ceny netto i korekty w pełni sterowane przez klienta bez granic (ujemne → 500, absurdalne → zapis); pozycje usług edytowalne w statusach COMPLETED/REJECTED/ARCHIVED (rozjazd z wystawionym paragonem/KSeF). | Naprawione (walidacja zakresów + zamrożenie w statusach zamkniętych) |
| 13 | **Low** | `vehicle/update` (`status`), `finance/FinanceController` (puste `requireManagerOrOwner()`), `ksef/KsefController` | Brak reguł przejść dla `VehicleStatus`; martwe helpery autoryzacyjne wprowadzają w błąd (gate klasy `FINANCE_INVOICES` jest skuteczny). | Rekomendacja |

### 1.3 Wstrzykiwanie i walidacja

Wynik: **brak SQL/JPQL/HQL injection**. Wszystkie 41 użyć `EntityManager`, 22 `JdbcTemplate` i ~350 `@Query` wiążą dane parametrami; interpolowane są wyłącznie stałe (nazwy encji w `StudioDataPurger`, tabele z listy w `TenantIsolationAuditService`, enumy). Brak `Sort.by(userInput)`, brak SpEL, brak `ProcessBuilder`.

| # | Ważność | Miejsce | Luka | Status |
|---|---------|---------|------|--------|
| 14 | **Medium** | `config/GlobalExceptionHandler` | Brak mapowania `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `IllegalArgumentException` (`UUID.fromString`, `Money`), `MaxUploadSizeExceededException` → wszystko jako 500 z ERROR stack‑trace do wywołania dowolnie wiele razy. | Naprawione |
| 15 | **Medium** | 189 `@RequestBody`, 3 `@Valid`, 0 constraintów JSR‑380 | Walidacja ad hoc; DTO kosztów, webhooka połączeń bez ograniczeń długości/rozmiaru list. | Naprawione punktowo (kosztów, inbound, platform); reszta — rekomendacja |
| 16 | **Medium** | `CustomerController`, `VehicleController`, `ServiceController`, `EmployeeController`, `AppointmentColorController`, `CostCategoryController.listExpenseItems` | Paginacja in‑memory: `limit=0` → ÷0, `page=-5` → `subList` IndexOutOfBounds, `limit=MAX_INT` → cała tabela tenanta. | Naprawione (`shared/Pagination`) |
| 17 | **Low** | `ksef/revenue/KsefRevenueController.xmlDownload` | `Content-Disposition` sklejany ze `invoiceNumber` (`"` / `;` łamały nagłówek). | Naprawione |
| 18 | **Low** | `gus/adapter/bir/parser/GusXmlParser`, `GusRawSoapClient` | `DocumentBuilderFactory` bez wyłączenia DOCTYPE/entity (XXE przy kompromitacji endpointu GUS). | Naprawione |
| 19 | **Low** | `carddav/VCardFormatter` | Imię/nazwisko wstawiane do vCard bez escapowania — `\r\n` w nazwisku wstrzykiwało właściwości do książek adresowych telefonów. | Naprawione |
| 20 | **Low** | `config/CacheConfig` | `LaissezFaireSubTypeValidator` + `DefaultTyping.EVERYTHING` — dowolna klasa z `@class` w Redisie. | Naprawione (allow‑lista + nowy prefiks cache) |
| 21 | **Low** | uploady (`SmsSenderNameController`, `DocumentController`, `CustomerDocumentService`), `LIKE` bez `ESCAPE` | Brak magic‑bytes, rozszerzenie z nazwy pliku, wildcardy w wyszukiwaniu. | Rekomendacja |

### 1.4 Uwierzytelnianie i autoryzacja endpointów

| # | Ważność | Miejsce | Luka | Status |
|---|---------|---------|------|--------|
| 22 | **High** | `smscampaigns/consent/SmsInboundController` (`/api/sms/inbound`) | Webhook SMSAPI bez żadnego uwierzytelnienia. `sms_text=TAK` zatwierdzało płatne usługi na cudzej wizycie; `STOP` masowo wypisywał klientów z kampanii we wszystkich studiach. | Naprawione (sekret w URL/nagłówku, fail‑closed) |
| 23 | **High** | `security/RateLimitFilter` | `CF-Connecting-IP` / `X-Forwarded-For` ufane od każdego peera → losowy nagłówek na żądanie = brak limitu logowań, PIN‑ów, kont demo. | Naprawione (`ClientIpResolver`: nagłówki tylko od zaufanego proxy) |
| 24 | **High** | `carddav/CardDavSecurityConfig` | HTTP Basic z hasłem konta bez licznika/blokady — omijał lockout z `/auth/login`. | Naprawione (wspólny `AccountLockoutService`) |
| 25 | **Medium** | `AuthController.login`, `PinController.switchUser`, `DemoAccountService` | Ręczne `saveContext` bez rotacji id sesji (session fixation); `maximumSessions(1)` martwe (brak `SpringSessionBackedSessionRegistry`). | Naprawione (rotacja); rejestr sesji — rekomendacja |
| 26 | **Medium** | `/actuator/prometheus`, `/actuator/metrics` (permitAll) | Metryki z tagiem `studio_id` czytelne anonimowo. | Rekomendacja (proxy/VPN lub `X-Platform-Key`) |
| 27 | **Medium** | `DemoAccountController` | Brak dedykowanego limitu — tworzenie tenantów demo w pętli (bcrypt 12, seeder). | Rekomendacja (osobny bucket + CAPTCHA) |
| 28 | **Medium** | zablokowani użytkownicy | `isActive` sprawdzane tylko przy logowaniu; sesja żyje 7 dni po zablokowaniu. | Rekomendacja (indeks principal‑name + kasowanie sesji przy blokadzie) |
| 29 | **Low** | uprawnienia `*_VIEW` na zapisach | `VisitTransitionController` (complete/reject/archive), `VisitCommentController`, `TasksController.PATCH`, `CustomerImportController`, `CategoryController` gate’owane uprawnieniem „podgląd”. | Rekomendacja produktowa (nowe `*_EDIT`/`*_MANAGE`) |
| 30 | **Low** | `voice/MobileVoiceController` | Token bez TTL/rotacji w query stringu; brak `LEADS_MANAGE`/`TASKS_MANAGE`. | Rekomendacja |

Zweryfikowane jako bezpieczne (m.in.): `PlatformAccessInterceptor` (fail‑closed, `MessageDigest.isEqual`), webhook Przelewy24 (SHA‑384 + verify), tokeny mobilne/tabletowe/podpisów/karty wizyty/CardDAV‑profile (32 B `SecureRandom`, TTL, wiązanie do studia), reset hasła, signup (zawsze nowe studio), `PermissionAuthorizationAspect` (pointcut `@within`/`@annotation`, fail‑closed przy pustej liście), gating PII (`PiiAccessFilter` decyduje serwer), WebSocket (tematy per studio).

---

## CZĘŚĆ 2 — Wdrożone poprawki (kod)

Zasada: **każde zapytanie o zasób tenanta przechodzi przez `findByIdAndStudioId` (lub
odpowiednik) ze `studioId` z sesji, nigdy z żądania**; nieistniejący i cudzy zasób dają
identyczne 404.

| Obszar | Pliki | Co się zmieniło |
|--------|-------|-----------------|
| Koszty KSeF | `costs/CostCategoryController.kt`, `costs/CostItemAssignmentRepository.kt` | pozycja akceptowana tylko, gdy `KsefInvoiceRepository.findByIdAndStudioId(item.invoiceId, studioId)`; agregaty native z dodatkowym `ki.studio_id = :studioId`; DTO z `@NotBlank/@Size/@Pattern`, `@Valid`, limit 500 pozycji, `pageSize` ograniczony. |
| Webhook połączeń | `inbound/InboundController.kt`, `application.properties` (`inbound.calls.webhook-secret`) | sesja → studio z principala (`studioId` w ciele ignorowane); brak sesji → wymagany `X-Inbound-Secret` (constant‑time) + jawne, istniejące `studioId`; pusty sekret = ścieżka anonimowa wyłączona; walidacja pól. |
| Webhook SMS | `smscampaigns/consent/SmsInboundController.kt`, `application.properties` (`smsapi.inbound-webhook-secret`) | `?secret=` lub `X-Webhook-Secret`, `MessageDigest.isEqual`, fail‑closed, 403 zamiast „OK”. |
| Czas pracy | `worktime/infrastructure/WorkTimePeriodRepository.kt`, `worktime/WorkTimeService.kt` | `findByUserIdAndStudioIdAndPeriod` we wszystkich operacjach menedżerskich; zakaz zatwierdzania/zwracania własnej karty. |
| Role | `role/permission/RoleGrantGuard.kt` (nowy), `CreateRoleHandler`, `UpdateRoleHandler`, `AssignRoleHandler` | nie‑właściciel nadaje tylko uprawnienia, które sam posiada (po domknięciu hierarchii), nie zmienia własnej roli, nie przypisuje roli bogatszej od własnej. |
| PIN | `pin/SwitchUserViaPinHandler.kt`, `pin/PinController.kt` | licznik `pin:attempts:{studio}:{user}` jako Redis `INCR` z TTL 15 min; `reset-lock` czyści klucz; rotacja id sesji po przełączeniu. |
| Sesja | `auth/AuthController.kt`, `demo/DemoAccountService.kt` | `changeSessionId()` przed `saveContext` (session fixation). |
| Lockout | `auth/login/AccountLockoutService.kt` (nowy), `auth/login/LoginHandler.kt`, `carddav/CardDavSecurityConfig.kt` | jedna blokada (5 prób / 15 min) dla logowania JSON i CardDAV Basic. |
| Rate limit | `security/ClientIpResolver.kt` (nowy), `security/RateLimitFilter.kt`, `application.properties` (`security.rate-limit.trusted-proxies`) | nagłówki forwarded honorowane tylko od loopback/RFC1918/ULA/konfigurowanych CIDR; dla `X-Forwarded-For` liczy się ostatni hop. |
| Nazwa nadawcy SMS | `studio/settings/CompanyController.kt`, `platform/PlatformStudioAdminController.kt` (nowy, `/api/internal/studios/{id}/sms-sender-confirmation`) | tenant może tylko wycofać potwierdzenie (403 przy `true`); nadanie wyłącznie przez operatora za `X-Platform-Key`. |
| Usługi wizyty | `visit/services/ServicesChangePlanner.kt`, `visit/domain/Visit.kt` | katalog przez `findAllByIdInAndStudioId`; cena ≥ 0 i ≤ limit, korekta % w [-100, 1000], kwotowe w limicie; `saveServicesChanges/approveService/rejectService` odrzucają statusy COMPLETED/REJECTED/ARCHIVED (`IllegalStateTransitionException` → 409). |
| Pojazdy | `vehicle/create/CreateVehicleValidationContext(Builder).kt`, `validators/OwnerAccessValidator.kt`, `vehicle/list/ListVehiclesHandler.kt`, `vehicle/lookup/LookupVehicleByPlateHandler.kt` | wszyscy właściciele ładowani `findByIdAndStudioId`; duplikaty/pusta lista → 400; nazwiska właścicieli rozwiązywane tylko w obrębie studia. |
| Zadania | `task/create/CreateTaskHandler.kt`, `task/list/ListTasksHandler.kt` | walidacja `visibleToUserIds`/`visibleToRoleId`; nazwy z `findByStudioId`. |
| Zdjęcia / check‑in / faktury / konta | `visit/infrastructure/PhotoSessionService.kt`, `checkin/CreateVisitFromReservationHandler.kt`, `ksef/revenue/issue/IssueRevenueInvoiceHandler.kt`, `employee/account/ProvisionEmployeeAccountHandler.kt` | sesja uploadu, kolor rezerwacji, klient faktury, rola konta — wszystkie przez `findByIdAndStudioId`. |
| Obsługa błędów | `config/GlobalExceptionHandler.kt` | `BindException`/`ConstraintViolationException` → 400 z listą pól; malformed JSON / type mismatch / missing param → 400 bez echa komunikatu frameworka; `IllegalArgumentException` → 400; `MaxUploadSizeExceededException` → 413; `IllegalStateTransitionException` → 409. |
| Paginacja | `shared/Pagination.kt` (nowy) + 5 kontrolerów | `page ≥ 1`, `1 ≤ limit ≤ max`, bezpieczny `slice`. |
| Hardening | `ksef/revenue/KsefRevenueController.kt`, `gus/.../GusXmlParser.kt`, `GusRawSoapClient.kt`, `carddav/VCardFormatter.kt`, `config/CacheConfig.kt` | `ContentDisposition` builder; XXE off; escapowanie RFC 6350; `BasicPolymorphicTypeValidator` + prefiks `crm:v4:`. |

Nowe zmienne środowiskowe (wszystkie fail‑closed, brak = funkcja wyłączona):
`SMSAPI_INBOUND_WEBHOOK_SECRET`, `INBOUND_CALLS_WEBHOOK_SECRET`, opcjonalnie
`RATE_LIMIT_TRUSTED_PROXIES`.

Zmiany zachowania wymagające uwagi produktu:
1. Webhook SMSAPI przestaje działać, dopóki w panelu SMSAPI nie zostanie dopisany `?secret=…`.
2. Anonimowe `POST /api/v1/inbound/calls` wymaga sekretu i `studioId`; zalogowani użytkownicy działają jak dotąd.
3. Właściciel nie zaznaczy już sam „nazwa nadawcy potwierdzona” — robi to operator (`PUT /api/internal/studios/{id}/sms-sender-confirmation`).
4. Menedżer bez danego uprawnienia nie nada go w roli; nikt nie zmienia własnej roli.
5. Zamknięte wizyty nie przyjmują zmian usług (409).

---

## CZĘŚĆ 3 — Testy bezpieczeństwa (JUnit 5 + MockMvc standalone + MockK)

| Test | Dowodzi |
|------|---------|
| `costs/CostCategoryCrossTenantSecurityTest` | cudza pozycja → 404 i zero zapisów; nieistniejąca → identyczne 404 (brak wyrocznie istnienia); cudza kategoria → 404; własna → 204 ze `studioId` z sesji; puste/zbyt duże/niepoprawne `itemIds`, puste `name`, `color=javascript:` i zepsuty JSON → 400. |
| `role/permission/RoleGrantGuardTest`, `RoleEscalationHandlerTest` | payload z kompletem uprawnień na własnej roli → `ForbiddenException`, rola nietknięta, cache nie unieważniony; self‑assign i rola bogatsza od własnej → odmowa; właściciel bez ograniczeń; domknięcie hierarchii (dziecko wymaga rodzica). |
| `worktime/TeamWorkTimeCrossTenantTest` | approve/return/detail karty studia B ze studia A → 404, brak zapisu, stara niescope’owana metoda nigdy nie wywołana; self‑approval → 403. |
| `inbound/InboundCallWebhookSecurityTest` | bez sekretu / zły sekret / sekret nieskonfigurowany → 401 i handler nie wywołany; poprawny sekret → 201 do wskazanego studia; brak `studioId` → 400; zalogowany użytkownik z `studioId` innego studia w ciele → zapis do WŁASNEGO studia; puste/za długie pola → 400. |
| `smscampaigns/consent/SmsInboundWebhookSecurityTest` | sfabrykowane `TAK` bez sekretu → 403 i żaden serwis nie przetwarza; zły/nieskonfigurowany sekret → 403; poprawny → 200 „OK”. |
| `pin/PinBruteForceAndSessionTest` | trzy równoległe próby ze „stale” encją → blokada po 3. dzięki `INCR`; zablokowany PIN nie dotyka hasha; sukces czyści licznik; `POST /pin/switch` zmienia id sesji. |
| `auth/login/AccountLockoutServiceTest`, `carddav/CardDavBruteForceTest` | 5. próba blokuje; CardDAV zlicza błędy do tej samej blokady i odrzuca zablokowane konto bez porównania hasha. |
| `security/ClientIpResolverTest` | spoofowane `CF-Connecting-IP`/`X-Forwarded-For` od publicznego peera ignorowane; honorowane od proxy z sieci prywatnej/CIDR; ostatni hop XFF; brak wyjątków na śmieciowym adresie. |
| `studio/settings/SmsSenderConfirmationTamperingTest` | `PATCH {"smsApiNameConfirmed":true}` → 403 bez zapisu; `false` → 200; potwierdzenie przez `/api/internal` → 200; `{}` → 400. |
| `visit/services/ServicesChangePlannerSecurityTest`, `visit/domain/VisitServicesLockedTest` | obcy `serviceId` → 404 przez lookup scope’owany, `findAllById` nigdy; cena ujemna/absurdalna, korekta -150 %/5000 %/NaN → 400; wizyta COMPLETED/REJECTED/ARCHIVED odrzuca zmiany usług; otwarta przyjmuje. |
| `vehicle/create/OwnerAccessValidatorTest` | drugi właściciel spoza studia → 404; duplikaty/pusta lista → 400. |
| `config/GlobalExceptionHandlerInputErrorsTest` | `@Valid` → 400 z `fieldErrors`; zepsuty JSON / zły typ / zły UUID / brak parametru / ujemne `Money` → 400; przejście stanu → 409. |
| `shared/PaginationTest` | `limit=0`, `page=-5`, `limit=MAX_INT` nie wywracają serwera i nie zrzucają tabeli. |

Uruchomienie: `./gradlew test -PksefStub` (stub SDK KSeF, bez Dockera) lub `./gradlew test`
w CI z prawdziwym SDK. Istniejące testy strażnicze (`AuthorizationSurfaceScanTest`,
`CrossTenantManipulationIntegrationTest`, `PiiResponseSurfaceScanTest`) przechodzą bez zmian
poza dopisaniem `PlatformStudioAdminController` do allow‑listy `/api/internal`.
