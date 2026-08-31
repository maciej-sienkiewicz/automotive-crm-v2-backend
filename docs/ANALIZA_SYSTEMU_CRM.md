# Analiza systemu DetailBoost CRM

> Dokument analityczno‑architektoniczny. Powstał na podstawie kodu trzech repozytoriów:
> `automotive-crm-v2-backend` (Kotlin/Spring), `detailing-crm-v2` (panel React)
> oraz `detailing-crm-v2-tablet` (kiosk podpisu).
> Wszystkie wersje i zachowania biznesowe zostały odczytane z plików konfiguracyjnych,
> encji domenowych i handlerów — nie z dokumentacji marketingowej.

---

# CZĘŚĆ 1: OPIS TECHNICZNY

## 1.1 Baza danych i warstwa danych

| Element | Wersja / szczegóły | Źródło |
|---|---|---|
| PostgreSQL | 15+ (sterownik `org.postgresql:postgresql` z BOM Spring Boota) | `architecture.docs`, `application.properties` |
| pgvector | rozszerzenie wektorowe, tabela `instagram_post_vectors`, HNSW, `COSINE_DISTANCE`, 1536 wymiarów | `application.properties` |
| HikariCP | `com.zaxxer:HikariCP` (wersja zarządzana przez Spring Boot 3.2.5) | `build.gradle.kts` |
| Flyway | `flyway-core` (wersja z BOM); 88 migracji `V1…V98` | `src/main/resources/db/migration` |
| Hibernate / JPA | Spring Data JPA, dialekt `PostgreSQLDialect`, `ddl-auto=update` | `application.properties` |
| Redis | obraz `redis:alpine`; sesje (`spring-session-data-redis`), cache GUS, tokeny uploadu, challenge podpisu, pub/sub | `deploy/docker-compose.yaml` |

Architektura wielotenantowa: **wspólna baza, wspólny schemat, izolacja wierszowa**.
Każdy rekord ma kolumnę `company_id` / `studio_id`, każde zapytanie repozytorium filtruje po tenancie
(`findByIdAndCompanyId`), każdy indeks zaczyna się od kolumny tenanta.

## 1.2 Backend

| Element | Wersja |
|---|---|
| Kotlin (JVM) | 2.0.0 (`kotlin("jvm")`, `plugin.spring`, `plugin.jpa`) |
| Java / JVM target | 17 (`JavaVersion.VERSION_17`, obraz `eclipse-temurin:17-jre-alpine`) |
| Spring Boot | 3.2.5 |
| Spring Dependency Management | 1.1.4 |
| Gradle | 8.14 (wrapper), build w obrazie `gradle:8.5-jdk17` |
| Spring AI (BOM) | 1.0.0 |

Wykorzystane moduły frameworka:

- `spring-boot-starter-web`, `-validation`, `-websocket` (STOMP przez SockJS, endpoint `/ws-registry`)
- `spring-boot-starter-security` + `spring-session-data-redis` + `spring-boot-starter-data-redis`
  (sesje stanowe, ciasteczko HttpOnly/SameSite, natychmiastowa rewokacja)
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-cache` (cache danych GUS)
- `spring-boot-starter-aop` (aspekty uprawnień, entitlementów, metryk)
- `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
- `spring-ai-starter-model-openai`, `spring-ai-starter-vector-store-pgvector`

Biblioteki dodatkowe backendu:

| Biblioteka | Wersja | Zastosowanie |
|---|---|---|
| kotlinx-coroutines (core / reactor / jdk8) | 1.8.0 | równoległe budowanie kontekstów walidacji, `Dispatchers.IO` |
| jackson-module-kotlin, kotlin-reflect | z BOM | serializacja |
| AWS SDK v2 (BOM) | 2.21.0 | `s3`, `sts`, `cloudwatch` — magazyn dokumentów, zdjęć, protokołów |
| Apache PDFBox + FontBox | 3.0.1 | wypełnianie formularzy AcroForm, składanie podpisanych PDF |
| metadata-extractor | 2.19.0 | normalizacja orientacji EXIF zdjęć uszkodzeń |
| thumbnailator | 0.4.20 | miniatury zdjęć |
| jsoup | 1.17.2 | odszumianie HTML e‑maili (cytaty, stopki) przed zapisem i przed LLM |
| BouncyCastle (`bcprov`, `bcpkix`, `bcutil`) | 1.78.1 | pieczęć PAdES (CMS/CAdES) + znacznik czasu RFC 3161 |
| KSeF SDK (`pl.akmf.ksef-sdk:ksef-client`) | 3.0.18 | Krajowy System e‑Faktur (repozytorium GitHub Packages) |
| SMSAPI Java SDK (`pl.smsapi:smsapi-lib`) | 3.0.1 | wysyłka SMS |
| JavaMail (`com.sun.mail:jakarta.mail`) | 2.0.1 | SMTP/IMAP |
| Resilience4j (circuitbreaker, retry, ratelimiter, kotlin) | 2.2.0 | odporność integracji GUS |
| MockK | 1.13.10 | testy |

## 1.3 Frontend — panel CRM (`detailing-crm-v2`)

| Element | Wersja |
|---|---|
| React / React DOM | 19.2.0 |
| TypeScript | 5.9.3 |
| Vite | 7.2.4 |
| React Router DOM | 7.12.0 |
| TanStack React Query (+ devtools) | 5.90.16 / 5.91.2 |
| Axios | 1.13.2 |
| React Hook Form + `@hookform/resolvers` | 7.70.0 / 5.2.2 |
| Zod | 4.3.5 |
| FullCalendar (core, daygrid, timegrid, interaction, react) | 6.1.20 |
| Recharts | 3.7.0 |
| styled-components | 6.3.1 |
| Tailwind CSS + `@tailwindcss/postcss` | 4.1.18 |
| PostCSS / autoprefixer / `@csstools/postcss-oklab-function` | 8.5.6 / 10.4.23 / 5.0.0 |
| lucide-react | 0.563.0 |
| dinero.js + `@dinero.js/currencies` | 2.0.0-alpha.14 |
| pdfjs-dist | 6.1.200 |
| qrcode.react | 4.1.0 |
| dompurify | 3.4.14 |
| `@stomp/stompjs` + sockjs-client | 7.2.1 / 1.6.1 |
| `@radix-ui/react-dialog` | 1.1.15 |
| Vitest + Testing Library + jsdom | 4.1.6 / 16.3.2 / 29.1.1 |
| ESLint + typescript-eslint | 9.39.1 / 8.46.4 |

## 1.4 Frontend — kiosk tabletowy (`detailing-crm-v2-tablet`)

