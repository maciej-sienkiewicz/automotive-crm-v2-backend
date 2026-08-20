# Moduł metryk i analityki produktowej

Zastępuje poprzedni system dashboardów oparty o statyczny plik `crm-overwiev.json`
(Grafana + Prometheus). Zbudowany od zera, wdraża się bez migracji danych.

---

## 1. Dlaczego stary system musiał odejść

Poprzednie rozwiązanie odpowiadało dokładnie na jedno pytanie: **czy serwer żyje teraz**.
To wartościowe pytanie i Prometheus zostaje w systemie, żeby dalej na nie odpowiadać.
Natomiast pytania, które faktycznie zadaje dostawca SaaS-a, były tam strukturalnie
nieodpowiadalne — i nie z powodu braku dashboardu, tylko z powodu właściwości narzędzia:

| Ograniczenie Prometheusa | Konsekwencja |
|---|---|
| Krótka retencja (dni/tygodnie) | „Ile godzin to studio spędziło w CRM w marcu?" — dane już nie istnieją |
| Etykieta = wymiar kardynalności | `studio_id` jako label przy 500 tenantach × 200 endpointów = eksplozja serii i OOM |
| Brak pojęcia dnia kalendarzowego | Doba UTC przesuwa dwie godziny każdego letniego wieczoru na następny dzień |
| Brak trwałego stanu | „Czy ten błąd był już zgłoszony i naprawiony?" — nie ma gdzie tego zapisać |
| Metryka to liczba, nie encja | Nie da się powiedzieć „ten defekt dotknął studia X, Y i Z" |

Nowy moduł trzyma analitykę produktową z przypisaniem do tenanta i długim horyzontem
w Postgresie. **Prometheus nie znika** — dostaje węższą, właściwą sobie rolę.

### Podział odpowiedzialności

```
                    Prometheus  ──►  sygnały techniczne w czasie rzeczywistym,
                                     alerty, JVM/HTTP, retencja dni
                                     (+ nowe gauge'y subskrypcji — wymóg „real-time")

                    Postgres    ──►  analityka produktowa per tenant,
                                     historia lat, encje z cyklem życia
                                     (grupy błędów, katalog endpointów)
```

---

## 2. Przepływ danych

```
   HOT PATH (nigdy nie blokuje, nigdy nie rzuca wyjątku)
   ┌──────────────────────────────────────────────────────────┐
   │  Interceptor ruchu API   →  bufor w pamięci (LongAdder)   │
   │  Aspekty biznesowe       →  kolejka ograniczona (20k)     │
   │  Heartbeat sesji         →  UPDATE jednego wiersza        │
   │  Aspekty błędów          →  REQUIRES_NEW, osobna trans.   │
   └──────────────────────────────────────────────────────────┘
                              │
                   flush co 5 s / 60 s
                              ▼
   SUROWE TABELE       metric_events, metric_user_sessions,
                       metric_api_endpoint_daily, metric_studio_api_daily,
                       metric_error_events
                              │
                   agregacja 03:10 / 03:25 + co godzinę
                              ▼
   READ MODEL          metric_daily_studio_snapshots
                       metric_daily_platform_snapshots
                              │
                              ▼
   KONSOLA             GET /api/internal/metrics/...
```

Trzy właściwości hot pathu są nienegocjowalne i wymuszone w kodzie:

1. **Nigdy nie rzuca.** Awaria metryk, która cofa rezerwację klienta, jest gorsza niż
   utrata metryki. Każda ścieżka jest opakowana; najgorszy skutek to zgubione zdarzenie
   i wpis w logu.
2. **Nigdy nie blokuje.** `offer()` na ograniczoną kolejkę — żadnego I/O, żadnej
   transakcji na wątku żądania.
3. **Degraduje się widocznie.** Przepełniona kolejka liczy odrzucenia, a licznik jest
   wystawiony w `GET /overview → pipeline`. Cicha utrata danych w potoku metrycznym to
   sposób, w jaki firma spędza kwartał podejmując decyzje na liczbach, które przestały
   być prawdziwe w drugim tygodniu.

