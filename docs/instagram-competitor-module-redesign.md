# Moduł Instagram / Monitoring Konkurencji — Przebudowa (Projekt)

> Status: propozycja (System Architect + Lead Dev + PM) · Data: 2026-08-12
> Dokument projektowy — implementacja w kolejnym kroku, po akceptacji.
> Zakres: całkowita przebudowa modułu `instagram/` (backend) i `competition-monitoring` (frontend)
> z "surowych danych" na "actionable insights". Warstwa pozyskiwania danych **zostaje na RapidAPI**,
> opcjonalnie rozszerzona o drugiego, oficjalnego dostawcę (Meta Graph API) — patrz §3 i §4.
> Dokument-towarzysz po stronie frontendu: `detailing-crm-v2/docs/INSTAGRAM_REDESIGN_VIEWS.md`.

---

## 1. Cel i teza projektowa

Obecny moduł odpowiada na pytanie *"jakie liczby mają profile konkurencji?"*.
Właściciel studia detailingu nie potrzebuje liczb — potrzebuje odpowiedzi na pięć pytań biznesowych:

1. **Czy odstaję od konkurencji?** (benchmark, pozycja, trend)
2. **Co u konkurencji działa?** (które treści/formaty/tematy generują zaangażowanie)
3. **Co konkurencja właśnie robi?** (promocje, konkursy, nowe usługi — zdarzenia, nie wykresy)
4. **Ile i kiedy publikować?** (kadencja i timing rynku lokalnego)
5. **Czy mój marketing działa na tle rynku?** (ja vs koszyk konkurencji)

Teza: **każda liczba pokazana użytkownikowi musi mieć kontekst (deltę, benchmark lub próg),
a wnioski wyciąga system, nie użytkownik.** Surowe dane pozostają dostępne, ale jako trzecia
warstwa ("pokaż szczegóły"), nie jako ekran startowy.

---

## 2. Diagnoza stanu obecnego (audyt kodu)

### 2.1 Co mamy

| Warstwa | Stan |
|---|---|
| Pozyskiwanie | RapidAPI `ig-scraper5.p.rapidapi.com` (posty, szczegóły profilu, stories) — `RapidApiInstagramClient` |
| Harmonogram | posty: niedziela 03:00 · szczegóły profilu: codziennie 08:00 · stories: codziennie 09:00 + sync po zatwierdzeniu profilu |
| Model danych | 6 tabel: `instagram_profiles` (globalne, dedup po username), `studio_instagram_profiles` (link tenant↔profil + moderacja), `instagram_post_snapshots`, `instagram_profile_metrics_snapshots` (dzienna historia obserwujących), `instagram_story_snapshots` (tylko `story_id` + `taken_at`), `studio_instagram_post_reactions` |
| Agregacja | jeden handler (`GetCompetitionSummaryHandler`) liczący wszystko in-memory na każde żądanie |
| AI | generator postów (RAG po reakcjach 👍/👎, pgvector, gpt-4o-mini) — działa, zostaje |
| Frontend | `competition-monitoring`: 2 zakładki → 4 pod-zakładki wykresów, 9-kolumnowa tabela rankingowa, macierz "SEO profilu", modal z surowymi caption'ami postów |

### 2.2 Dlaczego jest szum — konkretnie

1. **Same wartości bezwzględne.** Żadna metryka nie ma delty vs poprzedni okres, percentyla ani progu.
   Jedyny trend to nieopisany sparkline 80 px.
2. **`avgEngagement` = lajki + komentarze / post, bez normalizacji przez obserwujących** — profil
   z 900 followersami jest nieporównywalny z profilem 4 200, a tabela sugeruje, że jest.
3. **Interpretacja przerzucona na prozę.** Cztery akapity `ChartHint` tłumaczą, co *mogą* znaczyć
   piki ("konkurs, płatna kampania lub kupiona aktywność"), zamiast żeby system je wykrywał i etykietował.
4. **Trzy poziomy nawigacji do jednej liczby** (zakładka strony → zakładka wykresu → selektor okresu),
   zero stanu w URL, inny model selekcji profili w sąsiadujących komponentach (wykres: max 4, tabele: wszystkie).
5. **Modal postów = zrzut surowych caption'ów** w 3 kolumnach — bez zdjęć, bez linku do posta,
   bez sortowania i filtrów.
6. **Brak modelu własnego konta studia** — niemożliwe jakiekolwiek "ja vs konkurencja".
7. **~40% pobieranych pól nigdy nie trafia na ekran** (`biography`, `mediaCount`, `avgViews`,
   `hashtags`, `product_type`, `carousel_media_count`…), a pola niskosygnałowe (`isVerified`,
   `followingCount`) zajmują główne kolumny. Hashtagi i typ formatu są **zbierane i niewykorzystywane**.

### 2.3 Długi techniczne — blokery do spłaty niezależnie od redesignu