Osobna aplikacja SPA („DetailBoost Tablet") uruchamiana na tablecie recepcyjnym.

| Element | Wersja |
|---|---|
| React / React DOM | 19.1.0 |
| TypeScript | 5.8.3 |
| Vite | 6.3.5 |
| pdfjs-dist | 5.3.31 |
| `@stomp/stompjs` + sockjs-client | 7.1.1 / 1.6.1 |
| Playwright | 1.53.0 (testy e2e) |
| Vitest | 3.2.4 |

## 1.5 Integracje zewnętrzne i infrastruktura

| System | Rola | Konfiguracja |
|---|---|---|
| **KSeF** (MF) | e‑faktury: wysyłka FA(3), pobieranie kosztów i przychodów, UPO, kody QR | `api.ksef.mf.gov.pl/api/v2`, sync co 15 min |
| **GUS BIR** (BIR 1.1 / 1.2, SOAP) | pobieranie danych kontrahenta po NIP | `wyszukiwarkaregon.stat.gov.pl`, sesja 55 min, cache Redis 24 h |
| **SMSAPI** | SMS wychodzące + 2‑way SMS (webhook `/api/sms/inbound`) | `api.smsapi.pl` |
| **OpenAI** (przez Spring AI) | `gpt-4o-mini` (generowanie treści), `text-embedding-3-small` (1536), `whisper-1` (transkrypcja PL) | zmienna `OPENAI_API_KEY` |
| **RapidAPI — Instagram** | dostawca `instagram-looter2` (aktywny) / `ig-scraper5` (legacy) | limit 4 req/s, budżet 2000 wywołań/dobę |
| **AWS S3** | dokumenty, protokoły, zdjęcia, zgody (presigned URL) | region `us-east-1` |
| **Przelewy24** | płatności za subskrypcję i pakiety SMS | sandbox/produkcja, tryb mock bez credentiali |
| **CloudFlare Email Workers** | webhook e‑maili przychodzących (nagłówek `X-Cloudflare-Email-Token`) | |
| **Web Push / VAPID** | powiadomienia push na telefon (click‑to‑call, zarobek po wizycie) | RFC 8292 |
| **CardDAV** | synchronizacja kontaktów klientów z telefonem (`/.well-known/carddav`) | hasła aplikacyjne |
| **Prometheus 2.52.0 + Grafana 11.0.0** | monitoring techniczny i analityka produktowa | `deploy/docker-compose.yaml` |
| **Jenkins** | CI/CD (`Jenkinsfile` w każdym repozytorium) | |
| **Docker** | osobne kontenery: backend, redis, prometheus, grafana, frontend | |

## 1.6 Wzorce architektoniczne

- **Vertical slices** — katalogi po funkcjach biznesowych (`visit/create/…`), nie po warstwach.
- **Composite + Context validation** — `ValidationContextBuilder` zrównolegla zapytania korutynami,
  `ValidatorComposite` uruchamia zestaw walidatorów przed handlerem.
- **Niezmienne snapshoty** — cena i nazwa usługi oraz dane pojazdu zamrażane w momencie utworzenia wizyty;
  edycja usługi w cenniku archiwizuje starą wersję (`is_active = false`) i tworzy nowy rekord.
- **Value classes** — `@JvmInline value class` dla wszystkich identyfikatorów (`StudioId`, `VisitId`…).
- **Money w groszach** — `Money(amountInCents)`, inwariant `netto + VAT = brutto` egzekwowany w konstruktorze.
- **Maskowanie PII na granicy serializacji** — adnotacja `@Pii` + `PiiMaskingModule`, nagłówek
  `X-Pii-Access: granted|masked`.

---

# CZĘŚĆ 2: OPIS FUNKCJONALNY

Jak system działa z perspektywy studia detailingowego: co robi obsługa, co dzieje się
automatycznie i według jakich reguł. Opis obejmuje wszystkie obszary funkcjonalne wraz
z uzasadnieniem decyzji, które mają realne konsekwencje biznesowe.

---

## 2.1 Rezerwacje i przyjmowanie wizyt

### Rezerwacja i wizyta to dwie różne rzeczy

System celowo rozdziela **rezerwację** — obietnicę terminu, którą można odwołać i której
klient może nie dotrzymać — od **wizyty**, czyli auta, które fizycznie stoi w warsztacie.
Dzięki temu statystyki nie mieszają umówionych terminów z faktycznie wykonaną pracą,
a automatyczne wiadomości nie dziękują za wizytę, na którą nikt nie przyjechał.

**Życie rezerwacji:**

| Stan | Znaczenie |
|---|---|
| Utworzona | termin umówiony, auto jeszcze nie przyjechało |
| Zamieniona na wizytę | klient przyjechał, przeprowadzono przyjęcie pojazdu |
| Odwołana | rezerwacja anulowana przez studio lub klienta; można ją przywrócić |
| Porzucona | termin minął, a auto nigdy nie dojechało |

Stan „porzucona" nadawany jest automatycznie. Co kwadrans system sprawdza rezerwacje,
których termin minął wczoraj lub wcześniej i które nadal czekają na przyjęcie auta, po czym
oznacza je jako porzucone. Kalendarz nie zapełnia się nieaktualnymi wpisami, a właściciel
widzi realną skalę nieodbytych wizyt. Każda taka zmiana trafia do historii aktywności firmy
jako działanie systemu, nie pracownika.

**Życie wizyty:**

```
W realizacji ──► Gotowa do odbioru ──► Zakończona ──► Zarchiwizowana
     │                    │
     │                    └──► W realizacji (trzeba jeszcze dorobić)
     └──► Odrzucona ─────────────────────────────► Zarchiwizowana
```

Ścieżka jest zamknięta: nie da się przeskoczyć etapu ani cofnąć wizyty w dowolne miejsce.
Auto gotowe do odbioru może wrócić do prac, ale wizyty zakończonej nie da się „odkończyć".
Dokumenty finansowe zawsze opisują więc stan, który faktycznie miał miejsce.

### Umawianie terminu

Przed zapisaniem rezerwacji system sprawdza komplet warunków: czy klient istnieje w bazie,
czy pojazd istnieje, czy nowy klient nie duplikuje kogoś już zapisanego, czy podano dane
kontaktowe, czy stawki podatku na pozycjach są prawidłowe, czy tam gdzie trzeba podano cenę
ręcznie i czy wybrany kolor rezerwacji jest dostępny. Rezerwacja albo powstaje kompletna,
albo obsługa dostaje konkretną informację, czego brakuje.

Każdą usługę można wycenić inaczej niż w cenniku: rabaty i narzuty procentowe, kwotowe
doliczane do ceny netto lub brutto oraz całkowite nadpisanie ceny netto albo brutto.
**Kwota wpisana przez pracownika jest wiążąca** — jeśli podał cenę brutto, system nie
przelicza jej wstecz i nie „poprawia" o grosz. Pozornie drobiazg, ale eliminuje sytuację,
w której klient widzi na dokumencie 200,99 zł zamiast uzgodnionych 201 zł.

**Terminy cykliczne**: dla klientów obsługiwanych regularnie można utworzyć całą serię wizyt
naraz; pojedynczy termin da się odczepić i zmienić niezależnie, nie ruszając pozostałych.
**Rezerwacja z zapytania**: zapytanie ofertowe zamienia się w rezerwację wraz z przygotowaną
wyceną i zachowuje powiązanie ze źródłem, więc widać, ile z zapytań kończy się terminem.

### Zmiany i odwołania

Rezerwację można w całości edytować (termin, zakres usług, pojazd, klient), zmienić jej stan,
odwołać i przywrócić. Usunięcie ma dwa poziomy: **zwykłe** — wpis znika z widoków, ale zostaje
w bazie i w historii — oraz **trwałe**, wymagające osobnego uprawnienia. Przy każdej rezerwacji
można też włączyć lub wyłączyć przypomnienie SMS niezależnie od ustawień całego studia oraz
nadać jej własny tytuł widoczny w kalendarzu.

### Kalendarz i dostępność zespołu

Kalendarz pokazuje w jednym widoku wszystko, co zajmuje czas studia: rezerwacje, wizyty w toku,
urlopy i nieobecności pracowników, zdarzenia własne (szkolenie, dostawa, przegląd sprzętu)
oraz trasy odbioru i dowozu aut. Dostępne są widoki miesiąca, tygodnia i dnia, a terminy
przesuwa się przeciągnięciem. Kolory rezerwacji to własny słownik studia — każde może zbudować
swoją legendę (rodzaj usługi, stanowisko, priorytet), zarchiwizować nieużywane kolory
i ustawić kolor domyślny.

**Dostępność pracowników** wynika z ewidencji urlopów i nieobecności, nakładanej na kalendarz
jako osobna warstwa — recepcja od razu widzi, że w danym dniu jednej osoby nie ma. Równolegle
działa **ewidencja czasu pracy**: wpisy dzienne, miesięczne okresy rozliczeniowe zamykane
po zatwierdzeniu i automatyczne wyliczanie nadgodzin ponad ośmiogodzinny dzień pracy.

### Przyjęcie pojazdu

Auto można przyjąć na dwa sposoby: z wcześniejszej rezerwacji albo bezpośrednio „z ulicy".
W obu przypadkach rejestrowane są: przebieg, informacja o przekazaniu kluczy i dokumentów,
uwagi z oględzin, notatki techniczne, zdjęcia oraz **mapa uszkodzeń** — punkty nanoszone
na schemat nadwozia, z których powstaje trwały obraz dołączany do dokumentacji wizyty.
To najważniejszy dokument dowodowy w ewentualnym sporze o rysę, której „wcześniej nie było".

**Przyjęcie z telefonem w ręku**: pracownik skanuje kod QR i na swoim telefonie robi zdjęcia
oraz zaznacza uszkodzenia, a komputer na recepcji aktualizuje formularz na bieżąco — nie trzeba
przenosić plików ani wracać do biurka. Sesja zdjęciowa jest ważna dwie godziny i mieści
do dwudziestu zdjęć; niedokończone sesje są automatycznie sprzątane.

### Karta wizyty

Karta wizyty jest kompletnym zapisem zlecenia. Zawiera **zamrożone dane pojazdu** (marka,
model, numer rejestracyjny, numer nadwozia, rocznik, kolor) — późniejsza zmiana danych
w kartotece nie przepisze historii. Poza tym: listę usług z cenami, zdjęcia i mapę uszkodzeń,
komentarze zespołu, notatkę techniczną z **pełną historią zmian** (widać, kto i kiedy co
poprawił), dokumenty i protokoły, planowaną i faktyczną datę zakończenia, datę odbioru
oraz dziennik zdarzeń.

> **Zmiana zakresu prac w trakcie wizyty.** Gdy w trakcie pracy okazuje się, że trzeba coś
> dodać, usunąć albo zmienić cenę, zmiana nie wchodzi od razu w życie — trafia na listę
> propozycji i czeka na akceptację. **Do wartości wizyty liczą się wyłącznie pozycje
> potwierdzone**: nowa usługa czekająca na zgodę nie podbija kwoty, a zmieniona cena liczy się
> jeszcze po starej stawce, dopóki klient jej nie zaakceptuje. Akceptacja może przyjść
> od pracownika albo **bezpośrednio od klienta, SMS-em zwrotnym**. W obu przypadkach zostaje
> ślad, kto i kiedy zgodził się na rozszerzenie prac.

### Wydanie auta

Kolejne kroki to: oznaczenie auta jako gotowego do odbioru (wysyła powiadomienie do klienta),
wydanie pojazdu (tu powstają dokumenty sprzedaży i opcjonalnie faktura — patrz 2.6),
odrzucenie zlecenia, archiwizacja oraz anulowanie wizyty, która nie została jeszcze rozpoczęta.

### Karta wizyty dla klienta i sprzedaż dodatkowa

Klient dostaje link do prywatnej strony swojej wizyty, którą otwiera bez logowania
i bez instalowania czegokolwiek. Widzi tam status swojego auta oraz usługi dodatkowe
zaproponowane przez studio. Jedno kliknięcie „chcę" uruchamia potwierdzenie, po którym usługi
dopisują się do zlecenia. Studio widzi, czy i kiedy klient otworzył kartę oraz czy link w ogóle
do niego dotarł — to zamienia rozmowę „dzwoniliśmy, nie odbierał" w konkretną informację.

### Odbiór i dowóz auta

Dla studiów oferujących obsługę door-to-door dostępny jest osobny moduł z adresem odbioru,
adresem dostawy, notatkami dla kierowcy i etapami: zaplanowane → w drodze po auto →
auto odebrane → w drodze do klienta → dostarczone. Trasy pokazują się w kalendarzu jako osobna
warstwa, więc planowanie dnia kierowcy odbywa się w tym samym miejscu co planowanie stanowisk.

---

## 2.2 Komunikacja — SMS i e-mail

> **Zasada nadrzędna.** Ustawienia komunikacji należą do studia, wszystkie automatyzacje są
> **domyślnie wyłączone**, a reguła bez wpisanej treści nic nie wysyła. System nigdy nie
> podstawia własnego tekstu za studio — dopóki właściciel nie przeczyta i nie zatwierdzi
> treści, żadna wiadomość nie dotrze do klienta.

Każda wiadomość przechodzi przez wspólną bramkę sprawdzającą, czy studio ma wykupiony moduł
uprawniający do danego rodzaju wysyłki: transakcyjnej (potwierdzenia, przypomnienia,
powiadomienia o gotowości), marketingowej (kampanie) albo wewnętrznej (zaproszenie pracownika
do złożenia podpisu). Każda wysłana wiadomość trafia do historii komunikacji widocznej zarówno
na karcie klienta, jak i na karcie konkretnej wizyty.

### Kiedy dokładnie wychodzi SMS

System sprawdza warunki co minutę, więc wiadomości wychodzą z dokładnością do minuty
od zaplanowanego momentu.

**Wiadomości powiązane z czasem:**

| Wiadomość | Liczona od | Domyślnie | Domyślna treść |
|---|---|---|---|
| Przypomnienie o wizycie | *przed* umówioną godziną | 1 godz. | „Przypominamy o wizycie dnia {{data}} o godz. {{godzina}}. Do zobaczenia, {{imie}}!" |
| Podziękowanie po wizycie | *po* odbiorze auta | 30 min | „Dziękujemy za wizytę, {{imie}}! Mamy nadzieję, że jesteś zadowolony z usługi." |
| Zaproszenie po czasie | *po* odbiorze auta | 90 dni | „Cześć {{imie}}! Minęły 3 miesiące od Twojej ostatniej wizyty. Czas na kolejny detailing?" |

> **Decyzja o realnych konsekwencjach.** Tylko przypomnienie przed wizytą patrzy na kalendarz
> rezerwacji. Podziękowanie i zaproszenie po miesiącach liczone są **od momentu, w którym
> klient odebrał auto**, a nie od godziny w kalendarzu. Minięcie umówionej godziny nie dowodzi,
> że klient przyjechał. Gdyby liczyć od rezerwacji, osoba, która się nie stawiła, dostałaby
> podziękowanie za wizytę, której nie było — i to jest dokładnie ten rodzaj wiadomości, który
> kosztuje studio klienta.

**Wiadomości wysyłane od razu po zdarzeniu:**

| Wiadomość | Wychodzi, gdy… | Domyślna treść |
|---|---|---|
| Potwierdzenie rezerwacji | umówiono termin | „…potwierdzamy rezerwację na {{data}} o godz. {{godzina}}. Czekamy na Ciebie!" |
| Potwierdzenie zmiany terminu | przesunięto rezerwację | „…termin Twojej wizyty został zmieniony na {{data}} o godz. {{godzina}}…" |
| Auto gotowe do odbioru | zakończono prace | „…Twój pojazd {{pojazd}} {{rejestracja}} jest gotowy do odbioru. Zapraszamy!" |
| Link do karty wizyty | pracownik wysyła kartę | „Karta Twojej wizyty {{numer_wizyty}} … jest dostępna tutaj: {{link}}" |
| Link do karty rezerwacji | pracownik wysyła szczegóły terminu | „Szczegóły Twojej rezerwacji na {{data}} … znajdziesz tutaj: {{link}}" |
| Propozycja usług dodatkowych | studio proponuje rozszerzenie zakresu | „Odpisz TAK, żeby do rezerwacji dodać usługi: {{uslugi}}. Łącznie {{kwota}} PLN brutto." |
| Prośba o podpis | dokument czeka na podpis klienta | „Dokument „{{dokument}}" czeka na Twój podpis… {{link}}" |

**Przypomnienie mimo wyłączonej automatyzacji.** Jeśli studio nie włączyło przypomnień
na stałe, ale pracownik zaznaczył je przy konkretnej rezerwacji, wiadomość i tak wyjdzie —
na godzinę przed terminem. Zaznaczenie przy pojedynczej rezerwacji przełamuje wyłącznik,
ale nie tworzy treści: jeżeli studio nigdy nie napisało tekstu przypomnienia, nie ma czego wysłać.

**Pojedyncza wiadomość zaplanowana ręcznie.** Do każdej wizyty można zaplanować jeden SMS
na wskazaną godzinę — na przykład informację, że lakier potrzebuje jeszcze doby na utwardzenie.
Treść pracownik pisze sam albo prosi system o propozycję. Numer telefonu jest zapamiętywany
w chwili planowania, więc późniejsza korekta danych klienta nie zmieni już zaplanowanej wysyłki.

**Ochrona przed dublowaniem.** Wiadomość danego rodzaju wychodzi dla danej rezerwacji dokładnie
raz. Nawet jeśli warunki spełnią się ponownie, klient nie dostanie drugiego przypomnienia
o tej samej wizycie.

### SMS zwrotny od klienta

Gdy pracownik zmienia zakres prac i zaznaczy „powiadom klienta", wychodzi wiadomość z prośbą
o odpowiedź **„TAK"**. Odpowiedź wraca do systemu, zostaje dopasowana po numerze telefonu
do właściwej wizyty i **automatycznie zatwierdza wszystkie czekające pozycje**. Jeśli
w międzyczasie zakres zmienił się jeszcze raz, starsza prośba przestaje obowiązywać, żeby klient
nie zatwierdził nieaktualnej listy. Powstaje udokumentowana zgoda na rozszerzenie prac —
bez telefonu, bez papieru.

### Nazwa nadawcy i budżet SMS

SMS może wychodzić z nazwą studia zamiast numeru, ale dopiero po formalnym potwierdzeniu
u operatora. Do tego czasu wiadomości wysyłane są z numeru dostawcy. Proces potwierdzenia
przeprowadzany jest w aplikacji: system generuje upoważnienie nadawcy, studio podpisuje je
elektronicznie lub wgrywa skan, a dokument trafia do weryfikacji.

**Kredyty SMS** rozliczane są per studio. System pobiera kredyt przed wysyłką i zwraca go,
jeśli operator zgłosi błąd. Kredyty można dokupić w pakietach, a nowe studio dostaje pulę
startową. Brak środków blokuje wysyłkę — to twarda bramka, nie ostrzeżenie, więc nie da się
przypadkiem wygenerować rachunku poza budżetem.

### Automatyczne e-maile

| Wiadomość | Wychodzi, gdy… |
|---|---|
| Potwierdzenie przyjęcia pojazdu | auto zostało przyjęte — z numerem wizyty, opcjonalnie z protokołem przyjęcia w załączniku |
| Auto gotowe do odbioru | zakończono prace |
| Link do karty wizyty lub rezerwacji | pracownik wysyła kartę klientowi |
| Rozliczenie miesiąca dla kontrahenta | zamknięto okres rozliczeniowy — raport w załączniku |

### Szablony i zmienne

Każdy rodzaj wiadomości ma z góry określony zestaw zmiennych, których wolno w nim użyć.
Szablon z nieznaną zmienną jest odrzucany **już przy zapisie**, z listą dopuszczalnych
podpowiedzi w komunikacie. Nie zdarza się więc wysyłka, w której w miejscu imienia zostaje
surowy nawias.

| Grupa | Zmienne |
|---|---|
| Klient | imię, nazwisko, imię i nazwisko *(to ostatnie tylko w e-mailach)* |
| Termin | data, godzina |
| Pojazd i wizyta | pojazd, numer rejestracyjny, numer wizyty |
| Link i sprzedaż dodatkowa | link, lista usług, kwota, nazwa dokumentu |
| Zlecenia zbiorcze | kontrahent, okres, kwota brutto, liczba wpisów |
| Kampanie | marka, model, ostatnia usługa, data ostatniej wizyty, liczba dni od wizyty |

Świadomie **nie ma** zmiennych opisujących samo studio — nazwy, telefonu, adresu czy godzin
otwarcia. Te dane studio zna i wpisuje w szablon jako zwykły tekst; zmienna wnosiłaby tu tylko
ryzyko pomyłki. Cztery wiadomości mają treść ustaloną na stałe, bo dyktuje ją przebieg sprawy,
a nie marketing: dwie dotyczące zmiany zakresu usług, reset hasła i zaproszenie pracownika.

### Kampanie marketingowe

Kampanie mają dwie odmiany, obsługiwane tak samo poza sposobem uruchomienia. **Jednorazowa**
jest przygotowywana, planowana i wysyłana w wybranym momencie (świąteczna, okazjonalna).
**Automatyczna** działa w tle i sama wyłapuje klientów spełniających warunek, na przykład
„180 dni po powłoce ceramicznej"; można ją wstrzymać i wznowić. Kanał: SMS, e-mail albo oba.

**Dobór odbiorców** odbywa się przez filtry, które da się łączyć: liczba wizyt, jak dawno klient
był ostatnio, ile łącznie zostawił w studiu, z jakich usług korzystał, a z jakich nie, kiedy
ostatnio korzystał z danej usługi, marka i model auta, rocznik, klient prywatny czy firma,
data pierwszej wizyty. Poszczególne osoby można dodać lub wykluczyć ręcznie. Osobny przełącznik
decyduje, czy wysyłać do klientów zapisanych tylko z numeru telefonu, bez imienia — kreator
domyślnie ich pomija, bo wiadomość zaczynająca się od „Cześć !" szkodzi bardziej, niż pomaga.

**Warunek uruchomienia kampanii automatycznej** to wybrane usługi, liczba dni po usłudze,
godzina wysyłki oraz opcja pominięcia klientów, którzy w międzyczasie i tak już byli.

**Zabezpieczenia wbudowane w silnik wysyłki:**

- **Godziny ciszy** — domyślnie od 20:00 do 8:00. Wysyłka, która trafiłaby w to okno, jest
  przesuwana na jego koniec. Nikt nie obudzi klienta SMS-em o drugiej w nocy.
- **Limit częstotliwości** — domyślnie jedna wiadomość na siedem dni do tej samej osoby,
  niezależnie od tego, ile kampanii ją obejmuje.
- **Zgody marketingowe** sprawdzane ponownie w chwili wysyłki, a nie tylko przy budowaniu
  listy — zgoda cofnięta wczoraj działa dziś.
- **Rezygnacja** odnotowywana bez względu na źródło: odpowiedź STOP, kliknięcie w e-mailu
  albo ręczne oznaczenie przez pracownika.
- **Przejrzystość kosztu** — kreator na każdym kroku pokazuje, ilu klientów pasuje do filtrów,
  ilu odpadnie (brak zgody, brak numeru, limit) i ile kredytów pochłonie wysyłka.
- **Stopki** SMS i e-mail ustawiane raz dla całego studia.

---

## 2.3 Skrzynka poczty i kontakty

> **Ustalenie faktyczne.** Centralna skrzynka obsługuje **pocztę e-mail**. Nie ma w systemie
> integracji z wiadomościami prywatnymi Instagrama ani innego komunikatora — Instagram
> występuje wyłącznie jako narzędzie obserwacji konkurencji i przygotowywania postów
> (patrz 2.9). Kanałem „z internetu" wpiętym dziś do skrzynki są formularze kontaktowe
> ze strony WWW, które przychodzą jako poczta i są rozpoznawane automatycznie.

### Podłączenie skrzynki

Studio podaje swój adres, a system sam rozpoznaje dostawcę i sposób logowania — jeśli poczta
wymaga zgody przez okno dostawcy, onboarding od razu prowadzi na ten ekran zamiast pytać
o hasło. Hasła skrzynek są przechowywane w postaci zaszyfrowanej; jeżeli klucz szyfrujący
nie jest skonfigurowany, podłączenie skrzynki kończy się błędem, a nie zapisem hasła w słabo
chronionej formie. Konto może być aktywne, wymagać ponownego zalogowania albo zostać wyłączone.

### Jak działa synchronizacja

System nasłuchuje na serwerze pocztowym, więc nowa wiadomość pojawia się w skrzynce od razu,
bez odpytywania co kilka minut. Wiadomości są rozkładane na treść i załączniki (razem
z obrazkami osadzonymi w treści), przechodzą oczyszczenie z niebezpiecznego kodu, a przy zapisie
odcinana jest cytowana historia i stopka — na liście wątków widać samą nową treść, a nie ścianę
powtórzeń.

**Stan przeczytania działa w obie strony.** Jeśli pracownik otworzy wiadomość w telefonie,
w systemie przestanie ona być nieprzeczytana — i odwrotnie. Wysłane odpowiedzi lądują też
w folderze „Wysłane" na serwerze, więc historia korespondencji jest kompletna niezależnie
od tego, gdzie ktoś do niej zajrzy.

**Rozpoznawanie poczty automatycznej** odsiewa newslettery i autorespondery od realnych
zapytań, żeby lista spraw do obsługi nie zapełniała się rzeczami, na które nikt nie odpowiada.

### Praca w skrzynce

Dostępne są: wątki z wyszukiwaniem, oznaczanie jako przeczytane, odpowiadanie i wysyłanie
nowych wiadomości, podgląd i pobieranie załączników, etykiety własne, archiwizowanie wątków,
**osobiste podpisy** każdego pracownika oraz **korekta treści** — system poprawia styl i błędy
przed wysłaniem odpowiedzi.

### Konwersacja przypięta do klienta

To jest sedno modułu. Na podstawie adresu nadawcy system buduje **kartę kontaktu**: kim jest
ta osoba, jakie ma auta, kiedy była ostatnio i ile już u nas zostawiła. W wątku widać
oznaczenie, czy piszący jest klientem, zapytaniem, czy kimś zupełnie nowym, a jednym
kliknięciem można wyświetlić pozostałe wątki tej samej osoby. Do adresu można prowadzić
**notatki** z pełną historią zmian — kontekst zostaje przy sprawie, nie w głowie jednej osoby.

**Zapytanie z wątku**: rozmowę można zamienić w zapytanie ofertowe. Zapytanie e-mailowe
nie kopiuje przy tym wiadomości — jego historia *jest* wątkiem, więc nie ma dwóch
rozjeżdżających się wersji tej samej korespondencji.

**Formularze ze strony WWW** są rozpoznawane automatycznie: system wyciąga z takiej wiadomości
pola formularza i **sam zakłada zapytanie**, zamiast zostawiać je jako kolejny e-mail
do przeczytania. Formularz można też wpiąć bezpośrednio, z pominięciem poczty.

**Połączenia przychodzące** prowadzone są w tej samej kartotece: rejestracja połączenia,
przyjęcie, odrzucenie i uzupełnienie informacji. Działa też **wybieranie numeru z komputera** —
kliknięcie numeru w systemie powoduje, że sparowany telefon pracownika dostaje powiadomienie
i od razu dzwoni.

### Kontakty w telefonie

Baza klientów studia może być udostępniona telefonom pracowników jako książka adresowa,
synchronizowana automatycznie. Eksportowani są aktywni klienci z numerem telefonu, a dostęp
zabezpieczają osobne hasła urządzeń, które da się w każdej chwili unieważnić. Praktyczny efekt:
przy przychodzącym połączeniu na ekranie telefonu pojawia się nazwisko klienta zamiast
nieznanego numeru.

---

## 2.4 Karta klienta, historia i zdjęcia

### Co widać na karcie klienta

- Dane osobowe i firmowe — dane firmy można dopiąć i odpiąć bez usuwania osoby, więc klient
  prywatny, który założył działalność, nie musi być zakładany od nowa.
- Pojazdy klienta, przy czym jedno auto może mieć wielu właścicieli, a jedna osoba wiele aut —
  model odwzorowuje realne sytuacje: auto firmowe, wspólne, sprzedane innemu klientowi studia.
- **Historia wizyt** wraz z wykonanymi usługami.
- **Podsumowanie przychodu** — ile ten klient łącznie zostawił w studiu.
- Historia komunikacji: SMS-y, e-maile, wątki korespondencji i połączenia telefoniczne
  w jednym miejscu.
- Notatki, dokumenty, zgody wraz z ich aktualnym statusem oraz możliwość wysłania SMS-a
  wprost z karty.

> **Ochrona danych osobowych.** Dostęp do danych osobowych jest osobnym uprawnieniem,
> niezależnym od dostępu do samej pracy. Pracownik warsztatu bez tego uprawnienia normalnie
> korzysta z kalendarza i kart wizyt, ale zamiast nazwiska i telefonu widzi gwiazdki. Widoki,
> których sensem są dane osobowe — kartoteka klientów, dokumenty, faktury — nie są mu w ogóle
> pokazywane. To rozróżnienie pozwala dać dostęp do systemu całemu zespołowi, nie oddając
> bazy klientów.

### Kartoteka pojazdu

Pojazd ma własną kartę: dane techniczne, właścicieli (przypisywanych i odpinanych), historię
wizyt i rezerwacji, komentarze, notatki, dokumenty, zdjęcia i galerię. Auto można wyszukać
po numerze rejestracyjnym. Marka i model wpisane przez pracownika są dopasowywane do katalogu
pojazdów — do bazy trafia zawsze wartość słownikowa, nigdy surowy tekst z literówką, dzięki
czemu statystyki po markach są wiarygodne. Pojazdy mają też przypisany segment, co pozwala
różnicować cennik.

### Zdjęcia

Zdjęcia funkcjonują w trzech powiązanych miejscach: przy **wizycie**, przy **pojeździe**
(galeria zbiorcza wszystkich wizyt tego auta) oraz przy **wpisach zleceń zbiorczych**.
Usunięcie zdjęcia wizyty wymaga osobnego uprawnienia — zdjęcia bywają jedynym dowodem
w sporze i nie powinny znikać przez przypadek.

Pliki trafiają do magazynu w chmurze bezpośrednio z urządzenia, co pozwala wgrywać serie zdjęć
bez czekania, przy limicie 15 MB na plik. Przy zapisie zdjęcia z telefonu są automatycznie
obracane do właściwej orientacji i powstają miniatury, dzięki czemu galeria otwiera się
natychmiast również przy kilkuset zdjęciach.

**Galeria studia** to przekrojowy widok wszystkich zdjęć z filtrowaniem, przydatny przy
szukaniu materiału na social media. **Tagi zdjęć** pozwalają opisać ujęcie, a system podpowiada
oznaczenia już używane w studiu, żeby nie powstawało pięć wariantów tego samego słowa.

Dodanie i usunięcie zdjęcia są odnotowywane w historii aktywności firmy — z tym, że usunięcie
ma podwyższoną wagę i wyróżnia się na liście zdarzeń.

---

## 2.5 Dokumenty, zgody i RODO

### Protokoły przyjęcia i wydania pojazdu

Studio wgrywa własne wzory dokumentów w dwóch formatach: **formularz PDF**, który przechodzi
pełną ścieżkę — automatyczne wypełnienie danymi, podpis na tablecie i zapieczętowanie — oraz
**dokument HTML**, wypełniany danymi i przeznaczony do podglądu i druku. Podpis na tablecie
wymaga formatu PDF.

Wgrany wzór przechodzi **weryfikację**: system sprawdza, czy zawiera wszystkie wymagane pola.
Dokument oczekuje na sprawdzenie, zostaje zatwierdzony albo odrzucony — odrzucony nie wejdzie
do obiegu, więc nie zdarzy się przyjęcie auta na wzorze, w którym brakuje miejsca na przebieg.

System dostarcza gotowe wzory: protokół przyjęcia, protokół wydania, zgody marketingowe,
oświadczenie RODO i upoważnienie nadawcy SMS. Jeśli studio skasuje wzór potrzebny
do przyjmowania aut, system go odtworzy — obsługa nigdy nie zostaje bez dokumentu.

**Mapowanie pól** łączy pola formularza z danymi w systemie, dzięki czemu protokół wypełnia się
sam: dane klienta, auta, przebieg, zakres usług. **Reguły** decydują, który dokument i na jakim
etapie ma powstać, więc obsługa nie musi pamiętać, co drukować przy przyjęciu, a co przy
wydaniu. Do protokołu dopisywana jest też ocena stanu wizualnego pojazdu.

### Podpis elektroniczny na tablecie

Klient podpisuje palcem na tablecie ustawionym na recepcji ekranem w swoją stronę. Rozwiązanie
jest zaprojektowane pod kątem jednego konkretnego zarzutu, który może paść w sądzie: „mój podpis
został skopiowany i wklejony do innej umowy". Odpowiadają na to:

1. **Podpis związany z konkretnym dokumentem.** W chwili wysłania prośby o podpis system wylicza
   cyfrowy odcisk dokładnie tego dokumentu, który klient zobaczy. Odcisk jest liczony ponownie
   przy wyświetleniu na tablecie i jeszcze raz przy przyjęciu podpisu. Jeśli treść zmieniłaby się
   choćby o znak, podpis nie zostanie przyjęty. Klient podpisuje więc dokładnie to, co przeczytał.
2. **Jednorazowość sesji.** Każda prośba o podpis ma jednorazowy kod, który zużywa się w chwili
   złożenia podpisu. Przechwycenie i powtórzenie tej samej operacji jest niemożliwe.
3. **Podpis bez tła.** Kreski są zapisywane na przezroczystym tle, więc podpisu nie da się
   „wyjąć" i nałożyć na inny dokument bez śladu.
4. **Natychmiastowe niszczenie danych.** Obraz podpisu istnieje wyłącznie w pamięci — do archiwum
   trafia tylko gotowy, zapieczętowany dokument. Tablet zaraz po wysłaniu, niezależnie od tego,
   czy się udało, kasuje ze swojej pamięci obraz podpisu i treść dokumentu. Na urządzeniu
   na recepcji nie zostaje nic.
5. **Karta podpisu.** Do dokumentu dołączana jest strona ze ścieżką audytu: kiedy dokument
   wystawiono, kiedy go wyświetlono, kiedy złożono podpis.
6. **Pieczęć elektroniczna i znacznik czasu.** Gotowy dokument jest opatrywany kwalifikowaną
   pieczęcią oraz niezależnym znacznikiem czasu, co potwierdza, że od momentu podpisania nic
   w nim nie zmieniono.

**Podłączenie tabletu** zajmuje chwilę: pracownik generuje w ustawieniach sześciocyfrowy kod
ważny pięć minut, a tablet go wpisuje. Połączenie nie wygasa samo — kończy je wyłącznie
odłączenie urządzenia w ustawieniach, więc nikt nie musi parować tabletu co miesiąc. Pojedyncza
sesja podpisu wygasa po piętnastu minutach.

Dokument można też wysłać klientowi **na jego własny telefon**, linkiem SMS — wymaga to jednak
wykupionego zarówno modułu podpisów, jak i modułu komunikacji, bo link jedzie SMS-em. Osobno
zbierane są **podpisy pracowników**: każdy składa go raz, przez link onboardingowy, po czym
podpis pojawia się na dokumentach studia.

### Zgody klientów

Zgoda jest dokumentem **trwałym**: klient podpisuje ją raz i obowiązuje do odwołania —
w odróżnieniu od protokołu, który powstaje przy każdej wizycie. Zgoda deklaruje, których kanałów
dotyczy (e-mail, SMS), przy czym **w danym momencie tylko jedna aktywna zgoda studia może
obejmować dany kanał**, żeby nie powstała sytuacja dwóch sprzecznych zgód na tę samą rzecz.

**Nowa wersja zgody** może wymagać ponownego podpisu albo nie. Jeśli nie wymaga, klienci, którzy
podpisali starszą wersję, pozostają objęci zgodą i nie trzeba prosić ich ponownie. Jeśli wymaga —
obowiązuje wyłącznie podpis pod aktualnym brzmieniem.

**Kiedy system wyśle wiadomość marketingową:**

- Studio nie prowadzi żadnej zgody obejmującej dany kanał → wysyłka jest **dozwolona**.
- Studio prowadzi zgodę na ten kanał → klient musi ją mieć ważną.
- Klient nie ma ważnej zgody → wysyłka jest **wstrzymana**, a powód odnotowany, żeby dało się
  ustalić, dlaczego kampania ominęła te osoby.

**Odwołanie zgody** nie kasuje historii — zapisywana jest data cofnięcia, więc w razie kontroli
widać zarówno, że zgoda była, jak i od kiedy przestała obowiązywać. Podpisane zgody
przechowywane są w archiwum dokumentów, a każde nowe studio otrzymuje domyślną zgodę
marketingową gotową do użycia.

**Retencja danych**: dane obserwowanych profili społecznościowych są automatycznie kasowane
po 24 miesiącach, a wyprowadzone z nich wnioski po 12 miesiącach — usuwanie jest wpisane
w system, nie zależy od pamięci administratora.

**Dokumenty klienta i wizyty** tworzą wspólne archiwum plików przypiętych do osoby
i do konkretnego zlecenia; usuwanie dokumentu wymaga uprawnienia do operacji nieodwracalnych.

---

## 2.6 Finanse, kasa i fakturowanie

### Dokumenty sprzedaży i kosztów

System prowadzi własną ewidencję przychodów i kosztów. Dokument opisany jest kilkoma wymiarami:

| Wymiar | Wartości |
|---|---|
| Rodzaj | Paragon · Faktura · Dokument (inny) |
| Kierunek | Przychód · Koszt |
| Status | Opłacony · Oczekujący · Przeterminowany |
| Metoda płatności | Gotówka · Karta · Przelew · BLIK na numer · BLIK terminal · Inne |
| Pochodzenie | Z wizyty · Wprowadzony ręcznie |

Dwie reguły wynikają wprost z metody płatności. Płatność przelewem tworzy dokument
**oczekujący**, bo pieniądze jeszcze nie wpłynęły — pozostałe metody od razu opłacony. Tylko
płatność gotówką **wpływa na stan kasy**. Kwoty są pilnowane rachunkowo: suma netto i podatku
musi zgadzać się z kwotą brutto, inaczej dokument w ogóle nie powstanie. Usunięcie jest
odwracalne, więc pomyłkowo skasowany dokument da się przywrócić.

### Kasa

Każde studio ma jedną kasę, zakładaną automatycznie przy pierwszej operacji gotówkowej. Stan
kasy jest wypadkową wpływów gotówkowych, wypłat i korekt ręcznych, a pełna historia pokazuje,
skąd wzięła się aktualna kwota. Obsługa ma dostęp do bieżącego stanu, historii operacji oraz
do **wpłat, wypłat i korekt** — na przykład wypłaty na zakup materiałów albo wyrównania
po przeliczeniu szuflady. Operacje kasowe wymagają osobnego uprawnienia i trafiają do historii
aktywności firmy.

### Raporty finansowe

- Podsumowanie przychodów i kosztów w wybranym okresie.
- Rozbicie wpływów po metodach płatności — ile poszło przez terminal, ile gotówką, ile przelewem.
- Rejestr dokumentów przychodowych z możliwością **wyłączenia dokumentu ze statystyk bez
  kasowania go** — przydatne przy fakturach korygowanych lub rozliczeniach wewnętrznych.
- Wykrywanie dokumentów wprowadzonych dwukrotnie.
- Kategorie kosztów wraz z **regułami automatycznego przypisania po dostawcy** — faktura
  od stałego dostawcy chemii sama trafia do właściwej kategorii.

### Fakturowanie i KSeF

Integracja z Krajowym Systemem e-Faktur działa w obie strony.

**Faktury sprzedaży wystawiane w systemie.** Faktura jest budowana zgodnie z obowiązującym
schematem, numerowana według ustawień studia i wysyłana do KSeF. Obsługiwane są stawki 23%, 8%,
5%, 0% krajowe oraz zwolnione, z prawidłowym rozdzieleniem podstaw i kwot podatku. Podatek
liczony jest metodą „w stu", gdy pracownik wpisał cenę brutto — kwota brutto pozostaje dokładnie
taka, jaką uzgodniono z klientem.

| Stan faktury | Co oznacza dla obsługi |
|---|---|
| Do wysłania → Wysyłana → Przyjęta w KSeF | normalny przebieg, nic nie trzeba robić |
| Odrzucona | KSeF zakwestionował dane — wymaga poprawy i ponowienia |
| Czeka na ponowienie | KSeF był niedostępny; system sam dośle fakturę, najpóźniej następnego dnia roboczego |
| Niewysłana | faktura jest kompletna, ale świadomie zatrzymana przez użytkownika — system nigdy nie wyśle jej sam |

Rozróżnienie „niewysłana" od „czeka na ponowienie" jest celowe: pierwsze to decyzja, drugie
to awaria. Automat zajmuje się wyłącznie awariami. Ponowna wysyłka jest możliwa dla faktur
zatrzymanych, odrzuconych i czekających, ale nigdy dla już przyjętych — faktura nie może trafić
do KSeF dwa razy.

Poza tym dostępne są: **faktury korygujące**, pobranie urzędowego potwierdzenia odbioru, kody QR
do weryfikacji faktury, oznaczanie statusu zapłaty, notatki oraz roczne zestawienia sprzedaży.

> **Ochrona przed podwójnym fakturowaniem.** Jeżeli tę samą transakcję zafakturowano raz
> w systemie, a raz poza nim (np. w biurze rachunkowym), system wyłapie parę faktur o tym samym
> nabywcy, tej samej kwocie i zbliżonej dacie i zgłosi podejrzenie. **Nigdy nie scala ich ani
> nie ukrywa automatycznie** — obie są prawnie wiążące. Decyzję podejmuje użytkownik:
> potwierdza duplikat (wtedy nadmiarowa faktura jest wyłączana ze statystyk i trzeba ją
> skorygować do zera) albo odrzuca alert jako fałszywy.

**Faktury kosztowe pobierane z KSeF.** Co kwadrans system pobiera faktury zakupowe wystawione
na studio, więc koszty pojawiają się bez ręcznego wprowadzania. Każdy koszt można wyłączyć
ze statystyk lub przywrócić, oznaczyć jako zapłacony i opatrzyć notatką; koszty spoza KSeF
(paragony, opłaty) dodaje się ręcznie. System pilnuje przy tym limitów zapytań narzuconych przez
KSeF, żeby integracja nie została zablokowana w środku dnia.

**Faktura przy wydaniu auta.** Pozycje mogą różnić się od usług na wizycie — pracownik może
zmienić nazwy i kwoty, na przykład połączyć kilka czynności w jedną pozycję. Cenę podaje się
netto albo brutto, zależnie od tego, jak uzgodniono z klientem. Jeśli faktura obejmuje tylko
część kwoty (klient płaci część na firmę, część prywatnie), system wymaga wskazania metody
płatności dla reszty i **sam wystawia drugi dokument** na pozostałą kwotę. To, czy faktura ma
od razu iść do KSeF, decyduje ustawienie studia, które można nadpisać przy konkretnej wizycie.
Wizyty bezpłatne nie generują dokumentów sprzedaży.

### Pobieranie danych firmy po NIP

Po wpisaniu numeru NIP system pobiera dane kontrahenta wprost z rejestru REGON: pełną
i skróconą nazwę, formę prawną, kompletny adres, telefon, e-mail, stronę WWW, numer KRS, daty
rozpoczęcia, zawieszenia i zakończenia działalności oraz informację, czy firma jest aktywna.
Obsługa nie przepisuje danych z faktury ani ze strony klienta — i nie popełnia przy tym literówek
w nazwie na fakturze.

Integracja jest zabezpieczona na wypadek problemów po stronie rejestru: dane firm są
zapamiętywane na dobę, nieudane zapytanie jest ponawiane, a przy dłuższej awarii system czasowo
przestaje odpytywać rejestr i wraca do tego automatycznie. Efekt dla użytkownika: awaria
zewnętrznego rejestru nie zawiesza wystawiania faktur. Dane trafiają do kartoteki klienta
firmowego, do kontrahentów zleceń zbiorczych i na faktury.

### Płatności za system

Abonament i pakiety SMS opłaca się przez bramkę płatniczą. W środowisku bez skonfigurowanej
bramki zamówienia realizują się od razu, co pozwala testować cały przepływ bez realnych transakcji.

---

## 2.7 Konta pracowników i zadania

### Dostęp do systemu

- **Rejestracja studia** ze sprawdzeniem adresu e-mail, siły hasła, nazwy studia i akceptacji
  regulaminu.
- **Logowanie** z sesją, którą da się natychmiast unieważnić — odebranie dostępu zwolnionemu
  pracownikowi działa od razu.
- **Samodzielny reset hasła**: link ważny 30 minut, z minutową przerwą między kolejnymi prośbami.
- **Kod PIN** do szybkiego przełączania osoby na wspólnym stanowisku — bez wylogowywania
  i logowania od nowa, ale z zachowaniem informacji, kto faktycznie wykonał daną operację.

### Pracownicy

Dane kadrowe są oddzielone od konta w systemie: można prowadzić pracownika w ewidencji,
nie dając mu dostępu do aplikacji, i odwrotnie. Konto zakłada się osobno, wraz z wysłaniem
zaproszenia; można też zmienić hasło, zablokować konto lub je usunąć. Do tego dochodzą urlopy
i nieobecności z kalendarzem oraz ewidencja czasu pracy.

### Role i uprawnienia

Lista uprawnień jest **zamknięta** — administrator nie wymyśla własnych, tylko składa z gotowych
klocków role odpowiadające stanowiskom w studiu. Uprawnień jest 25 i każde istnieje dlatego,
że da się wskazać realne stanowisko, które go potrzebuje bez sąsiednich.

Uprawnienia są ze sobą **powiązane zależnościami**. Zaznaczenie jednego automatycznie włącza
wszystkie, bez których byłoby bezużyteczne, a system domyka zapisany zestaw tak, żeby rola zawsze
działała. Nie da się więc utworzyć roli „umawia wizyty, ale nie widzi klientów", która kończyłaby
się komunikatem o braku dostępu przy pierwszym kliknięciu.

Główny łańcuch odwzorowuje przepływ pracy recepcji: **podgląd wizyt i kalendarza → podgląd danych
osobowych → podgląd cen usług → umawianie i edycja wizyt**. Wszystkie operacje nieodwracalne —
usuwanie wizyt, klientów, zdjęć — wiszą pod umawianiem: nie można usuwać tego, czego nie można
tworzyć.

| Obszar | Uprawnienia |
|---|---|
| Wizyty i kalendarz | Podgląd wizyt i kalendarza · Podgląd danych osobowych · Podgląd cen usług w wizycie · Tworzenie i edycja wizyt oraz rezerwacji · Usuwanie wizyt i dokumentów · Usuwanie zdjęć · Usuwanie klientów i pojazdów · Zlecenia zbiorcze |
| Finanse | Faktury i dokumenty przychodowe · Zarządzanie kasą · Podgląd raportów finansowych · Powiadomienia o zarobku po wizycie |
| Pracownicy | Zarządzanie pracownikami i ich kontami · Płace |
| Komunikacja i marketing | Wysyłanie wiadomości do klientów · Marketing i social media |
| Pozostałe | Podgląd statystyk · Praca z zapytaniami · Podgląd i realizacja zadań · Tworzenie i przypisywanie zadań · Podgląd historii aktywności firmy |

**Rozstrzygnięcia warte uwagi:**

- **Zlecenia zbiorcze stoją osobno.** Osoba obsługująca kontrahentów B2B nie potrzebuje
  kalendarza studia ani kartoteki klientów detalicznych — i odwrotnie, recepcja nie musi widzieć
  stawek kontrahentów. To jedyne uprawnienie, które można nadać całkiem samodzielnie.
- **Powiadomienie o zarobku to nie raport.** Właściciel może chcieć dostawać na telefon kwotę
  po każdej zamkniętej wizycie, nie oddając nikomu wglądu w rozliczenia firmy. Osoba prowadząca
  księgowość może chcieć raportów bez budzika przy każdym odbiorze auta. Oba układy da się ustawić.
- **Historia aktywności jest osobnym uprawnieniem**, bo obejmuje zdarzenia kadrowo-płacowe
  i bezpieczeństwa — nie może „przychodzić w pakiecie" z dostępem do jakiegokolwiek modułu.
- **Kalendarz i pojazdy nie są osobnymi obszarami.** Wpis w kalendarzu *jest* wizytą albo
  rezerwacją, a dostęp do aut wynika z dostępu do wizyt i klientów.
- Właściciel ma dostęp do wszystkiego z definicji i nie podlega tym ograniczeniom.

### Plany i moduły dodatkowe

Obok uprawnień działa druga, niezależna bramka: co studio ma wykupione. **Plan podstawowy**
obejmuje kalendarz, wizyty, klientów, pojazdy, dokumenty i galerię. **Plan pełny** obejmuje
wszystko. Poza tym dostępne są moduły dokupywane osobno: asystent przy obsłudze zapytań,
monitoring konkurencji na Instagramie, automatyzacja kontaktu z klientem, kampanie marketingowe,
podpisy elektroniczne, kontrola nad finansami i statystyki.

Niektóre funkcje wymagają dwóch modułów naraz — prośba o podpis na telefonie klienta potrzebuje
zarówno podpisów elektronicznych, jak i komunikacji, bo link jedzie SMS-em. Kredyty SMS są
dostępne dla każdego modułu, który wysyła wiadomości. Zmiana planu obejmuje proporcjonalne
rozliczenie różnicy, a obniżenie planu wchodzi w życie z końcem opłaconego okresu, nie od razu.

### Zadania

Zadanie ma tytuł, opis, status wykonania, autora i osobę, która je zamknęła, wraz z datami.
Usunięcie jest odwracalne. **Przypisanie realizowane jest przez widoczność** — zadanie może być
skierowane do wszystkich w studiu, do wskazanych osób albo do wszystkich pełniących określoną
rolę (np. „wszyscy detailerzy"). Reguła jest jedna dla całego systemu: właściciel widzi wszystko,
autor widzi swoje zadania, a poza tym decyduje wskazanie odbiorcy.

- **Widok zespołowy**: lista zadań, archiwum wykonanych, tworzenie, edycja i usuwanie.
- **Zadanie z nagrania głosowego** — pracownik dyktuje zadanie do telefonu, a system zamienia je
  na wpis. Przydatne, gdy ktoś ma ręce w wosku i nie zapisze niczego ręcznie.
- **Widok pracownika**: moje zadania, licznik nieprzeczytanych, oznaczanie jako przeczytane
  i odhaczanie wykonania.

### Historia aktywności firmy

Jeden wspólny dziennik odpowiada na pytanie: kto, co, kiedy i na jaką kwotę. Obejmuje klientów,
pojazdy, wizyty, rezerwacje, usługi, zapytania, protokoły, zgody, połączenia, dane studia,
użytkowników, finanse, kasę, pracowników, zadania, bezpieczeństwo i obsługę door-to-door.
Zdarzenia mają przypisaną wagę — usunięcie wizyty wyróżnia się na liście inaczej niż dodanie
komentarza — więc przeglądanie dziennika nie wymaga czytania wszystkiego po kolei.

Wpisy są formułowane pełnymi zdaniami po polsku („Zmieniono zakres usług"), a historia
pojedynczej wizyty, klienta czy pojazdu pochodzi z tego samego dziennika, więc nigdzie nie ma
dwóch niezgodnych wersji zdarzeń.

---

## 2.8 Zlecenia zbiorcze

Moduł dla studiów obsługujących floty, komisy i podwykonawstwo, gdzie rozliczenie odbywa się nie
po każdym aucie, lecz raz na miesiąc, z całym kontrahentem. Jest świadomie odcięty od obsługi
klienta detalicznego — pracownik obsługujący ten obszar nie musi widzieć kalendarza ani kartoteki.

- **Kontrahenci**: nazwa, NIP, adres, osoba kontaktowa, e-mail, telefon, notatki i status
  współpracy.
- **Wpisy**: data usługi, marka, model, numer rejestracyjny, numer nadwozia, wykonane usługi
  z kwotami i stawką podatku, notatki oraz informacja, czy wpis został już rozliczony.
- **Własny cennik zleceń zbiorczych** — stawki dla kontrahentów bywają zupełnie inne niż
  detaliczne, więc prowadzone są oddzielnie.
- **Zdjęcia przy wpisie** — dokumentacja stanu auta z floty.
- **Podpowiadanie pojazdów** zarówno z kartoteki, jak i z wcześniejszych wpisów tego kontrahenta:
  przy dziesiątym aucie z tej samej floty wystarczy zacząć wpisywać numer rejestracyjny.

> **Odczyt numeru nadwozia ze zdjęcia.** Zamiast przepisywać siedemnastoznakowy numer VIN
> z tabliczki, pracownik robi zdjęcie, a system odczytuje numer. Wynik jest twardo sprawdzany:
> musi mieć dokładnie 17 dopuszczalnych znaków, w przeciwnym razie system nie zwraca nic i prosi
> o ręczne wpisanie. Nigdy nie „dopowiada" brakującego znaku — błędny numer nadwozia
> na rozliczeniu byłby gorszy niż jego brak.

**Rozliczenie miesiąca:**

- **Raport za okres** — zestawienie wykonanych usług dla kontrahenta.
- **Zamknięcie okresu** w jednym z dwóch trybów: wszystkie wpisy z zakresu dat albo wyłącznie
  te jeszcze nierozliczone. Wynikiem jest liczba wpisów, suma netto i brutto oraz opcjonalna
  wysyłka raportu e-mailem do kontrahenta — na adres z kartoteki albo jednorazowo wskazany inny.
- **Historia zamknięć** wraz z **zapisaną kopią raportu** dokładnie w tej postaci, w jakiej
  został wysłany. Przy sporze o rozliczenie sprzed pół roku studio pokazuje dokładnie ten dokument,
  który kontrahent dostał, a nie wygenerowany od nowa.

---

## 2.9 Śledzenie konkurencji

Obserwacja rynku obejmuje dwa obszary: **Instagram konkurencji** — działający i używany — oraz
**trendy wyszukiwania**, przygotowane, ale jeszcze nieuruchomione.

### Obserwacja profili konkurencji

Studio wskazuje profile, które chce śledzić; profil przechodzi zatwierdzenie, a jeden z nich
oznacza się jako **własny** — to on staje się punktem odniesienia we wszystkich porównaniach.
Jeśli pobranie danych dla profilu się nie powiedzie, można je ponowić ręcznie,
z dziesięciominutową przerwą chroniącą dzienny limit zapytań.

| Pobieranie danych | Kiedy |
|---|---|
| Pełna aktualizacja historii | w niedziele nad ranem |
| Lekka aktualizacja dzienna | codziennie o 6:30 |

Dane zapisywane są jako zdjęcia stanu w czasie — posty, liczba obserwujących, statystyki
tygodniowe — dzięki czemu widać nie tylko „ile mają teraz", ale też jak to się zmieniało.

**Co studio dostaje:**

| Widok | Zawartość |
|---|---|
| Przegląd | każda liczba pokazywana razem ze zmianą i punktem odniesienia — nigdy sama, bez kontekstu |
| Porównanie | własny profil zestawiony z obserwowanymi, ze szczegółem wybranego tygodnia |
| Puls konkurencji | lista tego, co wydarzyło się w minionym tygodniu |
| Treść | analiza publikacji oraz mapa pokazująca, w które dni i godziny konkurencja publikuje |
| Hashtagi, sugestie, werdykt tygodnia | używane oznaczenia, podpowiedzi dla własnego profilu, tygodniowe podsumowanie |

**Puls konkurencji** nie jest generowany przez model językowy — liczą go reguły z jawnymi
progami, bez limitów i bez opóźnień. Za „normę" danego profilu przyjmuje się jego własne
zachowanie z pół roku wstecz, więc porównanie dotyczy tego, jak konkurent zachowuje się względem
siebie, a nie względem innych. Wychwytywane zdarzenia to: własny post, własne milczenie,
przyspieszenie i spowolnienie publikacji, post wyraźnie powyżej normy, nowy temat oraz skok
i spadek liczby obserwujących.

**Werdykt tygodnia** to dokładnie jedno zdanie na profil — milczał, wyróżnił się, przyspieszył
albo publikował jak zwykle. Świadomie zastąpił listę zdarzeń, która przy jednym aktywnym koncie
zalewała ekran ośmioma wierszami o tym samym profilu.

> **Ochrona przed zalewem wniosków.** Wnioski formułowane są prostym językiem według schematu
> „co się stało → dlaczego to ważne → co możesz zrobić". Ten sam wniosek nie powstanie dwa razy,
> a tygodniowo studio dostaje **najwyżej pięć nowych**, wybranych według ważności. Przypuszczenia —
> na przykład „podejrzenie kupionych obserwujących" — są zawsze wprost oznaczone jako hipoteza,
> nigdy jako fakt.

### Przygotowywanie postów

System proponuje treść posta, ucząc się na przykładach: podobnych publikacjach, które wcześniej
zadziałały. Reakcje studia na obserwowane posty (podoba się / nie podoba) wpływają na dobór
inspiracji, więc propozycje z czasem lepiej trafiają w styl konkretnego studia.

### Trendy wyszukiwania — status: przygotowane, jeszcze nieuruchomione

Zaplanowany moduł pokazuje, ile osób szuka danej usługi detailingowej, jak zmienia się to w czasie
i jak wygląda w podziale na województwa — czyli kiedy warto zwiększyć budżet reklamowy i na czym.
Interfejs jest gotowy, a integracja z dostawcą danych o wyszukiwaniach przygotowana, ale nie jest
częścią uruchomionej wersji systemu. Do włączenia potrzebna jest decyzja i wdrożenie.

---

## 2.10 Pozostałe funkcjonalności

### Zapytania ofertowe i lejek sprzedaży

Zapytania trafiają do systemu z pięciu źródeł: korespondencji e-mail, formularza na stronie WWW,
połączenia telefonicznego, nagrania głosowego z telefonu pracownika oraz wpisu ręcznego. Każde
zapytanie ma przypisaną kategorię odpowiadającą temu, o co klient pyta: powłoka ceramiczna,
folia ochronna i oklejanie, korekta lakieru, detailing wnętrza, mycie i pielęgnacja, pełny
detailing lub inne.

> **Powody, dla których zapytanie nie kończy się zleceniem.** Lista jest zamknięta, bo tylko taką
> da się później zsumować. Kluczowe jest rozdzielenie dwóch rzeczy, które w bazie wyglądają tak
> samo, a w rachunku zupełnie inaczej.
>
> **Realna strata:** za drogo · brak wolnego terminu · klient przestał odpowiadać · wybrał
> konkurencję · za daleko od studia · tylko sprawdzał cenę · stan auta wyklucza usługę · sprzedał
> albo zmienił auto · inny powód.
>
> **To nie była strata:** sami odmówiliśmy · poza zakresem usług · odłożył decyzję na później ·
> spam. Wrzucenie odmów własnych do sumy strat kazałoby właścicielowi gonić przychód, którego
> świadomie nie chciał — i psuło statystykę tym bardziej, im lepiej studio kwalifikuje zapytania.
> „Odłożył decyzję" też nie jest stratą, tylko sprawą wciąż otwartą.

Poza tym: wycena pozycjami z ceną zamrażaną w chwili przygotowania oferty, oznaczenia własne,
notatki, **automatyczne rozpoznawanie auta z treści korespondencji** (do bazy trafia zawsze marka
ze słownika, nigdy surowy tekst klienta), przypisanie opiekuna, alert o sprawie, która stoi
w miejscu, **pomiar czasu pierwszej odpowiedzi** — jeden z najsilniejszych wskaźników
skuteczności sprzedaży — oraz analityka całego lejka: skąd przychodzą zapytania, o co pytają,
ile z nich zamienia się w zlecenia i dlaczego pozostałe nie.

### Cennik i pakiety

Cennik studia z kontrolą nazwy, ceny i stawki podatku. Przy edycji usługi **stara wersja jest
archiwizowana, a nie nadpisywana** — dzięki temu wizyta sprzed roku nadal pokazuje cenę, która
wtedy obowiązywała, i podwyżka cennika nie przepisuje historii ani statystyk. Osobno prowadzone
są pakiety usług.

### Statystyki

Przegląd działalności, rozbicie przychodu i liczby wizyt w czasie, statystyki pojedynczej
kategorii i pojedynczej usługi, lista wizyt z wybranego okresu oraz zestawienie **usług
nieprzypisanych do żadnej kategorii** — kontrola, czy raporty obejmują całość obrotu. Kategorie
usług są warstwą raportową nakładaną na cennik: właściciel sam decyduje, co składa się
na „pielęgnację", a co na „korektę lakieru". Usługi wpisywane ręcznie, spoza cennika, również
trafiają do statystyk, więc nie ma przychodu, który wypada z raportu.

### Pulpit

Podsumowanie dnia: umówione terminy, przychód i najważniejsze wskaźniki, odświeżane na bieżąco
bez przeładowywania strony. Osobno wyświetlane są **podpowiedzi** dotyczące niedokończonej
konfiguracji (np. brak wgranego wzoru protokołu), które można zamknąć na stałe, gdy studio
świadomie z czegoś nie korzysta.

### Powiadomienia na telefon

System wysyła powiadomienia na sparowane telefony pracowników w dwóch sytuacjach: przy
**wybieraniu numeru z komputera** oraz jako **informacja o zarobku** po każdej zamkniętej wizycie.
Ta druga trafia wyłącznie do osób mających do tego uprawnienie, sprawdzane po stronie odbiorcy —
nie ma znaczenia, kto zamknął wizytę, znaczenie ma, kto może poznać kwotę. Wysyłka nigdy nie
blokuje pracy: awaria usługi powiadomień nie zamieni zamkniętej wizyty w błąd.

### Telefon jako narzędzie pracy

Bez pełnego logowania, przez kod QR lub skróty na ekranie telefonu, pracownik ma dostęp do:
robienia zdjęć i zaznaczania uszkodzeń przy przyjęciu auta, **dyktowania notatek i zapytań**
(nagranie zamienia się w wpis w systemie), podpisywania dokumentów oraz obsługi wybierania numeru
z komputera.

### Ustawienia studia

Jeden ekran z sekcjami: dane firmy, cennik usług, role i uprawnienia, zespół, kolory rezerwacji,
dokumenty i zgody, ustawienia faktur, karta wizyty dla klienta, sposób numerowania wizyt,
etykiety skrzynki, formularze zapytań, kredyty SMS, tablety do podpisu, własny podpis,
synchronizacja kontaktów z telefonem oraz bezpieczeństwo.

### Konto demonstracyjne i zgłaszanie problemów

Konto demo z wygenerowanymi danymi pozwala pokazać system bez ryzyka wprowadzenia czegokolwiek
do realnej bazy; dane demonstracyjne są automatycznie sprzątane. Problem można zgłosić z poziomu
aplikacji — zgłoszenie trafia bezpośrednio do zespołu wsparcia.

### Bezpieczeństwo

System ogranicza liczbę zapytań z jednego źródła, wysyła nagłówki zabezpieczające przeglądarkę,
oznacza każdą operację identyfikatorem pozwalającym prześledzić ją w logach, kontroluje szczelność
podziału między studiami oraz ukrywa dane osobowe przed osobami bez odpowiedniego uprawnienia —
na poziomie, przez który nie da się przejść, obchodząc interfejs.

### Monitoring i analityka użycia

Poza monitoringiem technicznym (czasy odpowiedzi, błędy, wykorzystanie limitów integracji) system
prowadzi analitykę produktową z podziałem na studia. Kilka rozstrzygnięć decyduje o wiarygodności
tych liczb:

| Obszar | Reguła |
|---|---|
| Czas pracy w systemie | laptop zostawiony otwarty na noc dopisze półtorej minuty, nie szesnaście godzin; sesja bez aktywności zamykana jest wstecznie, na ostatnim śladzie obecności; sesje krótsze niż pół minuty i bez interakcji nie liczą się wcale |
| Nieużywane funkcje | funkcja uznawana jest za martwą po trzech miesiącach bez użycia, a raport jest oznaczany jako niewiarygodny, dopóki obserwacja nie trwa wystarczająco długo — kwartalne zestawienie po trzech dniach obserwacji wygląda tak samo jak funkcja nieużywana, a usunięcie go byłoby awarią |
| Błędy | zbierane wraz z kontekstem, z ograniczeniem liczby zgłoszeń, żeby jeden zapętlony błąd nie zasypał systemu |
| Wydajność | zapis danych analitycznych nigdy nie spowalnia pracy użytkownika — odbywa się w tle, partiami |
| Retencja | szczegółowe zapisy są kasowane po ustalonym czasie, dzienne podsumowania zostają na stałe |
| Dostęp | konsola analityczna jest zamknięta, dopóki nie zostanie świadomie otwarta osobnym kluczem; narzędzia raportowe czytają wyłącznie tabele metryk i nie mają dostępu do danych klientów, wizyt ani faktur |

---

## Mapa obszarów funkcjonalnych

| # | Obszar | Co obejmuje |
|---|---|---|
| 1 | Rezerwacje i wizyty | kalendarz, umawianie i edycja terminów, terminy cykliczne, przyjęcie pojazdu, mapa uszkodzeń, karta wizyty, karta dla klienta, sprzedaż dodatkowa, odbiór i dowóz auta |
| 2 | Komunikacja | automatyczne SMS-y i e-maile, wiadomości planowane ręcznie, SMS zwrotny, szablony, kampanie marketingowe, kredyty SMS |
| 3 | Skrzynka i kontakty | poczta studia, wątki, załączniki, karta kontaktu, zapytania z korespondencji, formularze WWW, połączenia przychodzące, kontakty w telefonie |
| 4 | Klienci, pojazdy, zdjęcia | kartoteka klienta i pojazdu, historia wizyt i przychodu, galeria, tagi zdjęć |
| 5 | Dokumenty, zgody, RODO | wzory protokołów, automatyczne wypełnianie, podpis elektroniczny na tablecie i u klienta, zgody i ich wersjonowanie, retencja danych |
| 6 | Finanse i faktury | dokumenty sprzedaży i kosztów, kasa, raporty, faktury sprzedaży i kosztowe w KSeF, pobieranie danych firmy po NIP, płatności |
| 7 | Pracownicy i zadania | konta i logowanie, ewidencja kadrowa, urlopy, czas pracy, role i uprawnienia, plany i moduły, zadania, historia aktywności |
| 8 | Zlecenia zbiorcze | kontrahenci, wpisy flotowe, własny cennik, odczyt numeru nadwozia, raport i zamknięcie miesiąca |
| 9 | Śledzenie konkurencji | obserwacja profili, analityka i puls konkurencji, werdykt tygodnia, przygotowywanie postów, trendy wyszukiwania (nieuruchomione) |
| 10 | Pozostałe | zapytania ofertowe i lejek, cennik, statystyki, pulpit, powiadomienia na telefon, telefon jako narzędzie pracy, ustawienia, konto demo, bezpieczeństwo, monitoring |
