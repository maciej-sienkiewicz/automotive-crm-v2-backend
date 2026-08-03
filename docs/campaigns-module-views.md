# Moduł Kampanii — Specyfikacja widoków i stanów UI

> Uzupełnienie `campaigns-module-design.md`. Obowiązuje design system AutoCRM
> (Inter, karty 14px / modale 16px / inputy 12px, navy hero z niebieską poświatą,
> eyebrow-labels 11px/700/uppercase, StatTile z 3px górnym akcentem, ikony Lucide 1.75,
> **zero emoji w interfejsie** — karty scenariuszy z §7.2 dokumentu głównego używają
> ikon Lucide, nie emoji).

---

## 1. Inwentarz widoków — co i kiedy widzimy

Moduł ma dokładnie **5 widoków**. Mniej ekranów = mniej miejsc, w których użytkownik może
się zgubić.

| # | Widok | Trasa | Kiedy użytkownik tu trafia |
|---|---|---|---|
| 1 | Lista kampanii (landing) | `/kampanie` | wejście do modułu — zawsze tu |
| 2 | Kreator (4 kroki) | `/kampanie/nowa` | "+ Nowa kampania" lub "Duplikuj" |
| 3 | Szczegóły kampanii | `/kampanie/{id}` | klik w wiersz listy |
| 4 | Edycja | `/kampanie/{id}/edycja` | "Edytuj" w szczegółach (gdy status pozwala, §4) |
| 5 | Ustawienia wysyłki | `/kampanie/ustawienia` | zakładka w nagłówku modułu |

Nie ma osobnego widoku "raport" — raport **jest** widokiem szczegółów kampanii zakończonej.
Ten sam ekran zmienia zawartość wraz ze statusem (§3), dzięki czemu użytkownik ma jeden
adres "prawdy" o kampanii przez cały jej cykl życia.

### 1.1 Lista kampanii

Struktura od góry:

1. **Nagłówek modułu**: tytuł "Kampanie", zakładki `Kampanie · Ustawienia`, po prawej
   primary button `Nowa kampania`.
2. **Rząd 4 StatTile** (eyebrow + hero numeral, tabular-nums):
   `AKTYWNE` · `ZAPLANOWANE` · `WYSŁANE (30 DNI)` · `KREDYTY SMS` (ostatni kafel z linkiem
   `Doładuj` gdy saldo < ~200; czerwony akcent gdy 0).
3. **Tabela kampanii** — kolumny: Nazwa (+ badge rodzaju, §2), Status (badge semantyczny),
   Odbiorcy (`117` lub `84 / 117` w trakcie wysyłki), Koszt (`234 kredyty` / `—` dla e-mail),
   Termin (kontekstowy: "za 3 dni · 24 gru, 10:00" dla zaplanowanej, "12 maj 2026" dla
   wysłanej, "codziennie sprawdzana" dla automatycznej aktywnej).
   Segmentowany filtr nad tabelą: `Wszystkie · Aktywne · Zaplanowane · Zakończone · Szkice`.