| # | Problem | Gdzie |
|---|---|---|
| B1 | **Hardcodowana allowlista 5 usernames** — dla każdego innego profilu klient po cichu zwraca pustkę (bez `apiError`) | `RapidApiInstagramClient.kt:225,323` |
| B2 | **Zero migracji Flyway dla 6 tabel modułu** — prod (`ddl-auto=validate`) nie wstanie na czystej bazie | `db/migration/` |
| B3 | **Żywy klucz RapidAPI zacommitowany** jako default property — do rotacji i przeniesienia wyłącznie do env | `application.properties:77` |
| B4 | **Brak rate-limitingu / retry / budżetu wywołań** przeciw płatnemu vendorowi (Resilience4j jest w projekcie — używa go tylko GUS) | cały slice |
| B5 | **Pełny sync wszystkich profili synchronicznie w request POST /profiles** | `InstagramController.kt:52` |
| B6 | **Add-on `INSTAGRAM_MONITORING` (32 zł) jest sprzedawany, ale nigdzie nie egzekwowany** — kontrolery gatuje `MARKETING_MANAGE` → `FeatureKey.CAMPAIGNS` | `role/`, `subscription/` |
| B7 | Kill-switche `instagram.*.sync.enabled` opisane w KDoc **nie istnieją**; 2 z 3 cronów niezadeklarowane w properties | `sync/` |
| B8 | Endpointy eksperymentalne (`/ab-test`, `/negative-impact-test`, `/debug-generate`) na produkcyjnym API | `InstagramPostGenerationController` |
| B9 | `GET /posts` nie zwraca reakcji studia (frontend duplikuje stan w `localStorage`) | `posts/`, `PostsModal.tsx` |
| B10 | Brak metryk Micrometer (wolumen wywołań API, wyniki synców) | cały slice |

Frontend dodatkowo: ~2 050 linii martwego kodu (4 komponenty nigdy nieimportowane —
`CompetitionRanking`, `CompetitionTable`, `EngagementTrendChart`, `PostVolumeChart`) + ~470 linii
mock fixtures wchodzących do bundla.

---

## 3. Analiza oficjalnych API Instagrama (stan: sierpień 2026)

### 3.1 Co w ogóle istnieje

- **Instagram Basic Display API — nie istnieje.** Wyłączone przez Metę 4 grudnia 2024. Nie ma czego analizować.
- **Instagram Graph API** (obecnie "Instagram API", warianty z logowaniem Facebook lub Instagram) —
  jedyne oficjalne API. Wymaga własnego konta profesjonalnego (Business/Creator), aplikacji Meta
  z App Review i — dla pełni funkcji — połączonej strony Facebook.
- **oEmbed API** — oficjalny sposób osadzania publicznych postów na własnych stronach/aplikacjach
  (zwraca HTML embeda). Istotne dla legalnej *prezentacji* treści konkurencji (§4.2).

### 3.2 Co Graph API daje do analizy konkurencji — konkretnie

| Funkcja | Endpoint | Co zwraca | Ograniczenia |
|---|---|---|---|
| **Business Discovery** — jedyny oficjalny mechanizm podglądu cudzych kont | `GET /{ig-user-id}?fields=business_discovery.username(...)` | profil: `username`, `name`, `biography`, `website`, `followers_count`, `follows_count`, `media_count`, `profile_picture_url` + **media**: `caption`, `like_count`, `comments_count`, `media_type`, `media_product_type`, `permalink`, `timestamp`, `children` | tylko konta **Business/Creator** (konto osobiste → błąd); brak stories; brak danych demograficznych; `like_count` niewidoczny, gdy właściciel ukrył lajki; standardowe limity (≈200 wywołań/h na token użytkownika) |
| **Hashtag Search** | `GET /ig_hashtag_search` + `/{hashtag-id}/top_media`, `/recent_media` | media z hasztagu: `caption`, `like_count`, `comments_count`, `media_type`, `permalink`, `timestamp` — **bez** `username` autora | max **30 unikalnych hasztagów / 7 dni** na konto; tylko posty opublikowane po utworzeniu zapytania (recent) |
| **Mentions** | `/{own-id}/tags`, pole `mentioned_media` | posty, w których oznaczono **nasze** konto | tylko własne konto |
| **Insights** | `/{own-id}/insights`, `/{media-id}/insights` | zasięg, wyświetlenia profilu, zapisy, udostępnienia, dane demograficzne obserwujących | **wyłącznie własne konto** — to jest właściwe źródło do benchmarku "ja" |

### 3.3 Werdykt: pokrycie potrzeb modułu przez oficjalne API

| Potrzeba modułu | Business Discovery | Nasz scraper (RapidAPI) |
|---|---|---|
| Metryki profilu (followers, media_count, bio, www) | ✅ | ✅ |
| Posty z licznikami lajków/komentarzy, caption, format | ✅ (+ `permalink`!) | ✅ |
| Liczba wyświetleń wideo | ❌ | ✅ (`view_count`) |
| Historia obserwujących | ⚠️ budujemy sami z dziennych odczytów (tak jak dziś) | ⚠️ jw. |
| Stories konkurencji | ❌ | ✅ (ale patrz §4.3 — rezygnujemy) |
| Konta osobiste (nie-Business) | ❌ | ✅ |
| Flagi konta (is_private, is_business, kategoria) | częściowo | ✅ |
| Wzmianki o hasztagach lokalnych/branżowych | ✅ (limit 30/tydz.) | ✅ |

Wniosek: **oficjalne API legalnie pokrywa ~80% potrzeb** pod warunkiem, że śledzony konkurent ma
konto Business/Creator (w detailingu — zdecydowana większość liczących się studiów; konta osobiste
i tak są mało warte analitycznie). Nie pokrywa stories (których i tak się pozbywamy) oraz `view_count`.
Dodatkowo Graph API otwiera **nową, kluczową możliwość niedostępną scraperem: Insights własnego
konta studia** — fundament benchmarku "ja vs rynek".

---

## 4. Strategia pozyskiwania danych i ramy prawne

### 4.1 Decyzja architektoniczna: dwóch dostawców za wspólnym interfejsem

Zgodnie z ustaleniem: **nie wymieniamy warstwy pozyskiwania — abstrahujemy ją.**

```
                        ┌──────────────────────────────┐
 sync/ (schedulery) ───▶│  InstagramDataProvider (SPI) │
                        ├──────────────────────────────┤
                        │ RapidApiProvider   (istnieje)│  konta dowolne; posty+details; view_count
                        │ MetaGraphProvider  (nowy)    │  konta Business/Creator; business_discovery,
                        │                              │  hashtag search, oEmbed, own-account insights
                        └──────────────────────────────┘
```

