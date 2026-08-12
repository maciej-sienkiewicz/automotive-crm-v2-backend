# Przebudowa modułu monitoringu konkurencji na Instagramie

**Dokument koncepcyjny i plan refaktoryzacji (v1)**
Data: 2026-08-12 · Status: do dyskusji · Zakres: `automotive-crm-v2-backend` (moduł `instagram/`) + `detailing-crm-v2` (moduł `competition-monitoring/`)

> Zastrzeżenie: rozdział prawny to analiza ryzyk przygotowana z perspektywy inżynieryjno-produktowej, nie porada prawna. Przed wdrożeniem rekomendujemy weryfikację kluczowych punktów (RODO, prawo autorskie) z prawnikiem.

---

## 0. Streszczenie wykonawcze

**Problem:** moduł technicznie działa (zbiera posty, obserwujących i aktywność story pięciu konkurentów), ale prezentuje surowe szeregi czasowe: 4 zakładki wykresów, tabela rankingowa, macierz SEO, lista postów z pełnymi caption. Właściciel studia detailingowego musi sam być analitykiem, żeby cokolwiek z tego wyciągnąć. To jest dokładna odwrotność wartości, za którą płaci (dodatek 32 zł/mies.).

**Kierunek przebudowy:** odwracamy piramidę informacyjną. System ma odpowiadać wprost na trzy pytania właściciela:

1. **Co konkurencja zrobiła w tym tygodniu?** (brief, nie wykres)
2. **Co u nich działa i dlaczego?** (top treści + klasyfikacja, nie lista caption)
3. **Co ja powinienem z tym zrobić?** (rekomendacja, nie tabela)