4. **Stan pusty** (pierwsze użycie): navy hero card — nagłówek "Wyślij pierwszą kampanię",
   jedno zdanie wartości ("Przypomnij klientom o sobie — świątecznie, po usłudze albo gdy
   dawno ich nie było."), CTA `Utwórz kampanię`. Pod spodem trzy **prawdziwe** karty
   scenariuszy (te z kreatora, klikalne — wchodzą w kreator z prefill). Żadnych
   fake-wykresów ani wydmuszek.

Uwaga o zgodach (ryzyko nr 1 z dokumentu głównego): jeśli < 30% klientów studia ma zgodę
marketingową, nad tabelą pokazujemy jednorazowo zamykalny info-banner:
"Zgodę marketingową ma 41 z 800 klientów. Kampanie dotrą tylko do nich. → Zbieraj zgody
przy wizycie" (link do modułu zgód). To jedyny banner w całym module.

### 1.2 Kreator

Pełnoekranowy widok (nie modal — 4 kroki w modalu to proszenie się o utratę danych),
stepper u góry: `Scenariusz → Odbiorcy → Treść → Podsumowanie`. Kroki opisane w dokumencie
głównym §7.2; tu doprecyzowania stanów:

- Stepper pozwala wracać do ukończonych kroków; przejście dalej blokują tylko twarde błędy
  (pusta treść, 0 odbiorców).
- Szkic zapisuje się automatycznie po każdym kroku (status DRAFT od pierwszego "Dalej") —
  porzucenie kreatora nie traci pracy; wiersz "Szkic" na liście z akcją "Dokończ".
- Krok 2: licznik odbiorców przelicza się z debounce 500 ms; podczas przeliczania liczba
  dostaje skeleton, **nie** znika (bez skoków layoutu).

### 1.3 Ustawienia wysyłki

Jedna kolumna, karty sekcji: Nadawca SMS (nazwa + dokument autoryzacyjny — przeniesione
z obecnego miejsca) · Godziny ciszy · Limit częstotliwości · Stopki SMS/e-mail.
Każda sekcja z eyebrow i jednozdaniowym opisem konsekwencji
("Wiadomości zaplanowane na noc wyślemy następnego dnia rano.").

---

## 2. Komunikacja rodzajów kampanii (ONE_TIME vs AUTOMATIC)

Nazwy techniczne nie istnieją w UI. Słownik:

| W kodzie | W interfejsie | Definicja jednym zdaniem (używana wszędzie tam, gdzie pada nazwa) |
|---|---|---|
| ONE_TIME | **Jednorazowa** | "Wysyłana raz — teraz albo w wybranym terminie." |
| AUTOMATIC | **Automatyczna** | "Działa stale — sama wysyła wiadomość każdemu klientowi, gdy spełni warunek, np. 180 dni po usłudze." |

Rodzaj komunikujemy w **trzech miejscach i zawsze tak samo**:

1. **Krok 1 kreatora — jedyne miejsce edukacji.** Karty scenariuszy są pogrupowane w dwie
   sekcje z eyebrow: `JEDNORAZOWE` (Świąteczna, Reaktywacja, Wybrane auta, VIP, Własna)
   i `AUTOMATYCZNE` (Przypomnienie po usłudze, Własna automatyczna). Nad każdą grupą
   zdanie-definicja z tabeli powyżej. Ikony Lucide: `send` (jednorazowa), `repeat`
   (automatyczna). Użytkownik nie wybiera abstrakcyjnego "typu" — wybiera scenariusz,
   a rodzaj jest jego cechą. To usuwa całą barierę pojęciową: nikt nie musi rozumieć
   taksonomii, żeby zrobić kampanię świąteczną.
2. **Badge przy nazwie** na liście i w szczegółach: ikona + `Jednorazowa` / `Automatyczna`
   (outline badge, neutralny kolor — rodzaj to fakt, nie stan). Tooltip = zdanie-definicja.
3. **Język statusów rozdziela światy**: jednorazowe są "zaplanowane / wysłane",
   automatyczne "działają / są wstrzymane". Nigdy odwrotnie — to językowo utrwala różnicę
   bez ani jednego akapitu instrukcji.

Czego świadomie **nie** robimy: onboardingowych tourów, modali "poznaj rodzaje kampanii",
ilustracji z robotami. Jedno zdanie we właściwym miejscu wystarcza.

---

## 3. Zaplanowana kampania a zmienność listy — decyzja: **przeliczamy w dniu wysyłki**

### Decyzja architektoniczna

Dla kampanii zaplanowanej lista odbiorców **nie jest zamrażana w momencie planowania**.
Definicja odbiorców (filtry + ręczne korekty) jest trwała, a materializacja do
`campaign_recipients` następuje w chwili startu wysyłki. Uzasadnienie:

- **I tak nie wolno nam wysłać po nieaktualnych danych** — zgody i opt-out muszą być
  sprawdzone na moment wysyłki (RODO). Skoro re-walidacja jest obowiązkowa, snapshot
  z dnia planowania byłby tylko iluzją stabilności.
- Kampania "reaktywacja: brak wizyty 180 dni" zaplanowana 2 tygodnie naprzód **powinna**
  objąć klientów, którzy przekroczą próg w międzyczasie — i pominąć tych, którzy właśnie
  przyszli na wizytę. To jest zachowanie zgodne z intencją, nie anomalia.
- Ręczne korekty (`manual.include/excludeCustomerIds`) są częścią definicji, więc
  **przeżywają przeliczenie** — wykluczony Kowalski pozostaje wykluczony.

### Jak to komunikujemy (bez straszenia)

1. **Krok 4 kreatora**, przy wyborze "Zaplanuj na [data]" pod polem daty pojawia się stały
   opis (nie warunek, nie alert): *"Listę odbiorców przeliczymy ponownie tuż przed wysyłką —
   obejmie klientów spełniających warunki w dniu wysyłki. Twoje ręczne wykluczenia zostaną
   zachowane."* Recap pokazuje liczbę jako **"117 odbiorców (stan na dziś)"**.
2. **Szczegóły kampanii SCHEDULED**: sekcja odbiorców ma nagłówek
   `PROGNOZOWANI ODBIORCY` (eyebrow) zamiast "Odbiorcy", liczbę z dopiskiem "stan na dziś,
   odświeżana codziennie" i listę prognozowaną. Liczba w tle przeliczana raz dziennie —
   właściciel wchodzący dzień przed świętami widzi aktualną prognozę, nie relikt sprzed
   dwóch tygodni.
3. **Po wysyłce** sekcja zmienia nagłówek na `ODBIORCY` i pokazuje stan faktyczny ze
   statusami per osoba — od tego momentu lista jest historycznym zapisem i nigdy się
   nie zmienia.

Słowa "snapshot", "materializacja", "przeliczenie audiencji" nie występują w UI.

---

## 4. Edycja — macierz uprawnień i widoki

### 4.1 Co wolno w którym statusie

| Status | Edycja | Zakres | Inne akcje |
|---|---|---|---|
| Szkic (DRAFT) | pełna | wszystko, wraca się do kreatora w dowolny krok | Usuń |
| Zaplanowana (SCHEDULED) | pełna | wszystko — nic jeszcze nie wyszło, więc nie ma czego chronić | Anuluj, Wyślij teraz |
| W trakcie (SENDING) | brak | — | **Zatrzymaj wysyłkę** (pozostali PENDING → status "zatrzymano"; wysłanych nie cofniemy — copy mówi to wprost) |
| Zakończona / Anulowana / Błąd | brak | — | Duplikuj (→ nowy szkic z kompletem ustawień) |
| Automatyczna: Działa (ACTIVE) | pełna, z zastrzeżeniem | treść, odbiorcy, warunek | Wstrzymaj, Archiwizuj |
| Automatyczna: Wstrzymana (PAUSED) | pełna | jw. | Wznów, Archiwizuj |
| Zarchiwizowana | brak | — | Duplikuj |

Zasada nadrzędna: **edytowalne jest wszystko, z czego jeszcze nic nie wyszło do klienta**.
Historia (wysłane wiadomości) jest nienaruszalna.

Zastrzeżenie dla automatycznej ACTIVE: zmiany działają "od teraz" — wiadomości już
zakolejkowane, ale nie wysłane, są renderowane z aktualnej treści w momencie wysyłki
(spójne z silnikiem, który i tak re-waliduje przy dispatchu). Przy zapisie edycji aktywnej
kampanii pokazujemy jedno zdanie potwierdzenia: "Zmiany obejmą wszystkie wiadomości
wysyłane od tej chwili."

### 4.2 Widok szczegółów (podgląd)

Jeden layout, zawartość sterowana statusem:

**Nagłówek (stały):** nazwa + badge rodzaju + badge statusu; prawa strona: akcje wg macierzy
(primary = najbardziej prawdopodobna: `Dokończ` dla szkicu, `Edytuj` dla zaplanowanej,
`Wstrzymaj` dla działającej, `Duplikuj` dla zakończonej).

**Pas metryk (4 StatTile), zawartość wg statusu:**

| Status | Kafle |
|---|---|
| Zaplanowana | Prognozowani odbiorcy · Szacowany koszt · Termin wysyłki ("za 3 dni") · Kanał |
| W trakcie | Wysłano (`84 / 117`, licznik odświeżany) · Błędy · Pominięci · Zużyte kredyty |
| Zakończona | Wysłano · Pominięci (z powodem w tooltipie) · Błędy · Koszt |
| Automatyczna aktywna | Wysłano łącznie · W tym miesiącu · Następne sprawdzenie ("jutro 10:00") · Koszt łącznie |

**Sekcje pod pasem (kolejność stała):**

1. `TREŚĆ` — podgląd wiadomości w ramce imitującej dymek SMS / prostą kopertę e-mail,
   z podstawionymi danymi przykładowego prawdziwego odbiorcy; obok meta: liczba segmentów,
   nadawca, stopka.
2. `WARUNEK` (tylko automatyczne) — zdanie w ludzkim języku: "180 dni po usłudze
   *Powłoka ceramiczna*, o 10:00, pomijając klientów, którzy byli w międzyczasie."
3. `ODBIORCY` / `PROGNOZOWANI ODBIORCY` (§3) — tabela: klient, kanał/adres, status
   (po wysyłce), powód pominięcia wprost w wierszu ("brak zgody SMS"), wyszukiwarka.
   Filtry zapisane nad tabelą jako chipy w ludzkim języku ("Ostatnia wizyta: ponad 180 dni
   temu", "Marka: BMW lub Audi") — te same chipy co w kreatorze, więc użytkownik uczy się
   jednej reprezentacji.
4. `PRZEBIEG` — skromna oś czasu: utworzono → zaplanowano → rozpoczęto → zakończono
   (dla automatycznych: historia miesięcy z liczbą wysłanych). Bez wykresów, dopóki nie
   mamy atrybucji z Fazy 3 — wtedy dojdzie sekcja `EFEKT` (wizyty w 30 dni po wysyłce).

### 4.3 Widok edycji

**Edycja = kreator z odblokowanym stepperem**, otwarty na kroku 2, z nagłówkiem
"Edytujesz: {nazwa}" i przyciskami `Zapisz zmiany` / `Odrzuć`. Zero osobnych formularzy —
jedna implementacja, jedno miejsce nauki. Różnice vs tworzenie: krok 1 (scenariusz) jest
ukryty (scenariusz to tylko prefill, po utworzeniu nie ma znaczenia), krok 4 pokazuje
diff istotnych zmian ("Odbiorcy: 117 → 96 · Koszt: 234 → 192 kredyty") zamiast pełnego
recapu.

---

## 5. Jak nie zrobić "AI slop" — reguły egzekwowalne w code review

Slop to nie estetyka, tylko brak decyzji. Konkretne zakazy i nakazy dla tego modułu:

1. **Zero emoji w produkcie.** Ikony wyłącznie Lucide 1.75 (+ firmowa sylwetka coupé dla
   pojazdów). Emoji w tabelach scenariuszy z dokumentu głównego → zastąpione ikonami
   (`gift`, `repeat`, `moon`, `car`→sylwetka, `gem`, `settings-2`).
2. **Jeden akcent kolorystyczny** — brand blue `#0ea5e9`. Statusy używają wyłącznie
   semantycznych tokenów systemu (success/warning/error/neutral). Zakaz fioletowych
   gradientów, "glassmorphizmu", tęczowych badge'y — czyli domyślnej palety generatorów.
3. **Liczby są bohaterem, nie dekoracją.** Hero numerals 800/-1px/tabular-nums pokazują
   wyłącznie liczby, które istnieją w bazie. Zakaz kafli-wypełniaczy ("Współczynnik
   zaangażowania — wkrótce"), zakaz wykresów bez danych źródłowych. Nie mamy danych
   doręczeń → nie ma wykresu doręczeń. Sekcja `EFEKT` pojawi się dopiero z Fazą 3.
4. **Copy pisane od konsekwencji, nie od funkcji.** Każdy opis w UI mówi, co się stanie,
   językiem właściciela studia: nie "Konfiguruj parametry segmentacji odbiorców", tylko
   "Wybierz, kto dostanie wiadomość". Zakaz słów: *segmentacja, audiencja, snapshot,
   trigger, engagement, boost*. Sentence case, tryb rozkazujący na przyciskach, format
   `1 850,00 zł`, `+48 XXX XXX XXX`.
5. **Gęstość informacji jak w narzędziu, nie jak na landing page'u.** Tabela kampanii
   mieści 10+ wierszy na ekranie laptopa; bez kart-płytek z gigantycznym paddingiem po
   3 na rząd. Negative space tak — pustosłowie nie.
6. **Puste stany prowadzą do akcji, nie ilustrują.** Wzór: tytuł + jedno zdanie + CTA
   (+ ewentualnie realne karty scenariuszy). Zakaz stockowych ilustracji "ludzik z lupą".
7. **Mikrointerakcje wg systemu i tylko one:** lift `translateY(-1px)/180ms` na przyciskach,
   skeleton przy przeliczaniu licznika, focus ring 3px. Zakaz spinnerów pełnoekranowych,
   konfetti po wysłaniu kampanii i animowanych gradientów.
8. **Jedna reprezentacja pojęcia w całym module.** Filtr odbiorców wygląda identycznie
   (chip w ludzkim języku) w kreatorze, szczegółach i edycji; definicja rodzaju kampanii
   to zawsze to samo zdanie. Slop poznaje się m.in. po tym, że każdy ekran wymyśla własny
   wzorzec.
9. **Test wąchania przed merge:** każdy nowy ekran stawiamy obok `ui_kits/crm_app/index.html`.
   Jeśli nie wygląda jak ten sam produkt — wraca. Review UI robimy na zrzutach z realnymi
   danymi (długie nazwiska, 0 kredytów, 800 klientów bez zgód), nie na happy path.