### Bufor ograniczony, nie nieograniczony

Kolejka ma twardy limit. Nieograniczona wymieniłaby „trochę zgubionych metryk" na
`OutOfMemoryError` w CRM-ie — to nie jest wymiana, na którą ktokolwiek by się zgodził,
gdyby zapytać go wprost.

---

## 3. Skąd biorą się liczby — i dlaczego akurat stamtąd

To jest najważniejsza decyzja projektowa w całym module.

| Rodzaj metryki | Źródło | Uzasadnienie |
|---|---|---|
| Rezerwacje, wizyty, klienci, logowania | **tabele źródłowe** (`appointments`, `visits`, `audit_logs`) | dokładne, **backfillowalne od pierwszego dnia**, niezdolne do rozjechania się z tym, co widzi biznes |
| SMS, e-mail | **strumień zdarzeń** | nie zostawiają po sobie żadnego wiersza — nie ma czego policzyć później |
| Czas pracy | **`metric_user_sessions`** | wymaga własnego mechanizmu pomiaru (patrz §4) |
| Ruch API | **bufor → agregat dzienny** | wiersz na żądanie przerósłby każdą tabelę biznesową w tydzień |
| Subskrypcje | **live query + cache 30 s** | wymóg „czas rzeczywisty"; zmienia się kilka razy dziennie |

Dzięki liczeniu rezerwacji z `appointments`, a nie z licznika zdarzeń:

- konsola pokazuje **12 miesięcy historii w dniu wdrożenia**, nie zaczyna od zera;
- nie może powstać sytuacja, w której „liczba rezerwacji" w metrykach i w kalendarzu to
  dwie różne liczby, a nikt nie wie, którą podać klientowi.

Aspekty biznesowe celowo **nie** obejmują funkcji `suspend`. Spring AOP w around-advice
na funkcji zawieszalnej widzi `COROUTINE_SUSPENDED`, a nie wynik — advice zapisałby
„sukces" zanim korutyna się zakończy, łącznie z tymi, które potem rzucą. To dokładnie
to przeliczanie w górę, którego reguła „można nie doliczyć, nie wolno doliczyć za dużo"
zabrania.

---

## 4. Czas pracy w systemie — mechanizm

### Problem

Naiwna implementacja (`ended_at - started_at`) mierzy, jak długo istniała karta
przeglądarki, a nie jak długo ktoś z niej korzystał. W prawdziwym CRM większość kart
otwiera się o 8:00 i zamyka następnego ranka — więc naiwna liczba raportuje każde studio
jako używające produktu 14 godzin dziennie. To najczęstszy sposób, w jaki dashboard
użycia SaaS-a kończy jako pewny siebie i błędny, a myli się w kierunku pochlebnym, co
jest gorsze.

### Rozwiązanie: przyrosty z zaciskiem (clamp)

Czas jest doliczany **wyłącznie przyrostowo i z ograniczeniem**, nigdy jako różnica
dwóch odległych znaczników:

```
doliczone = min(now − lastActivityAt, maxCreditedGapSeconds)   // domyślnie 90 s
```

Przyrost trafia do `active_seconds` tylko wtedy, gdy klient zgłosił, że karta była
widoczna **i** użytkownik wchodził w interakcję; w przeciwnym razie do `idle_seconds`.

Trzy konsekwencje:

- Laptop zamknięty na noc i otwarty po 16 godzinach dolicza **90 sekund, nie 16 godzin**.
- Zgubiony heartbeat (słabe wifi) kosztuje jeden interwał, nie całą sesję.
- Klient **nie może zawyżyć własnych liczb** — czas mierzy serwer swoim zegarem,
  a jedyny wpływ klienta to flaga „czy ten interwał był aktywny".

### Ogon sesji

`SessionSweeper` zamyka milczące sesje **wstecznie, na ostatnim heartbeacie**. Martwy
czas między odejściem użytkownika a zauważeniem tego przez sweeper nie jest liczony.

### Filtr „pustych sesji"

Sesja jest `is_meaningful = true` tylko gdy `active_seconds ≥ 30` **i**
`interaction_count > 0`. Oba warunki są konieczne:

- długa sesja bez interakcji = zapomniana karta,
- sesja z interakcjami ale 3 sekundami czasu = przypadkowe kliknięcie w zakładkę.

Filtr działa **raz**, przy zamknięciu sesji, a nie w każdym zapytaniu, które kiedykolwiek
dotknie tej tabeli.

Konsola raportuje też **odsetek odrzuconych sesji**. To metryka jakości samego pomiaru:
gwałtowna zmiana oznacza, że heartbeat po stronie frontu przestał działać — i wiadomo
o tym od razu, a nie miesiąc później, gdy ktoś zauważy, że użycie „urosło" o 40% bez
żadnego nowego klienta.

### Właściciel vs pracownik

`actor_kind` (OWNER/EMPLOYEE) jest **snapshotowany** na wierszu sesji przy jej otwarciu.
Denormalizacja celowa: pracownik awansowany na właściciela w marcu nie może wstecznie
przepisać lutowego podziału.

### Kontrakt frontendu

```js
await api.post('/api/v1/metrics/session/start', { device, appVersion, route });

setInterval(() => {
  const active = document.visibilityState === 'visible'
              && Date.now() - lastInteractionAt < 120_000;
  api.post('/api/v1/metrics/session/heartbeat', { active, interactions, route });
  interactions = 0;
}, 60_000);

addEventListener('pagehide', () =>
  navigator.sendBeacon('/api/v1/metrics/session/end'));
```

`sendBeacon`, nie `fetch`: przeglądarka gwarantuje dostarczenie beacona przy zamykaniu
strony i anuluje trwające fetche — zwykły fetch gubiłby dokładnie te zdarzenia zamknięcia,
które trzymają liczby w ryzach.

**Frontend jest optymalizacją, nie zależnością.** Backend ma passive touch: każde
uwierzytelnione żądanie przechodzi przez ten sam mechanizm zacisku, więc pomiar działa
nawet gdyby heartbeat nie został wdrożony. Podwójne liczenie jest niemożliwe — kto
pierwszy przesunie `lastActivityAt`, ten drugi widzi zerową różnicę.

---

## 5. Martwe endpointy

### Kluczowa inwersja

Raport zbudowany wyłącznie z zaobserwowanego ruchu **nie może nazwać** endpointu, który
ruchu nie ma — a to właśnie te endpointy chcemy usunąć. Dlatego:

`EndpointCatalogRegistrar` przy każdym starcie odpytuje `RequestMappingHandlerMapping`
i zapisuje **wszystkie trasy istniejące w kodzie**. Raport to `LEFT JOIN` z katalogu na
ruch — pytanie brzmi „co istnieje i nie było wołane", a nie „co było wołane".

Pętla zamyka się w drugą stronę: endpoint usunięty ze źródeł nie pojawia się w mappingu,
więc jego wiersz dostaje `is_active_in_code = false` i wypada z raportu, zamiast straszyć
w nim w nieskończoność.

### Uczciwość okna obserwacji

Nic nie jest nazywane martwym, dopóki audyt nie zbiera danych przez `min-observation-days`
(domyślnie 30). Endpoint raportu kwartalnego wygląda identycznie jak martwy po trzech
dniach danych, a usunięcie go byłoby awarią produkcyjną — słusznie przypisaną temu
raportowi. Przed upływem okna wszystko raportuje się jako `INSUFFICIENT_DATA`.

### Klasyfikacja

| Stan | Warunek | Rekomendacja |
|---|---|---|
| `NEVER_CALLED` | nigdy od startu audytu | najsilniejszy kandydat do usunięcia |
| `DEAD` | cisza > 90 dni | kandydat do usunięcia |
| `DORMANT` | cisza > 30 dni | sprawdzić, czy funkcja jest jeszcze w UI |
| `LOW_TRAFFIC` | < 10 wywołań / 30 dni | zostawić, obserwować |
| `ACTIVE` | normalny ruch | używany |
| `INSUFFICIENT_DATA` | okno obserwacji za krótkie | za wcześnie na ocenę |