Surowe dane nie znikają — spadają o dwa poziomy niżej, jako materiał dowodowy („evidence") pod każdym wnioskiem.

**Decyzje architektoniczne (propozycja):**

- **Źródło danych zostaje na RapidAPI** (decyzja produktowa z 2026-08). Oficjalne Graph API **nie jest pełną alternatywą** — nie daje stories, nie działa dla kont osobistych, wymaga od każdego tenanta konta professional + strony FB + przejścia App Review. Wprowadzamy natomiast **warstwę abstrakcji źródła danych** (`InstagramDataSource`), żeby: (a) móc podpiąć drugiego dostawcę scrapingu jako fallback, (b) opcjonalnie dodać oficjalne API jako **drugą, równoległą warstwę** tam, gdzie realnie coś wnosi (benchmark własnego konta, legalne osadzanie zdjęć przez oEmbed, share-of-voice na hashtagach).
- **Compliance budujemy wokół prawa powszechnego** (RODO, prawo autorskie, uczciwa konkurencja), a nie regulaminu Mety. Niezgodność z TOS Mety traktujemy jako **ryzyko vendorowe** (dostawca scrapera może zostać odcięty) i zarządzamy nim architektonicznie, nie przez rezygnację z funkcji.
- **Wnioski generuje silnik reguł na metrykach pochodnych**, a LLM służy wyłącznie do klasyfikacji treści i ubierania gotowych, policzalnych faktów w język — nigdy do „wymyślania" wniosków z surowych danych.

---

## 1. Analiza możliwości oficjalnych API Instagrama

### 1.1. Krajobraz API Meta (stan na 2026)

| API | Status | Do czego służy |
|---|---|---|
| **Instagram Basic Display API** | **Wyłączone** (4 grudnia 2024) | Historyczne — odczyt własnego konta osobistego. Nie istnieje. |
| **Instagram API with Instagram Login** | Aktywne | Następca Basic Display. Własne konto professional (Business/Creator): publikacja treści, komentarze, wiadomości, własne insights. **Bez** Business Discovery i Hashtag Search. |
| **Instagram API with Facebook Login** („Graph API") | Aktywne | Pełny wariant: wymaga konta professional powiązanego ze stroną FB. Jedyny wariant z **Business Discovery**, **Hashtag Search**, mentions i pełnymi insights. |
| **oEmbed** | Aktywne | Osadzanie publicznych postów po permalinku (token aplikacji, prosty review). |

Wniosek nr 1: **jedyną oficjalną furtką do danych konkurencji jest Business Discovery + Hashtag Search w wariancie z logowaniem przez Facebooka.**

### 1.2. Business Discovery — co realnie daje

Endpoint: `GET /{ig-user-id}?fields=business_discovery.username(nazwa_konkurenta){...}` — odpytujemy cudze konto *przez* własne konto professional.

**Dostępne pola profilu:** `username`, `name`, `biography`, `website`, `followers_count`, `follows_count`, `media_count`, `profile_picture_url`.

**Dostępne pola mediów (edge `media`, z paginacją):** `id`, `caption`, `media_type` (IMAGE/VIDEO/CAROUSEL_ALBUM), `media_product_type` (FEED/REELS), `like_count`, `comments_count`, `permalink`, `timestamp`, `children`, `media_url` (linki CDN wygasają — do prezentacji i tak lepszy oEmbed).

**Twarde ograniczenia:**

- Działa **tylko dla kont Business/Creator**. Konto osobiste lub prywatne → błąd. W branży detailingowej większość liczących się studiów ma konta firmowe, ale nie wszystkie.
- **Zero stories**, zero insights cudzego konta (reach, impressions, zapisy), zero listy obserwujących, brak liczby wyświetleń Reels konkurenta.
- `like_count` znika, jeśli właściciel ukrył licznik polubień.
- Brak historii obserwujących — trend followers trzeba budować samemu z codziennych snapshotów (robimy to już dziś — `instagram_profile_metrics_snapshots`).
- Rate limity per token użytkownika (rząd ~200 wywołań/h) — przy naszej skali (kilkudziesięciu konkurentów, sync dzienny) niegroźne.

**Warunki wejścia (istotny koszt organizacyjny):**

- Każdy **tenant** musiałby połączyć własne konto IG professional + stronę FB przez OAuth (dziś moduł w ogóle nie ma OAuth — sync jest globalny, dane współdzielone między studiami).
- Nasza aplikacja musi przejść **Meta App Review** (uprawnienia `instagram_basic`, `instagram_manage_insights`, `pages_show_list`/`pages_read_engagement`) oraz **weryfikację firmy** — realnie tygodnie oczekiwania i ryzyko odrzucenia.

### 1.3. Pozostałe oficjalne możliwości

- **Hashtag Search** (`ig_hashtag_search` → `top_media` / `recent_media`): publiczne posty otagowane hashtagiem, **limit 30 unikalnych hashtagów na 7 dni** na konto odpytujące. Zwraca metryki i treść posta, ale **bez autora** (`username` niedostępny — celowa anonimizacja). Idealne do „share of voice" na hashtagach lokalnych (`#detailingpoznan`, `#ppfwarszawa`), bezużyteczne do śledzenia konkretnego profilu.
- **Mentions / tags**: webhooki o wzmiankach **własnej** marki (komentarze, oznaczenia w caption). Świetny materiał na przyszły moduł „monitoring własnej marki", nie na moduł konkurencji.
- **Insights własnego konta**: reach, `profile_views`, `accounts_engaged`, demografia obserwujących (miasta, wiek, płeć). Kluczowy element funkcji **„Ty vs rynek"** — po stronie konkurencji mamy tylko dane publiczne, po własnej stronie możemy mieć pełną głębię.
- **oEmbed**: legalne, oficjalne osadzanie publicznego posta po permalinku — treść jest serwowana przez Instagram, my nie kopiujemy ani nie hostujemy zdjęcia. **Rozwiązuje nasz realny bug**: dziś przechowujemy hotlinki `image_url` do CDN Instagrama, które wygasają między synchronizacjami i frontend renderuje puste obrazki.

### 1.4. Porównanie: co mamy dziś (RapidAPI) vs co dałoby Graph API

| Dane | RapidAPI (dziś) | Business Discovery |
|---|---|---|
| Posty konkurenta: caption, likes, komentarze, data, typ | ✅ (pełna historia, paginacja) | ✅ (paginacja media) |
| Liczba wyświetleń wideo/Reels konkurenta | ✅ `view_count` | ❌ |
| Stories konkurenta (aktywność) | ✅ | ❌ |
| Followers/following/media count | ✅ | ✅ |
| Bio, link, kategoria, typ konta, weryfikacja | ✅ | częściowo (bez kategorii i flag) |
| Konta osobiste / niepowiązane z FB | ✅ | ❌ |
| E-mail/telefon publiczny konkurenta | ✅ (pobierany) | ❌ |
| Wymaga OAuth tenanta + App Review | ❌ | ✅ |
| Zgodne z TOS Mety | ❌ | ✅ |

**Wniosek nr 2 (rekomendacja):** zostajemy przy RapidAPI jako **źródle podstawowym** (jedyne pokrywające pełen zakres funkcjonalny), a wariant oficjalny traktujemy jako **opcjonalną warstwę wzbogacającą** w późnej fazie: oEmbed do prezentacji zdjęć (niski koszt, szybki zysk), własne insights do benchmarku, hashtag search do share-of-voice. Obie warstwy chowamy za wspólnym interfejsem `InstagramDataSource`.

---

## 2. Ramy prawne (prawo powszechne, nie regulamin platformy)

Założenie produktowe: dbamy o zgodność z **prawem** (RODO, prawo autorskie, zwalczanie nieuczciwej konkurencji), a niezgodność z regulaminem Mety akceptujemy świadomie jako ryzyko biznesowe/vendorowe (rozdz. 6).

### 2.1. RODO — dane publiczne to nadal dane osobowe

Wielu konkurentów to jednoosobowe działalności — `username`, bio, treści postów to dane osobowe niezależnie od tego, że są publiczne. Co porządkujemy:

- **Podstawa prawna:** art. 6 ust. 1 lit. f (uzasadniony interes — analiza rynku/monitoring mediów, praktyka powszechnie uznana). Wymaga spisanego testu równowagi (LIA) i wpisu do rejestru czynności przetwarzania.
- **Minimalizacja:** klient RapidAPI pobiera dziś `public_email` i `public_phone_number` (używane tylko do flagi `hasContactData`, niepersystowane). **Przestać je pobierać w ogóle** — do flagi wystarczy sama informacja o ich istnieniu, a najbezpieczniej z niej zrezygnować.
- **Retencja:** dziś snapshoty żyją wiecznie. Wprowadzamy politykę: dane postów i metryk **max 24 miesiące**, automatyczny job czyszczący; po usunięciu profilu przez ostatniego subskrybenta — twarde kasowanie (już jest, zostaje).
- **Prawa podmiotów:** procedura na sprzeciw/żądanie usunięcia → **blocklista** username'ów wyłączonych z monitoringu na stałe.
- **Konta prywatne:** pole `is_private` jest dziś zapisywane, ale **nigdzie nie egzekwowane**. Twarda reguła w kodzie: konto prywatne → stop sync, ukrycie danych. Publiczność danych jest filarem testu równowagi — bez tego LIA się sypie.
- **Powierzenie do OpenAI:** caption konkurentów lecą do klasyfikacji/generacji. Wymagany DPA z OpenAI (standardowo dostępny), zero danych kontaktowych w promptach.

### 2.2. Prawo autorskie i bazy danych

- **Caption to utwory.** Przechowywanie i analiza (klasyfikacja, statystyki) mieści się w wyjątku **text-and-data-mining** (art. 4 dyrektywy DSM 2019/790, wdrożonym do prawa polskiego) — z zastrzeżeniem, że uprawniony może zastrzec TDM; to jest punkt do opinii prawnej, bo TOS platformy bywa interpretowany jako takie zastrzeżenie.
- **Prezentacja treści:** pokazujemy metryki + **fragment** caption (cytat w celu analizy) + **link do oryginału** (`permalink` — mamy `post_code`, dziś nawet nierenderowany!). Nie kopiujemy i nie hostujemy zdjęć — prezentacja wizualna wyłącznie przez **oEmbed** albo link. To zdejmuje główne ryzyko autorskie.
- **Generator AI:** obecny RAG używa caption konkurencji jako few-shot. Ryzyko: wygenerowanie tekstu łudząco podobnego do cudzego (plagiat = ryzyko autorskie i wizerunkowe). Guardrail: prompt zakazujący kopiowania sformułowań + **similarity-check** wygenerowanego tekstu względem materiału inspiracyjnego przed zwróceniem użytkownikowi.
- **Baza danych sui generis (Meta):** masowa ekstrakcja może naruszać prawa producenta bazy. Nasza skala (kilkadziesiąt profili, kilkaset postów/tydzień) jest nieistotna ilościowo, ale to kolejny powód, by nie hostować kopii treści, tylko metryki i odnośniki.

### 2.3. Treści ulotne (stories) — utrzymujemy zakaz produktowy

Zasada produktu pozostaje: **nie przechowujemy i nie prezentujemy treści ulotnych ani prywatnych**. Dziś trzymamy `story_id + taken_at` per story, bezterminowo — to bezterminowy log zachowań wokół treści 24-godzinnych. Zmiana: przy synchronizacji zapisujemy **wyłącznie zagregowany licznik dzienny** (`data, liczba_stories`), bez identyfikatorów pojedynczych stories; tabela `instagram_story_snapshots` do wygaszenia. Funkcjonalnie nic nie tracimy — wykresy i tak operują na licznikach.

### 2.4. Higiena bezpieczeństwa (znaleziska przy okazji — do naprawy niezależnie od wszystkiego)

- **Żywy klucz RapidAPI zacommitowany** jako default w `application.properties` (`instagram.rapidapi.key=${RAPIDAPI_KEY:908...}`) — natychmiastowa rotacja klucza + usunięcie defaulta (analogicznie hasło SMTP w tym samym pliku).
- Endpointy deweloperskie `POST /api/v1/instagram/ai/ab-test` i `/negative-impact-test` (każde wywołanie = 2–3 generacje OpenAI z hardcodowanych próbek) dostępne produkcyjnie każdemu z uprawnieniem marketingowym — do usunięcia z produkcyjnego routingu.
- Dodatek `INSTAGRAM_MONITORING` (32 zł) jest **zdefiniowany, ale nieegzekwowany na backendzie** — bramkuje tylko frontend. Dodać guard entitlementu na kontrolerach modułu (dziś płacący i niepłacący mają to samo API).

---

## 3. Nowe funkcje i metryki dla branży detailingu

### 3.1. Metryki pochodne (fundament — dziś ich nie liczymy)

Dziś pokazujemy surowe: avg lajki, avg komentarze, posty/tydzień. Wartościowe są dopiero metryki **znormalizowane i porównawcze**:

| Metryka | Definicja | Dlaczego ważna dla detailera |
|---|---|---|
| **ER (engagement rate)** | (likes + komentarze) / followers, mediana z okna | Jedyna uczciwa miara „czy treści działają" — porównywalna między profilem 2k i 40k followers |
| **ER per format** | ER osobno dla Reels / karuzela / zdjęcie | Odpowiada na „czy warto robić Reelsy" — konkretna decyzja produkcyjna |
| **Tempo wzrostu** | Δ followers % tydzień-do-tygodnia (mamy dzienne snapshoty!) | Kto realnie rośnie, a kto ma dużo, ale stoi |
| **Kadencja i konsystencja** | mediana postów/tydz. + odchylenie | Regularność to najtańsza przewaga algorytmiczna |
| **Viral score posta** | likes posta / mediana likes profilu | Wyłapuje wyjątkowe posty niezależnie od wielkości konta |
| **Momentum Score (0–100)** | indeks złożony: wzrost (30%) + zaangażowanie (40%) + aktywność (30%) | Jedna liczba na konkurenta — wejście do rankingu zamiast 8 kolumn |

Zasada: **mediany zamiast średnich** (jeden viral/konkurs nie może zawyżać całego profilu) i **wszystko liczone przy synchronizacji, nie przy żądaniu** (dziś `GetCompetitionSummaryHandler` liczy 251 linii agregacji per request).

### 3.2. Warstwa treściowa — klasyfikacja AI pod detailing

Batch-owa klasyfikacja każdego nowego posta (LLM, raz przy sync, wynik cache'owany w kolumnach):

- **Filar treści:** `before/after` · `realizacja: korekta lakieru` · `realizacja: PPF/folia` · `realizacja: powłoka ceramiczna` · `realizacja: wnętrze` · `edukacja/poradnik` · `promocja/cennik` · `backstage/zespół` · `konkurs` · `opinia klienta` · `inne`
- **Sygnały handlowe:** czy caption zawiera cenę / promocję / CTA „umów się" (+ wyekstrahowana wartość promocji)
- **Segment auta:** marka/klasa pojazdu w treści (premium vs popularne) — proxy pozycjonowania cenowego konkurenta
- **Współprace:** wykrycie oznaczeń influencerów / #reklama

To zamienia listę caption w **mapę strategii contentowej konkurencji**: „X w 70% publikuje before/after PPF na autach premium; ich edukacyjne Reelsy mają 3× wyższy ER niż reszta treści".

### 3.3. Funkcje produktowe (od najwyższego ROI)

1. **Tygodniowy Brief Konkurencyjny** — poniedziałek rano, 5–7 punktów w języku naturalnym + e-mail/push. „Detailing Centrum opublikował post o promocji powłok (-20%), który zebrał 4× więcej reakcji niż ich norma. W Twojej okolicy to drugi cennik obniżony w tym miesiącu."
2. **Radar promocji i cen** — dedykowany strumień postów sklasyfikowanych jako promocja/cennik, z alertem. Dla właściciela studia to najbardziej „actionable" informacja w całym module.
3. **Top posty tygodnia z wyjaśnieniem** — 3 posty o najwyższym viral score, każdy z jednozdaniowym „dlaczego zadziałał" (format + filar + timing) i linkiem do oryginału.
4. **Content gap** — „konkurencja regularnie publikuje treści o PPF, Ty nie opublikowałeś żadnej od 60 dni" (wymaga podpięcia własnego profilu — synergia z warstwą oficjalnego API).
5. **Kalendarz konkurencji** — heatmapa dzień×godzina publikacji z nałożonym ER: kiedy publikować, żeby nie ginąć w szumie.
6. **Share of Voice na hashtagach lokalnych** *(faza opcjonalna, oficjalne Hashtag Search)* — udział studia vs konkurencja w `#detailing<miasto>` w ramach budżetu 30 hashtagów/tydzień.
7. **Benchmark „Ty vs rynek"** *(faza opcjonalna, own insights)* — Twoje reach/ER na tle median konkurencji.
8. **Pętla preferencji (już zaczęta)** — oceny „Dobry/Słaby" na postach konkurencji zasilają generator AI; do naprawy synchronizacja (dziś oceny żyją w `localStorage` przeglądarki, fire-and-forget do API, bez syncu między urządzeniami).

---

## 4. Redukcja szumu informacyjnego

### 4.1. Zasada projektowa: piramida informacyjna

```
 Poziom 1  BRIEF        „5 rzeczy, które musisz wiedzieć w tym tygodniu”   ← nowy default
 Poziom 2  SCORECARDY   Momentum Score + 3 liczby na konkurenta            ← nowy
 Poziom 3  ANALIZA      wykresy i tabele (dzisiejszy stan, odchudzony)     ← drill-down na żądanie
```

Dziś użytkownik ląduje od razu na poziomie 3 (i tylko on istnieje). Po przebudowie każda liczba na poziomie 1–2 jest klikalna i prowadzi do materiału dowodowego na poziomie 3.

### 4.2. Backend: potok Ingest → Derive → Detect → Narrate

```
[1] INGEST    RapidAPI (i przyszłe źródła) → snapshoty raw (bez zmian koncepcyjnych)
[2] DERIVE    agregaty tygodniowe + metryki z 3.1 → tabela instagram_weekly_aggregates
              klasyfikacja treści (3.2) → kolumny na snapshotach postów
[3] DETECT    silnik REGUŁ (deterministyczny) → encja Insight
[4] NARRATE   LLM ubiera strukturalne insighty w polszczyznę briefu (nic nie „odkrywa”)
```

**Encja `Insight`:** `{ id, type, severity (INFO/NOTABLE/ALERT), okres, profileIds[], headline, evidence (metryki + linki do postów), recommendation?, status (NEW/READ/DISMISSED) }`.

**Przykładowe reguły (progi konfigurowane, start konserwatywny):**

| Reguła | Warunek | Insight |
|---|---|---|
| Viral post | likes > 3× mediana profilu | „Post X przebił normę profilu 4,2×” + klasyfikacja dlaczego |
| Skok/odpływ followers | \|Δ dzienne\| z-score > 2 vs 90 dni | „Y zyskał 800 obserwujących w 2 dni (prawdopodobnie konkurs — post z 12.08)” |
| Promocja | klasyfikator: promocja/cennik | wpis do Radaru Promocji + alert |
| Zmiana strategii | udział filaru ±20 p.p. m/m | „Z przestawił się z before/after na Reelsy edukacyjne” |
| Cisza | brak postów > 2× mediana kadencji | „W nie publikuje od 3 tygodni” (sygnał rynkowy) |
| Spadek ER | mediana ER −30% m/m | „treści Q przestały angażować” |

Kluczowe: **LLM nie wykrywa wniosków** — wykrywają reguły na policzalnych metrykach (testowalne, tanie, bez halucynacji). LLM dostaje gotową listę faktów i pisze z nich brief; każde zdanie ma odnośnik do evidence.

### 4.3. Frontend: nowa architektura widoków

Nowa struktura (z prawdziwym routingiem URL — dziś wszystkie taby/filtry to lokalny `useState`, bez deep-linków i działającego „wstecz"):

- `/instagram` → **Brief** (default): karta briefu tygodnia + strumień insightów z akcjami (przeczytane/odrzuć) + Radar Promocji.
- `/instagram/konkurenci` → **Scorecardy**: karta per profil (Momentum Score, trend followers sparkline, ER, kadencja, dominujący filar treści). Zastępuje 8-kolumnową tabelę rankingową jako pierwszy kontakt; pełna tabela zostaje jako widok „szczegóły".
- `/instagram/analiza` → dzisiejsze wykresy, odchudzone: jeden wykres z przełącznikiem metryki zamiast 4 tabów z akapitami instruktażowymi (`ChartHint` do usunięcia — skoro wykres wymaga akapitu tłumaczącego, jak go czytać, to znaczy że wniosek powinien być policzony na backendzie).
- `/instagram/posty` → galeria postów **wszystkich** konkurentów (dziś: modal per profil): filtry po filarze treści/formacie/profilu, sort po viral score, karta = miniatura przez **oEmbed/permalink** (dziś: wygasające hotlinki i brak jakiegokolwiek linku do oryginału), skrócony caption, oceny Dobry/Słaby.
- `/instagram/profile` → zarządzanie (obecna zakładka Profile, bez zmian koncepcyjnych).

Porządki techniczne przy okazji: usunięcie ~1 950 LOC martwych komponentów (`CompetitionRanking`, `CompetitionTable`, `EngagementTrendChart`, `PostVolumeChart`) i ~470 linii nieużywanych mocków; naprawa inwalidacji zapytań (po approve/reject/remove zakładka analityki pokazuje stare dane); reakcje na posty przez react-query z inwalidacją zamiast `localStorage`; usunięcie fałszywego komentarza o filtrowaniu client-side w `instagramApi.ts`.

### 4.4. Przykład transformacji szumu w insight

Dziś: wykres „Obserwujący" z 4 liniami + akapit „Nagłe skoki mogą oznaczać viral, współpracę z influencerem…" — **użytkownik ma sam zauważyć skok i sam zgadnąć przyczynę.**

Po przebudowie, w briefie: *„**Car Art Detailing** zyskał 840 obserwujących w 48 h (norma: ~30/tydz.). W tym samym oknie opublikowali konkurs (post z 10.08, 2 100 polubień — 5× ich mediana). Jeśli rozważasz konkurs, ich mechanika: tag 2 osób + obserwacja."* — z linkiem do posta i do wykresu jako dowodu.

---

## 5. Plan refaktoryzacji

### Faza 0 — Higiena i compliance (≈1 tydzień, bez zmian funkcjonalnych)

1. Rotacja klucza RapidAPI + usunięcie defaultów sekretów z `application.properties`.
2. Kill-switche `instagram.*.sync.enabled` (`@ConditionalOnProperty` — dziś obiecane w KDoc, nieistniejące) + **ShedLock** na schedulerach (dziś przy >1 instancji joby dublują się).
3. Usunięcie pobierania `public_email`/`public_phone_number` z klienta RapidAPI.
4. Stories → wyłącznie dzienne agregaty; migracja danych z `instagram_story_snapshots` do licznika, drop tabeli.
5. Egzekwowanie `is_private` (stop sync + ukrycie danych) i entitlementu `INSTAGRAM_MONITORING` na backendzie.
6. Usunięcie endpointów `ab-test`/`negative-impact-test` z produkcji oraz martwych prototypów z `src/main/resources/test/` (źródła Kotlin pakowane do JAR-a jako zasoby).
7. **Flyway baseline dla tabel modułu** — dziś cały schemat Instagrama powstaje przez `ddl-auto=update`, bez migracji; przed jakąkolwiek przebudową schematu musimy mieć go pod kontrolą wersji.
8. Job retencyjny (24 mies.) + blocklista profili (RODO).

### Faza 1 — Warstwa źródeł danych (≈2 tygodnie)

1. Interfejs `InstagramDataSource` (profil, posty, liczniki stories) + `RapidApiDataSource` jako pierwsza implementacja; usunięcie hardcodowanej allowlisty 5 username'ów z klienta (zastąpienie konfigurowalnym limitem profili per plan).
2. Odporność: retry z backoffem, obsługa 429, budżet wywołań per sync, kolejkowanie profili zamiast `forEach` w pętli; health-check źródła + status widoczny dla admina (ryzyko vendorowe z rozdz. 6 zarządzane operacyjnie).
3. Naprawa `AddInstagramProfileHandler` — dziś dodanie profilu odpala **synchroniczny scrape wszystkich profili w request path**; przejście na zdarzenie + kolejkę.

### Faza 2 — Pipeline metryk i silnik insightów (≈3–4 tygodnie, serce przebudowy)

1. Tabela `instagram_weekly_aggregates` + obliczanie metryk z 3.1 przy synchronizacji; `GET /summary` czyta gotowe agregaty (koniec 251-liniowej agregacji per request).
2. Klasyfikacja treści (3.2): batch przy sync, wynik w kolumnach snapshotu posta; koszt LLM kontrolowany (tylko nowe posty, ~kilkadziesiąt/tydzień).
3. Silnik reguł + encja `Insight` + API `GET/PATCH /api/v1/instagram/insights`.
4. Generacja briefu (LLM na strukturalnych insightach) + wysyłka e-mail (istniejąca infrastruktura mailowa) — feature-flag.
5. Similarity-guardrail w generatorze postów AI.

### Faza 3 — Frontend „brief-first" (≈3–4 tygodnie, równolegle z końcówką fazy 2)

1. Routing URL + szkielet widoków z 4.3; Brief i Scorecardy na nowych endpointach.
2. Galeria postów z oEmbed/permalinkami; naprawa reakcji (backend-first) i inwalidacji zapytań.
3. Wielkie sprzątanie: martwe komponenty, mocki, `ChartHint`y; odchudzenie `/analiza`.

### Faza 4 — Opcjonalna warstwa oficjalna (≈2–3 tygodnie pracy + czas oczekiwania na App Review; decyzja go/no-go po fazie 3)

1. Meta App + OAuth „Połącz konto Instagram" per tenant (szyfrowany magazyn tokenów, odświeżanie).
2. oEmbed do miniatur (dostępny wcześniej — prostszy review, można wciągnąć do fazy 3).
3. Benchmark „Ty vs rynek" (own insights) i Content Gap.
4. Share of Voice na hashtagach (budżeter 30/7 dni per tenant).

**Metryki sukcesu przebudowy:** % tenantów z modułem otwieranym ≥1×/tydzień; czas od wejścia do pierwszej akcji; CTR briefu e-mail; konwersja dodatku 32 zł po wdrożeniu enforcementu (dziś część użytkowników ma moduł de facto za darmo).

---

## 6. Rejestr ryzyk i otwarte decyzje

| Ryzyko | Prawdopod. | Wpływ | Mitygacja |
|---|---|---|---|
| Dostawca `ig-scraper5` odcięty przez Metę / znika z dnia na dzień | średnie–wysokie | moduł ślepnie | interfejs `InstagramDataSource` + drugi dostawca „na ławce"; health-check + degradacja komunikowana w UI; dane historyczne zostają |
| Meta kieruje roszczenia wobec nas (nie dostawcy) za korzystanie ze scrapera | niskie | średni/wysoki | brak hostowania treści (metryki + linki/oEmbed), skala nieistotna, decyzja świadomie zaakceptowana przez biznes — wpis w ADR |
| Żądanie usunięcia danych od konkurenta (RODO) | średnie | niski | blocklista + procedura z rozdz. 2.1 |
| Similarity generatora AI do cudzych caption | średnie | średni | guardrail + similarity-check (faza 2) |
| Koszty LLM rosną z liczbą tenantów | niskie | niski | klasyfikacja globalna per post (nie per tenant), cache, batch |
| App Review odrzucony (faza 4) | średnie | niski | faza 4 jest opcjonalna; produkt kompletny bez niej |

**Do decyzji produktowej przed startem:**

1. Limit obserwowanych profili per plan (proponowane: 5 w cenie dodatku, dokupowanie pakietów) — zastępuje hardcodowaną allowlistę.
2. Czy brief wysyłamy e-mailem od razu (faza 2), czy najpierw tylko in-app?
3. Zakres fazy 4: całość czy tylko oEmbed?
4. Weryfikacja prawna dwóch punktów: TDM/zastrzeżenie (2.2) i treść LIA (2.1).

---

## Załącznik A. Kluczowe znane problemy obecnej implementacji (referencje do kodu)

| Problem | Miejsce |
|---|---|
| Klucz RapidAPI + hasło SMTP zacommitowane jako defaulty | `application.properties:77, ~110` |
| Hardcodowana allowlista 5 username'ów | `RapidApiInstagramClient.kt:225, 323` |
| Pełny sync wszystkich profili w request path przy dodaniu profilu | `InstagramController.kt:52` |
| 251 linii agregacji liczonej per request | `GetCompetitionSummaryHandler.kt` |
| Kill-switche opisane w KDoc, nieistniejące w kodzie | `Instagram*SyncScheduler.kt` |
| Brak locków na schedulerach (dublowanie przy skalowaniu) | `instagram/sync/*` |
| Schemat bez migracji (`ddl-auto=update`, Flyway wyłączony) | `application.properties:13,19` |
| `is_private` zapisywane, nieegzekwowane | `InstagramProfileEntity.kt:88`, serwisy sync |
| Stories: bezterminowy log ID treści 24-godzinnych | `InstagramStorySnapshotEntity.kt` |
| Entitlement 32 zł nieegzekwowany na backendzie | brak guardów w `instagram/` |
| Prototypy Kotlin w `resources/` pakowane do JAR-a | `src/main/resources/test/` |
| ~1 950 LOC martwych komponentów + ~470 linii mocków | `competition-monitoring/components/*`, `api/instagramApi.ts` |
| Reakcje na posty w `localStorage`, fire-and-forget | `PostsModal.tsx:389-453` |
| `postCode` (permalink) pobierany, nigdy nierenderowany | `types.ts`, `PostsModal.tsx` |
| Wygasające hotlinki `image_url` z CDN | `InstagramPostSnapshotEntity.kt:84-90` |
| Brak routingu URL, stan tylko w `useState` | `CompetitionMonitoringView.tsx` |
| Brak inwalidacji summary po akcjach na profilach | `useProfileActions.ts` |