- Interfejs: `fetchProfileDetails(username)`, `fetchPosts(username, window)`, `capabilities()`.
- **Routing per profil**: pole `preferred_source` na `instagram_profiles` (`RAPIDAPI` | `META_GRAPH` | `AUTO`).
  W trybie `AUTO`: jeśli konto jest Business/Creator i mamy skonfigurowaną aplikację Meta → Graph,
  w przeciwnym razie RapidAPI. Fallback przy błędzie źródła podstawowego.
- Dane obu źródeł normalizowane do jednego modelu snapshotów (obecne tabele) + kolumna `source`
  na snapshotach dla audytu.
- Provider własnego konta (`OwnAccountInsightsProvider`) — wyłącznie Graph API (OAuth studia),
  osobny, bo zwraca metryki niedostępne dla cudzych kont.

Koszt utrzymania dwóch źródeł jest niski (wspólny model, wspólne sync-serwisy), a zyskujemy:
odporność na wyłączenie jednego źródła, ścieżkę stopniowej migracji na kanał oficjalny
oraz dane własnego konta.

### 4.2 Ramy prawne (ochrona IP, RODO, uczciwa konkurencja)

*Poniższe to analiza inżynierska do decyzji produktowych, nie porada prawna — przed wdrożeniem
rekomendujemy walidację z kancelarią.* Priorytet zgodnie z ustaleniem: **zgodność z prawem
powszechnym (prawo autorskie, RODO, uzk)**; ryzyko regulaminowe Mety dla ścieżki RapidAPI
akceptujemy świadomie jako ryzyko biznesowe (cywilnoprawne, nie karne) i minimalizujemy je
istnieniem ścieżki oficjalnej.

**Prawo autorskie / własność intelektualna:**

1. **Fakty i liczby nie są utworami.** Liczniki (followers, lajki, komentarze), daty publikacji,
   format posta, hasztagi jako dane — wolne od ochrony prawnoautorskiej. Rdzeń modułu
   (metryki, trendy, benchmarki) jest bezpieczny.
2. **Caption i zdjęcia/wideo to utwory.** Zasady:
   - **Nie przechowujemy i nie kopiujemy mediów.** Usuwamy zapis `image_url` ze snapshotów
     (i tak wygasa — tokeny CDN). Prezentacja treści posta w UI wyłącznie przez **oficjalny
     oEmbed** (osadzenie wskazuje na oryginał u Mety, z atrybucją autora) albo link `permalink`.
     To ścieżka, którą Meta sama udostępnia do pokazywania publicznych postów poza Instagramem.
   - **Caption przechowujemy do analizy** (klasyfikacja tematów, wykrywanie promocji, RAG) —
     to mieści się w dozwolonym użytku *text and data mining* (art. 4 dyrektywy DSM,
     implementacja w polskiej ustawie o pr. aut. z 2024 r.), a wyniki analizy (etykiety, statystyki)
     nie są zwielokrotnieniem utworu. W UI caption pokazujemy najwyżej jako **krótki cytat
     z atrybucją i linkiem do oryginału** (prawo cytatu), a domyślnie — przez oEmbed.
     Zastrzeżenie: opt-out TDM przez uprawnionych jest prawnie nieostry — dlatego twarda zasada
     "przechowujemy do analizy, nie republikujemy" i możliwość usunięcia danych profilu na żądanie.
3. **Prawo baz danych (sui generis).** Pobieramy nieistotne ilościowo wycinki (kilka–kilkanaście
   profili per studio, kilkadziesiąt postów per profil) — nie "istotną część" bazy; niskie ryzyko.
   Nie budujemy własnej publicznej re-publikacji bazy Instagrama.

**RODO:**

- Profile firmowe studiów to często JDG — **username, bio, zdjęcia mogą być danymi osobowymi**.
  Podstawa przetwarzania: **uzasadniony interes (art. 6 ust. 1 lit. f)** — analiza rynku
  i konkurencji na podstawie danych, które przedsiębiorca sam upublicznił w celach promocyjnych.
  Do zrobienia: udokumentowany test równowagi (LIA), wpis w rejestrze czynności, aktualizacja
  polityki prywatności.
- **Minimalizacja wbudowana w kod:** monitorujemy wyłącznie konta publiczne (twarda walidacja
  `is_private == false` — dziś tylko wyświetlamy tę flagę); przechowujemy tylko pola potrzebne
  do zdefiniowanych metryk; **retencja**: snapshoty postów 24 mies., historia metryk 24 mies.,
  automatyczne czyszczenie (dziś: bezterminowo).
- **Prawa osób:** procedura usunięcia profilu na żądanie osoby, której dane dotyczą (kaskadowe
  czyszczenie snapshotów + wektorów pgvector — mechanizm kasowania już istnieje przy usunięciu
  ostatniej subskrypcji profilu, wystarczy go wystawić operacyjnie). Obowiązek informacyjny
  art. 14 — do decyzji z prawnikiem klauzula na stronie publicznej (wyjątek niewspółmiernego wysiłku).
- **Nie profilujemy osób prywatnych** — moduł analizuje działalność marketingową firm.

**Stories — §4.3. Uczciwa konkurencja:** monitorowanie publicznych działań marketingowych
konkurencji to standardowa, legalna praktyka market intelligence; nie pozyskujemy tajemnic
przedsiębiorstwa ani treści niepublicznych.

### 4.3 Stories: wycofujemy

Ograniczenie projektu zakazuje pobierania/przechowywania/prezentowania treści ulotnych.
Obecna implementacja przechowuje wyłącznie `story_id` + `taken_at` (metadane, nie treść),
ale utrzymywanie osobnego pipeline'u (dzienny cron, który i tak strukturalnie gubi stories
krótsze niż 24 h między odczytami) dla metryki o niskiej wiarygodności nie broni się także
produktowo. Decyzja:

- **Usuwamy** `InstagramStorySyncService/Scheduler`, endpoint RapidAPI `/user/stories`,
  tabelę `instagram_story_snapshots` (po migracji), pola `storyCount`/`storiesPerWeek`/
  `dailyStoryStats` z API i zakładkę "Stories" z UI.
- Intensywność publikacji mierzymy postami + Reels (`total_clips_count` już mamy).
- Jeśli kiedyś wróci potrzeba — wyłącznie jako dzienny licznik agregowany, nigdy treść. Nie w tym wydaniu.

---

## 5. Nowe metryki i funkcje (wartość dla właściciela studia)

### 5.1 Katalog metryk (warstwa obliczeniowa)

Wszystko liczone z danych, **które już zbieramy** (posty, liczniki, `product_type`, hashtagi,
`taken_at`, dzienna historia followersów) — bez nowych wymagań wobec źródła:

| Metryka | Definicja | Po co detailerowi |
|---|---|---|
| **ER%** (engagement rate) | (lajki + komentarze) / followers × 100, per post i średnio per profil | jedyna uczciwa porównywarka profili różnej wielkości; zastępuje obecny `avgEngagement` |
| **Wzrost obserwujących** | Δ% 7d / 30d z `follower_history` + wykrywanie skoków (>3σ) | "kto rośnie i od kiedy"; skok = kampania płatna / viral / kupione konta |
| **Mix i skuteczność formatów** | udział + mediana ER% dla Reels / karuzela / zdjęcie (`product_type`, `carousel_media_count`) | odpowiedź "czy Reels u konkurencji robią robotę" → decyzja o własnym formacie |
| **Kadencja i regularność** | posty/tydz. + współczynnik regularności (odchylenie odstępów) | "rynek publikuje 3×/tydz., ty 0,5×" |
| **Heatmapa publikacji** | rozkład `taken_at` dzień tygodnia × pora dnia, ważony ER% | kiedy publikować, żeby konkurować o uwagę lokalnego odbiorcy |
| **Tematy treści** | klasyfikacja caption przez istniejący pipeline LLM do słownika branżowego: *powłoka ceramiczna, PPF, korekta lakieru, detailing wnętrza, felgi/opony, przed–po, promocja/rabat, konkurs, backstage/zespół, oferta pracy* | macierz temat × ER% = "co działa w mojej okolicy"; dziś klasyfikujemy tylko na potrzeby generatora |
| **Detektor promocji i konkursów** | reguły (ceny, "%", "promocja", "konkurs", "rozdajemy") + potwierdzenie LLM | alert w ciągu tygodnia od ogłoszenia promocji przez konkurenta |
| **Posty ponadprzeciętne (viral)** | ER posta > mediana profilu + 2×MAD | "ten post im wystrzelił — zobacz dlaczego" |
| **Hashtag intelligence** | top hasztagi koszyka konkurencji, hasztagi wspólne/lokalne, ER% per hasztag | gotowa lista tagów do własnych postów (zasila też generator AI) |
| **Share of voice** | udział profilu w postach i zaangażowaniu koszyka konkurencji | pozycja studia na tle obserwowanego rynku |
| **Wskaźnik witryny cyfrowej** (0–100) | ważona suma: link w bio, dane kontaktowe, konto firmowe, kategoria, bio > 50 znaków, aktywność < 14 dni | zastępuje obecną macierz "SEO profilu" (7 wierszy checkmarków) jedną liczbą z listą braków |
| **Indeks aktywności marketingowej** (0–100) | kompozyt: kadencja + ER% + wzrost + regularność, przeliczany tygodniowo | jedna liczba do rankingu i do trendu "rynek przyspiesza/zwalnia" |

Każda metryka jest zawsze zwracana **w trójce: wartość · delta vs poprzedni okres · benchmark
koszyka** (mediana obserwowanych profili). To reguła kontraktu API, nie ficzer.

### 5.2 Benchmark "Twoje studio" (nowość strategiczna)

- Studio podłącza własne konto IG przez OAuth Meta (`OwnAccountInsightsProvider`) **lub** — wariant
  minimalny bez OAuth — dodaje własny username jako profil obserwowany "self" (te same metryki
  publiczne co konkurencja).
- Z OAuth dodatkowo: zasięg, zapisy, udostępnienia, demografia — metryki, których nie widzimy
  u konkurencji, prezentowane obok benchmarku publicznego.
- W każdym rankingu i wykresie wiersz/linia "Ty" jest wyróżniona i przyklejona; ranking pokazuje
  percentyl ("jesteś 3. z 7 obserwowanych, ER% powyżej mediany o 1,2 p.p.").

### 5.3 Funkcje produktowe

1. **Feed insightów** — chronologiczna lista zdarzeń wygenerowanych przez silnik (§6.2), każdy
   w formacie: *co się stało → dlaczego to ważne → co możesz zrobić*. Max 5 nowych/tydzień
   (twardy limit anty-szumowy), z priorytetem i możliwością "odhaczenia".
2. **Tygodniowy digest** — po niedzielnym syncu e-mail/in-app z 3–5 najważniejszymi insightami
   tygodnia (reuse istniejącej infrastruktury e-mail z modułu kampanii). To domyślny punkt
   kontaktu użytkownika z modułem — nie musi w ogóle wchodzić w dashboard, żeby dostawać wartość.
3. **Przegląd (dashboard)** — pozycja studia, indeksy, top-insighty (§7.1).
4. **Eksplorator treści konkurencji** — posty koszyka sortowane po ER%, filtrowane po temacie
   i formacie, prezentowane przez oEmbed z linkiem do oryginału; reakcje 👍/👎 zostają (karmią
   generator AI), stan reakcji zwracany z backendu (naprawa B9).