`NEVER_CALLED` jest celowo osobną kategorią od `DEAD` — to różnica między
„prawdopodobnie bezpiecznie usunąć" a „na pewno".

Kolumna `distinct_studios` odróżnia „skrypt integracyjny jednego studia" od „funkcji,
na której opiera się cała baza klientów". W surowej liczbie wywołań wyglądają identycznie.

`PATCH /api-audit/{id}/exempt` pozwala oznaczyć endpoint jako świadomie
niskoruchowy. Bez tego te same dwanaście fałszywych alarmów wraca w każdym raporcie,
aż ludzie przestaną go czytać — klasyczny los narzędzi tego typu.

---

## 6. Śledzenie błędów z przypisaniem do tenanta

Każde wystąpienie niesie `studio_id`. `NULL` wyłącznie tam, gdzie tenant jeszcze nie
istnieje (nieudane logowanie, webhook z błędnym podpisem).

### Fingerprinting

„4 812 błędów wczoraj" to nie jest informacja, na której da się działać. „Dziewięć
defektów, ten dotknął 23 studiów" — jest. Cała różnica to fingerprint, a cała trudność
to jego stabilność:

- **za szczegółowy** (hash surowej wiadomości) → grupa na każde wystąpienie, bo wiadomość
  zawiera identyfikator: `Nie znaleziono wizyty 8f3c…` tworzy grupę na każdą wizytę,
  a konsola jest bezużyteczna w jeden dzień;
- **za ogólny** (hash samej klasy wyjątku) → każdy `IllegalStateException` w kodzie ląduje
  w jednej grupie, więc naprawa jednego „rozwiązuje" trzydzieści innych.

Kompromis: klasa wyjątku + wiadomość ze zamaskowanymi częściami zmiennymi (UUID, liczby,
daty, e-maile) + kilka górnych ramek stosu **należących do naszego pakietu**. Ramki
frameworka są odrzucane — są identyczne dla niepowiązanych defektów.

### Trzy tabele, trzy role

- `metric_error_events` — wystąpienia. Retencja 90 dni.
- `metric_error_groups` — defekty. **Nigdy nie czyszczone automatycznie**, mają status
  (NEW / ACKNOWLEDGED / RESOLVED / IGNORED) i wykrywają regresje: defekt, który wraca po
  oznaczeniu jako naprawiony, otwiera się ponownie zamiast siedzieć pod zieloną flagą.
- `metric_error_group_impacts` — kto oberwał. Upsertowane, **przeżywa czyszczenie
  surowych wierszy**: „ten defekt po raz pierwszy dotknął tego klienta w marcu" to fakt,
  który liczy się w eskalacji długo po tym, jak stack trace przestał być użyteczny.

### Punkty przechwytywania

| Źródło | Mechanizm | Uwaga |
|---|---|---|
| Kontrolery | `@AfterThrowing` na `@RestController` | nie dotyka `GlobalExceptionHandler` — śledzenie i prezentacja błędu pozostają niezależne |
| Zadania `@Scheduled` | `@Around` | ślepa plamka każdego setupu opartego o HTTP: nikt nie patrzy, nic nie wraca, Spring loguje i idzie dalej |
| Frontend | `POST /api/v1/metrics/errors` | tenant **z sesji, nigdy z body** |

Wyjątki biznesowe (walidacja, 404, brak uprawnień) **nie są zapisywane**. To normalne
zachowanie aplikacji, a nie defekty — zapisywanie ich pogrzebałoby dziewięć prawdziwych
defektów pod pięćdziesięcioma tysiącami wierszy „użytkownik wpisał zły NIP" i nauczyło
wszystkich ignorować konsolę.

Frontendowe zgłoszenia są rate-limitowane per sesja: pętla renderowania potrafi wygenerować
tysiące identycznych błędów na minutę, a dziesiąte zgłoszenie tego samego defektu nie
wnosi nic ponad pierwsze.

`correlation_id` łączy błąd frontu z żądaniem backendu, które go wywołało — CRM już
zwraca `X-Correlation-ID` na każdej odpowiedzi.

---

## 7. Metryki zaproponowane dodatkowo

Wymagania 1–6 opisują, *co się dzieje*. Poniższe odpowiadają na pytanie, które faktycznie
zadaje sobie dostawca CRM-a: *którego klienta stracimy i dlaczego*.

### 7.1 Health score i ryzyko churnu

Churn w SaaS-ie dla małych firm prawie nigdy nie jest ogłaszany. Studio nie pisze
wypowiedzenia — po cichu przestaje się logować, płaci jeszcze dwa miesiące z rozpędu,
a potem kwestionuje odnowienie. Każde takie zakończenie jest widoczne w danych użycia
**tygodnie przed** tym, jak dotrze do billingu.

| Sygnał | Waga | Co wyłapuje |
|---|---:|---|
| Świeżość użycia | 30 | najsilniejszy predyktor — nikt nie odchodzi logując się codziennie |
| Trend zaangażowania (14 dni vs poprzednie 14) | 25 | spadek **zanim** stanie się nieobecnością — to jest okno na reakcję |
| Wynik biznesowy (rezerwacje) | 20 | odróżnia „loguje się" od „prowadzi tu firmę"; CRM bez rezerwacji to książka adresowa |
| Wykorzystanie kont | 15 | płacenie za sześć kont przy dwóch używanych poprzedza downgrade |
| Ekspozycja na błędy | 10 | nasza wina i jedyny czynnik z tej listy, który kontrolujemy bezpośrednio |

Wagi to świadomy, recenzowalny osąd produktowy, **nie model dopasowany do danych**: przy
tej wielkości bazy klientów nie ma dość zdarzeń churnu, żeby cokolwiek sensownie dopasować,
a przejrzysta rubryka, z którą można się spierać, bije czarną skrzynkę, której nikt nie ufa.
Gdy uzbiera się dość zdarzeń, uczciwym ulepszeniem jest regresja tych samych cech na
faktyczne wyniki — wejścia są już zapisywane per dzień.

Konto młodsze niż 14 dni dostaje wynik neutralny, nie krytyczny. Oznaczanie każdej nowej
rejestracji jako zagrożonej to sposób, w jaki raport retencyjny zostaje zignorowany.

Board sortuje po ryzyku, **a potem po zagrożonym przychodzie** — bo alokuje czas opiekuna
klienta, a konto próbne za 39 zł nad studiem za 400 zł w spadku odwraca ten priorytet.

Każdy wiersz niesie **konkretne powody** w języku naturalnym: „brak logowania od 12 dni",
„spadek aktywności o 68%", „zero rezerwacji w 2 tygodnie". To różnica między dashboardem,
na który się patrzy, a takim, na podstawie którego się działa.

### 7.2 Adopcja modułów

Ruch API zgrupowany po pionowym module (`visit`, `finance`, `instagram`…), skrzyżowany
z tym, za co studio płaci. Handlowo istotny wiersz to **`paidFor = true, calls = 0`**:
klient płaci za dodatek, którego nigdy nie otwiera. To jednocześnie predyktor churnu i —
dobrze obsłużone — rozmowa retencyjna. Nikt nie odnawia pozycji na fakturze, której nie
pamięta, że używał.

Nazwa modułu pochodzi z pakietu, więc nowy pion pojawia się w raporcie w dniu wdrożenia —
nie ma rejestru, który trzeba pamiętać zaktualizować, a to dokładnie ten krok utrzymaniowy,
który zostaje pominięty i po cichu psuje raport.

### 7.3 Time-to-value (aktywacja)

Kamienie milowe od rejestracji: pierwsze logowanie → pierwszy klient → pierwsza rezerwacja
→ **pierwsza zakończona wizyta**. Studio, które dwa tygodnie po rejestracji nie stworzyło
pierwszej wizyty, nie odnowi — a tu widać to, gdy onboarding może jeszcze zainterweniować.

`fullyActivated` oznacza przepuszczenie **całego zlecenia** przez produkt, od początku do
końca. Cokolwiek mniej to trial, który jeszcze niczego nie udowodnił.