5. **Generator postów AI — zostaje**, wzbogacony o kontekst z analityki: top tematy i hasztagi
   tygodnia podpowiadane jako temat/`styleNotes`.

### 5.4 Język korzyści i narracja sprzedażowa (PM)

**Persona.** Właściciel studia to rzemieślnik, nie marketer: Instagram jest jego głównym kanałem
pozyskania klienta (branża w 100% wizualna — przed/po sprzedaje usługę), ale prowadzi go
"po godzinach", bez wiedzy analitycznej i bez czasu. Nie kupi "metryk" — kupi **czas, spokój
i przewagę nad studiem po drugiej stronie miasta**. Punkt odniesienia cenowy: jedna dodatkowa
powłoka ceramiczna to 2 000–5 000 zł przychodu; add-on kosztuje 32 zł/mies.

**Zasada języka korzyści:** nie nazywamy metryk — nazywamy decyzje, które podejmuje za użytkownika
system. Każdy komunikat w formule: *sytuacja klienta → co dostaje → co z tego ma*.

| Co mierzymy (żargon wewnętrzny) | Co mówi produkt (język korzyści) |
|---|---|
| Indeks aktywności + rank/percentyl | „**W 10 sekund wiesz, czy Twój Instagram wygrywa, czy przegrywa** z konkurencją w Twoim mieście — bez scrollowania i zgadywania." |
| ER% (normalizowany) + wykrywanie skoków | „Widzisz, **kto naprawdę przyciąga klientów, a kto kupił obserwujących**. Duży profil nie znaczy skuteczny — pokazujemy, jak jest naprawdę." |
| Tematy treści × ER% | „**Przestań zgadywać, co wrzucić.** Sprawdzamy, które tematy — ceramika, PPF, wnętrza, przed/po — przynoszą zaangażowanie w Twojej okolicy, i mówimy Ci to wprost." |
| Skuteczność formatów (Reels/karuzela/zdjęcie) | „Reels u Twojej konkurencji robią 3× lepszy wynik niż zdjęcia? **Dowiesz się w tydzień, nie po pół roku prób.**" |
| Kadencja + heatmapa publikacji | „Podpowiadamy, **ile i kiedy publikować**, żeby Twoje posty trafiały wtedy, gdy Twoi klienci scrollują — na podstawie danych z Twojego rynku, nie ogólników z internetu." |
| Detektor promocji/konkursów | „Konkurent ogłasza −20% na powłoki? **Wiesz o tym w poniedziałek rano z maila** — a nie od klienta, który już tam pojechał." |
| Posty viralowe konkurencji | „Gdy komuś w okolicy „wystrzeli" post, dostajesz go z analizą: **co zadziałało i jak to powtórzyć u siebie**." |
| Hashtag intelligence | „**Gotowa lista hasztagów, które działają w Twojej okolicy** — skopiuj do posta albo jednym klikiem podaj do generatora AI." |
| Wskaźnik witryny cyfrowej | „Twój profil to Twoja wizytówka. Pokażemy, **czego w niej brakuje, zanim klient kliknie do konkurencji** — i dokładnie co poprawić." |
| Digest tygodniowy | „**5 minut w poniedziałek zamiast godzin śledzenia Instagrama.** Czytamy konkurencję za Ciebie i przysyłamy tylko to, co wymaga Twojej decyzji." |
| Benchmark „Ty vs rynek" | „Koniec z „chyba idzie nam okej". **Czarno na białym: jesteś 3. z 7 studiów w okolicy** i wiesz, co zrobić, żeby być wyżej." |

**Narracja przewodnia (pitch):** *„Twój analityk marketingu za 32 zł miesięcznie. Czyta Instagram
konkurencji za Ciebie i co poniedziałek mówi Ci trzy rzeczy: gdzie jesteś na tle rynku, co
u konkurencji działa i co masz z tym zrobić."* ROI-framing: jeśli jeden insight w roku przyniesie
jedno dodatkowe zlecenie na powłokę, add-on zwraca się ~10-krotnie.

**Odpowiedzi na obiekcje (do materiałów sprzedażowych i onboardingu):**

- *„Nie znam się na marketingu"* → nie musisz: system nie pokazuje wykresów do interpretacji,
  tylko gotowe wnioski z instrukcją „co zrobić".
- *„Sam widzę, co wrzuca konkurencja"* → gołym okiem nie widać: skuteczności względem wielkości
  profilu, trendu obserwujących, tego które tematy i godziny działają — ani tego, co wrzucili,
  kiedy akurat nie patrzyłeś.
- *„Nie mam czasu"* → cała wartość mieści się w jednym mailu tygodniowo; dashboard jest opcją,
  nie obowiązkiem.

**Obietnica onboardingowa:** „Dodaj 3–5 profili konkurencji dziś — pierwszy raport dostaniesz
w najbliższy poniedziałek rano." (Po fazie 2 warto rozważyć sync inicjalny od razu po
zatwierdzeniu profili, by skrócić time-to-value do godzin.)

---

## 6. Redukcja szumu — architektura przetwarzania

### 6.1 Zasada: licz przy zapisie, nie przy odczycie

Dziś `GetCompetitionSummaryHandler` ładuje wszystkie snapshoty i liczy wszystko in-memory na
każde żądanie. Docelowo:

```
sync (niedziela 03:00 + dzienny details)
  └─▶ 1. snapshoty (jak dziś)
  └─▶ 2. AggregationService  ──▶ instagram_profile_stats_weekly   (metryki per profil per tydzień ISO)
  └─▶ 3. ClassificationService ─▶ etykiety tematów na snapshotach postów (LLM, tylko nowe posty)
  └─▶ 4. InsightEngine        ──▶ instagram_insights              (zdarzenia, patrz niżej)
  └─▶ 5. DigestService        ──▶ e-mail tygodniowy
```

Nowe tabele (Flyway, razem z zaległym baseline'em B2):

- `instagram_profile_stats_weekly` — `profile_id`, `week_start`, `post_count`, `reels_count`,
  `carousel_count`, `total_likes`, `total_comments`, `er_pct`, `follower_delta`, `activity_index`, …
  Endpoint summary czyta gotowe wiersze; delty i benchmarki liczone w SQL (okno = porównanie
  z poprzednim okresem, mediana koszyka per studio).
- `instagram_post_topics` — `post_id`, `topic` (enum słownika branżowego), `confidence`,
  `is_promo`, `is_contest` (rozszerzenie istniejącej klasyfikacji z `ai/`).
- `instagram_insights` — `id`, `studio_id`, `type`, `severity`, `title`, `body`, `payload jsonb`,
  `profile_id?`, `post_id?`, `dedup_key`, `status` (NEW/SEEN/DISMISSED), `created_at`.

### 6.2 Silnik insightów: deterministyczne detektory + LLM tylko do narracji

**Detektory to kod, nie model** — progi jawne, wyniki powtarzalne i testowalne:

| Typ insightu | Reguła (przykład progu) | Severity |
|---|---|---|
| `PROMO_DETECTED` | post sklasyfikowany `is_promo` u konkurenta | wysoki |
| `VIRAL_POST` | ER posta > mediana profilu + 2×MAD | wysoki |
| `FOLLOWER_SPIKE / DROP` | Δ 7d > +5% / < −3% | średni |
| `CADENCE_SHIFT` | posty/tydz. konkurenta ±50% vs średnia 4 tyg. | średni |
| `FORMAT_TREND` | mediana ER% Reels koszyka > 1,5× mediany zdjęć przez 4 tyg. | średni |
| `YOU_FALLING_BEHIND` | indeks aktywności studia < mediana koszyka przez 2 tyg. z rzędu | wysoki |
| `NEW_HASHTAG` | hasztag używany przez ≥2 konkurentów, nieobecny wcześniej | niski |
| `PROFILE_GAP` | wskaźnik witryny cyfrowej studia < 70 z listą braków | niski |

LLM (istniejący stack Spring AI) dostaje **wyłącznie wyliczone liczby i etykiety** (nigdy surowe
treści) i pisze 2–3 zdania narracji do digestu. Dzięki temu koszt jest stały i mały, a treść
insightu nie może "zhalucynować" liczb — liczby wstawia kod.

#### 6.2.1 Atrybucja przyczyny wystrzału (kampania vs kupione zaangażowanie)

Dziś użytkownik widzi pik na wykresie i sam zgaduje między "kampania" a "kupione lajki"
(podpowiada mu to akapit prozy). Docelowo insighty `VIRAL_POST` i `FOLLOWER_SPIKE` niosą pole
`probable_cause` wyliczane z sygnałów, które już mamy:

| Sygnał (kombinacja) | `probable_cause` | Pewność |
|---|---|---|
| pik na poście z etykietą `is_contest` / `is_promo` | `KONKURS` / `PROMOCJA` | wysoka (przyczyna zadeklarowana w caption) |
| pik lajków **i** komentarzy proporcjonalny do normy profilu, format Reels | `VIRAL_REELS` (organiczna dystrybucja) | średnia |
| pik lajków przy płaskich komentarzach — stosunek lajki/komentarze > 3× norma profilu | `PODEJRZENIE_KUPIONEGO_ZAANGAŻOWANIA` | średnia |
| skok obserwujących bez wzrostu zaangażowania (ER% spada — rozwodnienie) | `PODEJRZENIE_KUPIONYCH_OBSERWUJĄCYCH` | średnia |
| skok obserwujących + viral post w tym samym tygodniu | `WZROST_ORGANICZNY` | średnia |
| równomiernie podniesione zaangażowanie wielu postów bez zmiany treści | `MOŻLIWA_KAMPANIA_PŁATNA` (boosting) | niska |

Zasady uczciwości: `probable_cause` prezentujemy zawsze jako **hipotezę z listą sygnałów**
("dlaczego tak sądzimy: …"), nigdy jako fakt — zwłaszcza wariantów "podejrzenie kupionego…",
które są zarzutem wobec konkretnej firmy. Insight linkuje do surowych danych (warstwa 3),
żeby użytkownik mógł ocenić sam — czyli obecny ręczny scenariusz pozostaje możliwy, tylko
system wykonuje pierwszy krok za użytkownika.

**Twarde potwierdzenie kampanii płatnej — Meta Ad Library (opcja, faza 5+):** na mocy DSA
wszystkie aktywne reklamy w UE są jawne w Ad Library; detektor `ADS_RUNNING` ("konkurent
włączył reklamy") zamieniłby hipotezę `MOŻLIWA_KAMPANIA_PŁATNA` w fakt. Dostęp do Ad Library
API wymaga weryfikacji tożsamości dewelopera — do zbadania razem z App Review (§8 pkt 4).

#### 6.2.2 Pipeline: tematy treści × ER%

1. **Trigger:** po niedzielnym syncu postów, wyłącznie dla snapshotów **nowych** (klasyfikacja
   jest idempotentna — post klasyfikujemy raz; re-klasyfikacja tylko przy zmianie caption).
2. **Pre-filtr deterministyczny (kod, zero kosztu):** regexy kandydatów promocji/konkursu
   (`%`, "promocja", "rabat", "zniżka", ceny "zł", "gratis", "rozdajemy", "konkurs") ustawiają
   `is_promo_candidate` — LLM tylko potwierdza kandydatów, nie skanuje wszystkiego.
3. **Klasyfikacja LLM (rozszerzenie istniejącego `InstagramPostClassificationService`):**
   batch nowych caption → structured output: `topic` (zamknięty słownik 10 etykiet + `INNE`),
   `confidence`, `is_promo` + payload `{discount_pct?, service?, deadline?}`, `is_contest`.
   Wynik do `instagram_post_topics`. Skala kosztu: ~12 nowych postów/profil/tydzień ×
   gpt-4o-mini — pomijalne; `confidence < 0.6` → `INNE` (nie zgadujemy).
4. **Agregacja (SQL, w `AggregationService`):** join `instagram_post_topics` × metryki postów
   → mediana ER% per temat per koszyk studia w oknie; wynik w `instagram_profile_stats_weekly`
   / dedykowanym widoku dla `GET /content?topic=` i macierzy temat×ER% w UI.
5. **Konsumpcja:** macierz "co działa w okolicy" (ekran Treści), detektor `FORMAT_TREND`/tematyczny
   w silniku insightów, podpowiedzi tematów dla generatora AI.

#### 6.2.3 Pipeline: alert "konkurent ogłosił promocję" (poniedziałkowy mail)

```
ndz 03:00  sync postów (jak dziś)
ndz ~03:30 klasyfikacja nowych postów (6.2.2) → is_promo=true na poście konkurenta
ndz ~04:00 InsightEngine: PROMO_DETECTED, dedup_key=PROMO:{post_pk}, severity=wysoki,
           payload={profil, permalink, discount_pct, service, deadline}
pon 07:00  DigestService: e-mail (infrastruktura kampanii) z sekcją "Wymaga Twojej uwagi"
           + insight w feedzie in-app z deep-linkiem do posta (oEmbed/permalink)
```

Świeżość: przy tygodniowym syncu najgorszy przypadek to ~8 dni od publikacji promocji do maila.
Świadomy trade-off koszt/świeżość — jeśli beta pokaże, że to za wolno, opcją jest lekki
codzienny odczyt pierwszej strony postów (12 szt./profil; ~7× więcej wywołań RapidAPI) tylko
dla detekcji promocji, ewentualnie jako wariant premium. Decyzja → §8.

**Reguły anty-szumowe (twarde):** dedup po `dedup_key` (np. `PROMO:{post_pk}`), max 5 insightów
NEW na studio na tydzień (nadwyżka: tylko digest, sekcja "pozostałe"), każdy insight musi mieć
wypełnione pole "co możesz zrobić", insighty wygasają (auto-SEEN po 30 dniach).

### 6.3 Kontrakt API v2 (szkic)

Prefiks `/api/v1/instagram` zostaje (wersjonowanie per endpoint), CRUD profili bez zmian. Nowe:

| Endpoint | Zwraca |
|---|---|
| `GET /overview?weeks=` | pozycja studia (rank, percentyl), indeksy z deltami, top 3 insighty, mini-ranking (każda metryka: `{value, delta, benchmark}`) |
| `GET /insights?status=&page=` | feed insightów |
| `POST /insights/{id}/dismiss` | odhaczenie |
| `GET /benchmark?weeks=` | pełna tabela porównawcza: profile × metryki, zawsze z deltą i percentylem, wiersz self |
| `GET /content?sort=er&topic=&format=&page=` | eksplorator postów koszyka: metryki + `permalink` + reakcja studia (naprawa B9); **paginowany** |
| `GET /content/heatmap?weeks=` | macierz dzień × pora × ER% |
| `GET /hashtags?weeks=` | top hasztagi koszyka z ER% |
| `POST /own-account/connect` / `DELETE …` | OAuth własnego konta (faza 4) |

`GET /profiles/summary` zostaje czasowo jako adapter dla starego UI i znika po migracji frontendu.
Endpointy eksperymentalne AI → za property `instagram.ai.debug-endpoints.enabled=false` (B8).

### 6.4 Architektura informacji w UI (szczegóły w dokumencie frontendowym)

Trzy ekrany zamiast zagnieżdżonych zakładek — piramida: **wniosek → kontekst → surowe dane**:

1. **Przegląd** (default) — "Twoja pozycja" + feed insightów + mini-ranking z deltami. Zero wykresów
   wymagających interpretacji.
2. **Porównanie** — tabela benchmarkowa v2 (delty, percentyle, przyklejony wiersz "Ty") + dokładnie
   **dwa** wykresy (aktywność tygodniowa, obserwujący) z **adnotacjami zdarzeń** na osi czasu
   (pin "promocja", "viral post") zamiast czterech zakładek wykresów z akapitami tłumaczeń.
3. **Treści** — eksplorator postów (oEmbed, sortowanie po ER%, filtry temat/format) + heatmapa +
   hasztagi.

Zarządzanie profilami (dodawanie/moderacja) przenosi się do panelu bocznego/ustawień modułu —
przestaje być główną zakładką. Stan (okres, filtry, selekcja) w URL. Kasujemy 4 martwe komponenty
i mock fixtures z bundla.

---

## 7. Plan refaktoryzacji (fazy)

Kolejność zaprojektowana tak, by każda faza była osobno wdrażalna i odwracalna (feature flag
`INSTAGRAM_V2` per studio), a długi krytyczne spłacone przed rozbudową.

| Faza | Zakres | Zależności | Szacunek |
|---|---|---|---|
| **0. Stabilizacja** (bez zmian funkcji) | rotacja klucza RapidAPI + tylko env (B3) · Flyway baseline dla 6 istniejących tabel (B2) · allowlista → property konfiguracyjne / usunięcie (B1) · sync po `POST /profiles` asynchronicznie, tylko dodany profil (B5) · Resilience4j: rate limiter + retry + dzienny budżet wywołań z licznikiem Micrometer (B4, B10) · kill-switche `instagram.*.sync.enabled` naprawdę istniejące (B7) · egzekwowanie `INSTAGRAM_MONITORING` w gate'owaniu (B6, decyzja produktowa: mapowanie permission→feature) · debug-endpointy AI za flagą (B8) | — | ~1 tydz. |
| **1. Warstwa pozyskiwania** | interfejs `InstagramDataProvider` + refaktor obecnego klienta do `RapidApiProvider` · usunięcie pipeline'u stories (§4.3) + migracja usuwająca tabelę · usunięcie zapisu `image_url`, w zamian `permalink` w modelu posta (RapidAPI go zwraca w `post_code` → `https://instagram.com/p/{code}`) · retencja snapshotów (job czyszczący 24 mies.) · walidacja `is_private` przy dodawaniu profilu | 0 | ~1 tydz. |
| **2. Agregacja i klasyfikacja** | `instagram_profile_stats_weekly` + `AggregationService` w syncu · przełączenie summary na odczyt z tabeli (stary handler = adapter) · ER%, delty, mediany koszyka w SQL · rozszerzenie klasyfikacji LLM o słownik tematów branżowych + `is_promo`/`is_contest` (`instagram_post_topics`) · agregaty hashtagów i heatmapy · naprawa B9 (reakcja w odpowiedzi `GET /content`) | 1 | ~2 tyg. |
| **3. Silnik insightów + digest** | tabela `instagram_insights` + detektory (§6.2) · narracja LLM na liczbach · endpointy `overview`/`insights`/`benchmark`/`content` · digest e-mail po niedzielnym syncu | 2 | ~2 tyg. |
| **4. Frontend v2** | trzy ekrany (§6.4), URL-state, jeden model selekcji · oEmbed w eksploratorze · kasacja martwego kodu i mocków · adapter `summary` do wycofania | 3 | ~2–3 tyg. |
| **5. MetaGraphProvider + własne konto** | aplikacja Meta + App Review (proces zewnętrzny, startuje równolegle od fazy 1!) · `MetaGraphProvider` (business_discovery) + routing `AUTO` · OAuth własnego konta + Insights · percentyle "Ty vs rynek" wszędzie | 2 (kod), App Review (zewn.) | ~2 tyg. kodu + czas review Mety |
| **6. Rollout** | beta na 3–5 studiach, potem flag domyślnie on · usunięcie starego UI i adaptera · pomiar sukcesu | 4, 5 | ~1 tydz. |

**Metryki sukcesu redesignu (PM):** ≥60% aktywnych subskrybentów add-onu otwiera digest lub
Przegląd co tydzień · median time-to-value nowego użytkownika < 10 min (dodanie profili → pierwszy
insight po najbliższym syncu) · churn add-onu `INSTAGRAM_MONITORING` ↓ · liczba reakcji 👍/👎
(karmienie AI) ↑ 3×.

---

## 8. Decyzje do podjęcia przed implementacją

1. **Mapowanie uprawnień (B6):** czy moduł gate'ujemy dedykowanym `FeatureKey.INSTAGRAM_MONITORING`
   (rozdzielenie od `CAMPAIGNS`)? Rekomendacja: tak — inaczej add-on 32 zł pozostaje fikcją.
2. **Obowiązek informacyjny art. 14 RODO:** klauzula publiczna vs indywidualne powiadamianie —
   do walidacji z kancelarią (rekomendacja: klauzula + procedura usunięcia na żądanie).
3. **Budżet wywołań RapidAPI:** ustalić plan cenowy i dzienny limit per studio (dziś: brak
   jakiejkolwiek kontroli kosztów; `POST /profiles` potrafi zrobić pełny scrape N profili).
4. **Aplikacja Meta:** kto jest właścicielem procesu App Review (Business Verification wymaga
   dokumentów firmy) — od tego zależy start fazy 5.
5. **Słownik tematów treści:** zamknięta lista 10 etykiet z §5.1 do akceptacji przez PM/klientów beta.
6. **Digest:** e-mail, in-app, czy oba (rekomendacja: oba; e-mail reuse'uje infrastrukturę kampanii).
7. **Świeżość alertów promocyjnych:** czy tygodniowy sync postów wystarcza dla `PROMO_DETECTED`
   (najgorszy przypadek ~8 dni), czy dokładamy lekki codzienny odczyt pierwszej strony postów
   (~7× więcej wywołań RapidAPI; wariant: tylko premium) — patrz §6.2.3.
8. **Meta Ad Library** jako twarde potwierdzenie kampanii płatnych konkurencji (`ADS_RUNNING`,
   §6.2.1) — zbadać dostęp do API przy okazji App Review.

---

## 9. Czego świadomie NIE robimy

- **Nie śledzimy stories** (ograniczenie projektu + niska wiarygodność pomiaru) — §4.3.
- **Nie przechowujemy ani nie kopiujemy mediów** (zdjęć/wideo) — prezentacja wyłącznie oEmbed/link.
- **Nie monitorujemy kont prywatnych** — twarda walidacja przy dodawaniu.
- **Nie analizujemy komentujących/obserwujących jako osób** (żadnych list followersów, żadnego
  profilowania osób prywatnych) — tylko zagregowane metryki działalności marketingowej firm.
- **Nie budujemy "podglądarki" konkurencji w czasie rzeczywistym** — kadencja tygodniowa jest
  wystarczająca dla decyzji marketingowych i utrzymuje koszty API pod kontrolą.
- **Nie pokazujemy metryk bez kontekstu** — każda liczba w UI ma deltę, benchmark lub próg.