Liczone z tabel biznesowych, więc działa dla każdego studia, które kiedykolwiek się
zarejestrowało — także tych sprzed istnienia tego modułu.

### 7.4 DAU / WAU / MAU i stickiness

Stickiness = DAU/MAU. Klasyczny wskaźnik zaangażowania: jaka część bazy, która pojawia
się w miesiącu, pojawia się danego dnia. Liczony na poziomie **studiów**, nie użytkowników
— w B2B jednostką decyzji o odnowieniu jest firma.

### 7.5 Wypalanie kredytów SMS

Dni do wyczerpania przy obecnym tempie. Najbardziej wiarygodny trigger upsellowy, jaki ma
platforma: studio zbliżające się do zera to studio, któremu za chwilę po cichu przestaną
działać automatyzacje. `null` gdy studio nie wysyła — plakietka „0 dni" na kliencie, który
nigdy nie wysłał SMS-a, to fałszywy alarm, a konsola, która krzyczy „wilk", zostaje wyciszona.

### 7.6 Wydajność per tenant

Kiedy studio mówi „u nas system działa wolno", jedyne uczciwe odpowiedzi to „zmierzyliśmy"
albo „nie mamy pojęcia". Globalny p95 nie odróżni jednego klienta na słabym łączu od
prawdziwej regresji, bo jego ruch jest błędem zaokrąglenia w globalnym histogramie.
`metric_studio_api_daily` czyni to twierdzenie sprawdzalnym per klient.

### 7.7 MRR / ARPA / mix pakietów

Przychód rozpoznawany **wyłącznie** dla kont faktycznie płacących. Trial to nie przychód,
a snapshot, który liczy go jako przychód, to najszybszy sposób na deck zarządczy mylący
się o 30%.

### 7.8 Zdrowie samego potoku metryk

`GET /overview → pipeline` raportuje głębokość kolejki, odrzucone zdarzenia i saturację.
Dashboard, który nie potrafi zaraportować własnej utraty danych, to sposób, w jaki firma
spędza kwartał działając pewnie na liczbach, które przestały być kompletne w drugim tygodniu.

---

## 8. Read model i idempotencja

Każdy ekran konsoli czyta `metric_daily_*_snapshots` i **tylko je**. Dashboard agregujący
surowe strumienie przy każdym otwarciu jest szybki w dniu premiery i nie do użycia
w drugim kwartale, bo jego koszt rośnie z pokrywaną historią. Koszt snapshotu jest stały:
„użycie per studio za 12 miesięcy" to skan 365 wierszy po indeksie, niezależnie od tego,
czy platforma ma dwa miesiące czy pięć lat.

Snapshot przechowuje pakiet i status **takie, jakie były tego dnia** — studio robiące
upgrade w czerwcu nie przepisuje własnej historii ze stycznia, jak zrobiłby to JOIN na żywo.

Job jest **idempotentny przez konstrukcję**: `INSERT … ON CONFLICT (studio_id, snapshot_date)
DO UPDATE`, gdzie każda wartość jest przeliczana od zera, a nie inkrementowana. Ponowne
uruchomienie dla tego samego dnia — po awarii, po poprawce, po ręcznym backfillu — daje ten
sam wiersz. Job inkrementujący podwajałby liczby przy każdym powtórzeniu, a uszkodzenie
byłoby ciche i trwałe.

Harmonogram: agregacja studiów 03:10 → agregacja platformy 03:25 → retencja 03:40.
Kolejność ma znaczenie: odwrócona kasowałaby surowe dane dnia, zanim ten dzień zostałby
zagregowany, a strata byłaby trwała i niewidoczna do momentu otwarcia wykresu za miesiąc.

`POST /api/internal/metrics/recompute?date=YYYY-MM-DD` przelicza dzień na żądanie.

---

## 9. Bezpieczeństwo

Konsola platformy jest **jedyną powierzchnią w systemie, która celowo czyta między
tenantami**. Nie korzysta z tożsamości studia: wymyślenie super-użytkownika *wewnątrz*
tabeli `users` postawiłoby każde studio o jeden błąd autoryzacji od odczytania przychodów
konkurencji.

- Nagłówek `X-Platform-Key`, porównywany w czasie stałym (przez hash, nie `String.equals`,
  który zwraca na pierwszym różniącym się bajcie i wycieka sekret znak po znaku).
- **Fail-closed**: brak skonfigurowanego klucza → HTTP 503 dla każdego wywołania.
  Konsola analityczna nigdy nie jest warta domyślnego „otwarte".
- Ścieżka `/api/internal/**`, żeby granica była widoczna w logach dostępu i w każdej
  regule reverse proxy, którą ktoś napisze później. Zakładane dodatkowe ograniczenie
  VPN / IP allow-list.
- Identyfikatory sesji są **hashowane** (SHA-256), nigdy nie przechowywane surowo: dump
  metryk ani zrzut ekranu ze wsparcia nie może wręczyć nikomu żywego ciasteczka sesji
  klienta.
- Bramka subskrypcji nie obejmuje `/api/v1/metrics/**` — telemetria musi działać także
  wtedy, gdy studio jest zablokowane paywallem, czyli dokładnie wtedy, gdy klient ma
  doświadczenie najbardziej warte zmierzenia.

---

## 10. Retencja

| Dane | Retencja | Uzasadnienie |
|---|---|---|
| `metric_events` | 120 dni | zagregowane w snapshotach |
| `metric_user_sessions` | 400 dni | pozwala na porównania rok do roku |
| `metric_error_events` | 90 dni | surowe wystąpienia |
| ruch API (dzienny) | 400 dni | — |
| **snapshoty dzienne** | **bezterminowo** | to **jest** historia |
| **grupy błędów + impacts** | **bezterminowo** | „ten defekt dotknął tego klienta w marcu" liczy się w eskalacji |

Moduł metryk bez retencji to awaria w zwolnionym tempie: surowe tabele rosną bez
ograniczeń, backupy się wydłużają, aż dane analityczne, na które biznes ledwo patrzy,
stają się największą rzeczą w bazie trzymającej faktury jego klientów.

---

## 11. API

### Tenant (sesja studia)

```
POST /api/v1/metrics/session/start      { device, appVersion, route }
POST /api/v1/metrics/session/heartbeat  { active, interactions, route }
POST /api/v1/metrics/session/end        (sendBeacon, 204)
POST /api/v1/metrics/errors             { name, message, stack, route, severity, correlationId }
```

### Platforma (`X-Platform-Key`)

```
GET   /api/internal/metrics/overview              # wymóg 1 — subskrypcje live + reszta
GET   /api/internal/metrics/trend?from=&to=       # szeregi czasowe
GET   /api/internal/metrics/sessions?from=&to=    # wymóg 2 — czas, OWNER vs EMPLOYEE
GET   /api/internal/metrics/api-audit             # wymóg 3 — martwe endpointy
PATCH /api/internal/metrics/api-audit/{id}/exempt
GET   /api/internal/metrics/tenants/{studioId}    # wymogi 4, 5 + adopcja, aktywacja
GET   /api/internal/metrics/errors                # wymóg 6 — grupy defektów
GET   /api/internal/metrics/errors/{fingerprint}
PATCH /api/internal/metrics/errors/{fingerprint}  # triage: ACKNOWLEDGED / RESOLVED / IGNORED
GET   /api/internal/metrics/tenants/{id}/errors   # „klient dzwoni — co go dotknęło?"
GET   /api/internal/metrics/health?risk=AT_RISK   # board retencyjny
POST  /api/internal/metrics/recompute?date=
```

### Prometheus (nowe serie)

```
crm_subscriptions_by_plan{plan="FULL",status="ACTIVE"}
crm_subscriptions_by_status{status="TRIALING"}
crm_subscriptions_add_ons_active{add_on="FINANCE_MODULE"}
crm_subscriptions_paying_total
crm_subscriptions_mrr_gross_cents
crm_subscriptions_arpa_gross_cents
crm_subscriptions_without_plan_total
```

`MultiGauge`, nie gauge na pakiet: pakiety i dodatki to wiersze w bazie, więc zbiór
etykiet nie jest znany w czasie kompilacji. `MultiGauge` przerejestrowuje całą rodzinę
serii przy odświeżeniu, co poprawnie **usuwa** serię pakietu, na którym nikt już nie
siedzi — osobno rejestrowane gauge'e raportowałyby w nieskończoność ostatnią wartość,
a nieaktualny niezerowy gauge jest gorszy niż jego brak.

### Prometheus — limity KSeF per najemca

```
crm_ksef_api_requests_total{studio_id="…",ksef_operation="get_invoice",result="success"}
crm_ksef_api_window_requests{studio_id="…",ksef_window="hour"}
crm_ksef_api_window_utilization{studio_id="…",ksef_window="hour"}
crm_ksef_api_deferred_total{studio_id="…",reason="xml_budget"}
```

Jedyne serie w tym module z etykietą `studio_id` — i to nie jest wyłom w zasadzie
z rozdziału 1, tylko jej zastosowanie. Zakazana była kombinacja `studio_id` × *setki
endpointów CRM*; tu drugim wymiarem jest kilkanaście metod klienta KSeF, a pytanie
„kto za chwilę zobaczy 429" jest z definicji pytaniem o **teraz** i musi dać się oprzeć
na nim regułę alertu. Postgres, który dostał resztę modułu, odpowiedziałby na nie
z opóźnieniem doby.

Punkt pomiaru to dekorator klienta KSeF (`MeteredKsefClient`), a nie poszczególne
wywołania: przez ten interfejs przechodzi każde żądanie do KSeF, więc operacja dodana
w przyszłości jest mierzona od pierwszego użycia. Najemcę niesie wątkowy
`KsefTenantContext` — metody SDK przyjmują token dostępu, nie identyfikator studia.

Okna są przesuwane (kolejka znaczników czasu na studio), bo limity KSeF też takie są:
„ile w ostatniej godzinie" musi dać się policzyć w dowolnej chwili, a nie tylko na
granicy pełnej godziny. Studio, które przez godzinę nic nie wysłało, wypada z map
i z serii — wskaźnik zatrzymany na ostatniej wartości kłamałby o obciążeniu.

---

## 12. Konfiguracja

Wszystko w `application.properties` pod `crm.metrics.*`. Progi sesji to **decyzje
produktowe, nie szczegóły implementacyjne** — definiują, co firma rozumie przez „użytkownik
spędził dziś 40 minut w CRM", a ich zmiana zmienia każde porównanie historyczne. Dlatego
siedzą w konfiguracji, recenzowane, a nie jako magiczne liczby rozsiane po trzech klasach.

```properties
crm.metrics.platform-api-key=${PLATFORM_METRICS_KEY:}   # puste = konsola zamknięta
crm.metrics.session.max-credited-gap-seconds=90         # zacisk — mechanizm anty-„pusta sesja"
crm.metrics.session.timeout-seconds=300
crm.metrics.session.min-meaningful-seconds=30
crm.metrics.api-audit.min-observation-days=30           # uczciwość raportu martwych endpointów
crm.metrics.api-audit.dead-after-days=90
```

---

## 13. Uruchomienie

1. Ustawić `PLATFORM_METRICS_KEY` w środowisku (bez tego konsola zwraca 503).
2. Zastosować `V65__metrics_module.sql`. Migracja jest idempotentna i seeduje puste
   snapshoty za 90 dni wstecz, więc konsola pokazuje realne trendy od pierwszego dnia.
3. Wystartować aplikację — `EndpointCatalogRegistrar` zbuduje katalog endpointów.
4. `POST /api/internal/metrics/recompute?date=…` dla dni wstecz (rezerwacje, wizyty
   i logowania wypełnią się z tabel źródłowych; czas sesji i błędy słusznie zaczynają
   od zera — nic ich wcześniej nie mierzyło, a wymyślanie wartości byłoby gorsze niż
   widoczna luka).
5. Wdrożyć heartbeat i reporter błędów po stronie frontu (§4, §6).
6. Raport martwych endpointów traktować jako wiążący dopiero po 30 dniach zbierania.
