# DetailBoost CRM

System do prowadzenia studia detailingu samochodowego: od zapytania klienta, przez kalendarz,
przyjęcie auta i pracę na stanowisku, po fakturę, rozliczenie i analizę wyników.

Dokument opisuje funkcje działające w uruchomionej wersji systemu.

---

# Część I. Codzienna praca

Ścieżka jednego auta przez studio: umówienie terminu, przyjęcie, praca na stanowisku,
wydanie klientowi.

## Kalendarz i rezerwacje

Kalendarz pokazuje w jednym widoku wszystko, co zajmuje czas studia: rezerwacje, wizyty w toku,
urlopy i nieobecności zespołu, zdarzenia własne oraz trasy odbioru i dowozu aut. Dostępne są
widoki miesiąca, tygodnia i dnia, terminy przesuwa się przeciągnięciem.

Kolory rezerwacji tworzy każde studio samodzielnie. Legenda może opisywać rodzaj usługi,
stanowisko albo priorytet. Nieużywane kolory da się zarchiwizować, jeden jest domyślny.

### Rezerwacja i wizyta to dwie różne rzeczy

Rezerwacja jest obietnicą terminu, wizyta autem stojącym w warsztacie. Rozdzielenie pilnuje,
żeby statystyki nie mieszały umówionych terminów z wykonaną pracą, a wiadomości nie dziękowały
za wizytę, na którą nikt nie przyjechał.

| Stan rezerwacji | Znaczenie |
|---|---|
| Utworzona | termin umówiony, auto jeszcze nie przyjechało |
| Zamieniona na wizytę | klient przyjechał, przeprowadzono przyjęcie pojazdu |
| Odwołana | anulowana przez studio lub klienta, można ją przywrócić |
| Porzucona | termin minął, auto nie dojechało |

Stan porzucenia nadaje system. Co kwadrans sprawdza rezerwacje, których termin minął wczoraj
lub wcześniej i które wciąż czekają na przyjęcie auta. Kalendarz nie zapełnia się nieaktualnymi
wpisami, a właściciel widzi skalę nieodbytych wizyt.

### Umawianie terminu

Przed zapisaniem system sprawdza komplet warunków: istnienie klienta i pojazdu, brak duplikatu
przy nowym kliencie, dane kontaktowe, stawki podatku na pozycjach, wymagalność ceny ręcznej,
dostępność wybranego koloru. Rezerwacja powstaje kompletna albo obsługa dostaje informację,
czego brakuje.

Każdą usługę można wycenić inaczej niż w cenniku: rabat lub narzut procentowy, kwota doliczana
do ceny netto albo brutto, całkowite nadpisanie ceny netto albo brutto. Kwota wpisana przez
pracownika jest wiążąca. Przy cenie brutto system nie przelicza jej wstecz, więc uzgodnione
201 zł zostaje na dokumencie jako 201 zł.

### Terminy cykliczne

Dla klientów obsługiwanych regularnie tworzy się całą serię wizyt naraz. Pojedynczy termin można
odczepić od serii i zmienić niezależnie, bez ruszania pozostałych.

### Zmiany i odwołania

Rezerwację da się w całości edytować, zmienić jej stan, odwołać i przywrócić. Usunięcie ma dwa
poziomy: zwykłe, po którym wpis znika z widoków, ale zostaje w historii, oraz trwałe, wymagające
osobnego uprawnienia. Przy każdej rezerwacji można włączyć przypomnienie SMS niezależnie
od ustawień całego studia oraz nadać jej własny tytuł w kalendarzu.

### Dostępność zespołu

Urlopy i nieobecności nakładają się na kalendarz jako osobna warstwa, więc recepcja widzi, kogo
w danym dniu nie ma. Osobno prowadzona jest ewidencja czasu pracy z wpisami dziennymi,
miesięcznymi okresami rozliczeniowymi i automatycznym liczeniem nadgodzin ponad ośmiogodzinny
dzień.

## Przyjęcie pojazdu

Auto przyjmuje się z wcześniejszej rezerwacji albo bezpośrednio, bez umówionego terminu. W obu
przypadkach rejestrowane są przebieg, przekazanie kluczy i dokumentów, uwagi z oględzin, notatki
techniczne, zdjęcia oraz mapa uszkodzeń.

### Mapa uszkodzeń

Punkty nanoszone na schemat nadwozia tworzą trwały obraz dołączany do dokumentacji wizyty.
To materiał dowodowy w sporze o rysę, której wcześniej nie było.

### Przyjęcie z telefonem w ręku

Pracownik skanuje kod QR i na swoim telefonie robi zdjęcia oraz zaznacza uszkodzenia. Komputer
na recepcji aktualizuje formularz na bieżąco, bez przenoszenia plików. Sesja zdjęciowa jest ważna
dwie godziny i mieści do dwudziestu zdjęć, niedokończone sesje są sprzątane automatycznie.

Zdjęcia trafiają do magazynu w chmurze bezpośrednio z urządzenia, więc seria wgrywa się bez
czekania. Limit to 15 MB na plik. Zdjęcia z telefonu są automatycznie obracane do właściwej
orientacji, powstają też miniatury.

## Prowadzenie wizyty

```
W realizacji ─→ Gotowa do odbioru ─→ Zakończona ─→ Zarchiwizowana
      │                  │
      │                  └─→ W realizacji (trzeba jeszcze dorobić)
      └─→ Odrzucona ────────────────────→ Zarchiwizowana
```

Ścieżka jest zamknięta, nie da się przeskoczyć etapu ani cofnąć wizyty w dowolne miejsce.
Auto gotowe do odbioru może wrócić do prac, ale wizyty zakończonej nie da się odkończyć.

### Karta wizyty

Karta jest kompletnym zapisem zlecenia i zawiera:

- zamrożone dane pojazdu: marka, model, numer rejestracyjny, VIN, rocznik, kolor. Późniejsza
  zmiana danych w kartotece nie przepisuje historii,
- listę usług z cenami,
- zdjęcia i mapę uszkodzeń,
- komentarze zespołu,
- notatkę techniczną z pełną historią zmian,
- dokumenty i protokoły,
- planowaną i faktyczną datę zakończenia oraz datę odbioru,
- dziennik zdarzeń.

### Zmiana zakresu prac

Gdy w trakcie pracy trzeba coś dodać, usunąć albo zmienić cenę, zmiana trafia na listę propozycji
i czeka na akceptację. Do wartości wizyty liczą się wyłącznie pozycje potwierdzone: nowa usługa
czekająca na zgodę nie podbija kwoty, a zmieniona cena liczy się po starej stawce do momentu
akceptacji.

Akceptacja przychodzi od pracownika albo bezpośrednio od klienta, SMS-em zwrotnym. Klient dostaje
wiadomość z listą usług i kwotą, odpisuje TAK, a system dopasowuje odpowiedź po numerze telefonu
i zatwierdza wszystkie czekające pozycje. Jeśli zakres zmienił się w międzyczasie jeszcze raz,
starsza prośba przestaje obowiązywać. Powstaje udokumentowana zgoda na rozszerzenie prac,
bez telefonu i bez papieru.

### Galeria i tagi

Zdjęcia funkcjonują przy wizycie, przy pojeździe jako galeria zbiorcza wszystkich jego wizyt oraz
przy wpisach zleceń zbiorczych. Galeria studia daje przekrojowy widok wszystkich zdjęć
z filtrowaniem, przydatny przy szukaniu materiału na social media. Tagi opisują ujęcie, a system
podpowiada oznaczenia już używane w studiu.

Usunięcie zdjęcia wizyty wymaga osobnego uprawnienia i jest odnotowywane w historii aktywności
z podwyższoną wagą.

## Karta wizyty dla klienta

Klient dostaje link do prywatnej strony swojej wizyty. Otwiera ją bez logowania i bez instalowania
czegokolwiek, widzi status swojego auta oraz usługi dodatkowe zaproponowane przez studio.

Kliknięcie „chcę" uruchamia potwierdzenie, po którym usługi dopisują się do zlecenia. Studio widzi,
czy i kiedy klient otworzył kartę oraz czy link do niego dotarł.

## Wydanie auta

Kolejne kroki to oznaczenie auta jako gotowego do odbioru, co wysyła powiadomienie do klienta,
wydanie pojazdu, przy którym powstają dokumenty sprzedaży i opcjonalnie faktura, oraz archiwizacja.
Wizytę można też odrzucić albo anulować, jeśli nie została jeszcze rozpoczęta.

### Odbiór i dowóz auta

Studia oferujące obsługę door to door prowadzą adres odbioru, adres dostawy, notatki dla kierowcy
i etapy: zaplanowane, w drodze po auto, auto odebrane, w drodze do klienta, dostarczone. Trasy
pokazują się w kalendarzu jako osobna warstwa, więc dzień kierowcy planuje się razem
ze stanowiskami.

---

# Część II. Pieniądze i dokumenty

Rozliczenie wizyty, kasa, fakturowanie w KSeF, protokoły, podpis elektroniczny i zgody klientów.

## Finanse i kasa

### Dokumenty sprzedaży i kosztów

| Wymiar | Wartości |
|---|---|
| Rodzaj | Paragon, Faktura, Dokument |
| Kierunek | Przychód, Koszt |
| Status | Opłacony, Oczekujący, Przeterminowany |
| Metoda płatności | Gotówka, Karta, Przelew, BLIK na numer, BLIK terminal, Inne |
| Pochodzenie | Z wizyty, Wprowadzony ręcznie |

Płatność przelewem tworzy dokument oczekujący, pozostałe metody od razu opłacony. Tylko gotówka
wpływa na stan kasy. Suma netto i podatku musi zgadzać się z kwotą brutto, inaczej dokument
nie powstanie. Usunięcie jest odwracalne.

### Kasa

Każde studio ma jedną kasę, zakładaną automatycznie przy pierwszej operacji gotówkowej. Stan
wynika z wpływów, wypłat i korekt ręcznych, a pełna historia pokazuje, skąd wzięła się aktualna
kwota. Obsługa ma dostęp do bieżącego stanu, historii operacji oraz do wpłat, wypłat i korekt.
Operacje kasowe wymagają osobnego uprawnienia.

### Raporty

- podsumowanie przychodów i kosztów w wybranym okresie,
- rozbicie wpływów po metodach płatności,
- rejestr dokumentów przychodowych z możliwością wyłączenia dokumentu ze statystyk bez kasowania go,
- wykrywanie dokumentów wprowadzonych dwukrotnie,
- kategorie kosztów z regułami automatycznego przypisania po dostawcy.

## Faktury i KSeF

Integracja z KSeF działa w obie strony: faktury sprzedaży wychodzą z systemu, faktury kosztowe
przychodzą do niego same.

### Faktura przy wydaniu auta

Pozycje faktury mogą różnić się od usług na wizycie, pracownik może zmienić nazwy i kwoty albo
połączyć kilka czynności w jedną pozycję. Cenę podaje się netto albo brutto, zależnie od ustaleń
z klientem.

Jeśli faktura obejmuje tylko część kwoty, na przykład gdy klient płaci część na firmę, a część
prywatnie, system wymaga metody płatności dla reszty i sam wystawia drugi dokument. Wysyłka
do KSeF idzie zgodnie z ustawieniem studia, które można nadpisać przy konkretnej wizycie. Wizyty
bezpłatne nie generują dokumentów sprzedaży.

### Wysyłka i jej stany

Obsługiwane są stawki 23%, 8%, 5%, 0% krajowe i zwolnione, z prawidłowym rozdzieleniem podstaw
i kwot podatku. Podatek liczony metodą „w stu" przy cenie brutto pozostawia kwotę brutto dokładnie
taką, jaką uzgodniono.

| Stan | Co oznacza dla obsługi |
|---|---|
| Do wysłania, Wysyłana, Przyjęta w KSeF | normalny przebieg, nic nie trzeba robić |
| Odrzucona | KSeF zakwestionował dane, wymaga poprawy i ponowienia |
| Czeka na ponowienie | KSeF był niedostępny, system dośle fakturę najpóźniej następnego dnia roboczego |
| Niewysłana | faktura kompletna, świadomie zatrzymana przez użytkownika, system nie wyśle jej sam |

Rozróżnienie stanu niewysłanego od czekającego na ponowienie jest celowe. Pierwsze to decyzja,
drugie awaria, a automat zajmuje się wyłącznie awariami. Ponowna wysyłka jest możliwa dla faktur
zatrzymanych, odrzuconych i czekających, nigdy dla już przyjętych.

Poza tym dostępne są faktury korygujące, pobranie UPO, kody QR do weryfikacji, oznaczanie statusu
zapłaty, notatki oraz roczne zestawienia sprzedaży.

### Ochrona przed podwójnym fakturowaniem

Jeżeli tę samą transakcję zafakturowano raz w systemie, a raz poza nim, system wyłapie parę faktur
o tym samym nabywcy, tej samej kwocie i zbliżonej dacie. Nigdy nie scala ich ani nie ukrywa
automatycznie, bo obie są prawnie wiążące. Użytkownik potwierdza duplikat, co wyłącza nadmiarową
fakturę ze statystyk, albo odrzuca alert.

### Faktury kosztowe

Co kwadrans system pobiera faktury zakupowe wystawione na studio, więc koszty pojawiają się bez
ręcznego wprowadzania. Każdy koszt można wyłączyć ze statystyk lub przywrócić, oznaczyć jako
zapłacony i opatrzyć notatką. Koszty spoza KSeF dodaje się ręcznie. System pilnuje limitów
zapytań, żeby integracja nie została zablokowana w środku dnia.

### Dane firmy po NIP

Po wpisaniu NIP system pobiera dane kontrahenta z rejestru REGON: pełną i skróconą nazwę, formę
prawną, kompletny adres, telefon, e-mail, stronę WWW, numer KRS, daty rozpoczęcia, zawieszenia
i zakończenia działalności oraz informację o aktywności firmy.

Dane firm są zapamiętywane na dobę, nieudane zapytanie jest ponawiane, a przy dłuższej awarii
rejestru system czasowo przestaje go odpytywać i wraca do tego automatycznie. Awaria po stronie
GUS nie zawiesza wystawiania faktur. Pobrane dane zasilają kartotekę klienta firmowego,
kontrahentów zleceń zbiorczych i faktury.

## Protokoły

Studio wgrywa własne wzory dokumentów w dwóch formatach. Formularz PDF przechodzi pełną ścieżkę:
automatyczne wypełnienie danymi, podpis na tablecie i zapieczętowanie. Dokument HTML jest
wypełniany danymi i przeznaczony do podglądu oraz druku.

Wgrany wzór przechodzi weryfikację pól wymaganych. Dokument oczekuje na sprawdzenie, zostaje
zatwierdzony albo odrzucony, a odrzucony nie wchodzi do obiegu.

System dostarcza gotowe wzory: protokół przyjęcia, protokół wydania, zgody marketingowe,
oświadczenie RODO i upoważnienie nadawcy SMS. Skasowany wzór potrzebny do przyjmowania aut jest
odtwarzany automatycznie.

Mapowanie pól łączy pola formularza z danymi w systemie, więc protokół wypełnia się sam: dane
klienta, auta, przebieg, zakres usług. Reguły decydują, który dokument i na jakim etapie ma
powstać. Do protokołu dopisywana jest ocena stanu wizualnego pojazdu.

## Podpis elektroniczny

Klient podpisuje palcem na tablecie ustawionym na recepcji ekranem w swoją stronę. Rozwiązanie
odpowiada na zarzut, który może paść w sądzie: że podpis skopiowano i wklejono do innej umowy.

1. **Podpis związany z konkretnym dokumentem.** W chwili wysłania prośby system wylicza cyfrowy
   odcisk dokładnie tego dokumentu, który klient zobaczy. Odcisk liczony jest ponownie przy
   wyświetleniu na tablecie i przy przyjęciu podpisu. Zmiana treści choćby o znak blokuje
   przyjęcie podpisu.
2. **Jednorazowość sesji.** Każda prośba ma jednorazowy kod zużywany w chwili złożenia podpisu,
   więc powtórzenie operacji jest niemożliwe.
3. **Podpis bez tła.** Kreski zapisywane są na przezroczystym tle.
4. **Natychmiastowe niszczenie danych.** Obraz podpisu istnieje wyłącznie w pamięci, do archiwum
   trafia tylko gotowy, zapieczętowany dokument. Tablet zaraz po wysłaniu, niezależnie od wyniku,
   kasuje ze swojej pamięci obraz podpisu i treść dokumentu.
5. **Karta podpisu.** Do dokumentu dołączana jest strona ze ścieżką audytu: kiedy dokument
   wystawiono, kiedy go wyświetlono, kiedy złożono podpis.
6. **Pieczęć elektroniczna i znacznik czasu.** Gotowy dokument dostaje kwalifikowaną pieczęć oraz
   niezależny znacznik czasu.

Podłączenie tabletu zajmuje chwilę. Pracownik generuje w ustawieniach sześciocyfrowy kod ważny
pięć minut, tablet go wpisuje. Połączenie nie wygasa samo, kończy je odłączenie urządzenia
w ustawieniach. Pojedyncza sesja podpisu wygasa po piętnastu minutach.

Dokument można też wysłać klientowi na jego własny telefon, linkiem SMS. Osobno zbierane są
podpisy pracowników, każdy składa go raz przez link onboardingowy, po czym podpis pojawia się
na dokumentach studia.

## Zgody i RODO

Zgoda jest dokumentem trwałym. Klient podpisuje ją raz i obowiązuje do odwołania, w odróżnieniu
od protokołu powstającego przy każdej wizycie. Zgoda deklaruje, których kanałów dotyczy, e-mail
czy SMS, przy czym w danym momencie tylko jedna aktywna zgoda studia może obejmować dany kanał.

Nowa wersja zgody może wymagać ponownego podpisu albo nie. Jeśli nie wymaga, klienci, którzy
podpisali starszą wersję, pozostają objęci zgodą.

Kiedy system wyśle wiadomość marketingową:

- studio nie prowadzi żadnej zgody obejmującej dany kanał: wysyłka dozwolona,
- studio prowadzi zgodę na ten kanał: klient musi mieć ją ważną,
- klient nie ma ważnej zgody: wysyłka wstrzymana, powód odnotowany.

Odwołanie zgody nie kasuje historii. Zapisywana jest data cofnięcia, więc widać zarówno, że zgoda
była, jak i od kiedy przestała obowiązywać. Podpisane zgody trafiają do archiwum dokumentów,
a każde nowe studio dostaje domyślną zgodę marketingową gotową do użycia.

Dane obserwowanych profili społecznościowych są kasowane automatycznie po 24 miesiącach,
a wyprowadzone z nich wnioski po 12 miesiącach.

Dokumenty klienta i wizyty tworzą wspólne archiwum plików przypiętych do osoby i do konkretnego
zlecenia. Usuwanie dokumentu wymaga uprawnienia do operacji nieodwracalnych.

---

# Część III. Relacja z klientem

Automatyczne wiadomości, kampanie, skrzynka pocztowa, zapytania i kartoteka.

## Automatyczne wiadomości

> **Zasada nadrzędna.** Ustawienia komunikacji należą do studia, wszystkie automatyzacje są
> domyślnie wyłączone, a reguła bez wpisanej treści nic nie wysyła. Dopóki właściciel nie
> zatwierdzi treści, żadna wiadomość nie dotrze do klienta.

Każda wysłana wiadomość trafia do historii komunikacji widocznej na karcie klienta i na karcie
konkretnej wizyty.

### Wiadomości powiązane z czasem

| Wiadomość | Liczona od | Domyślnie | Domyślna treść |
|---|---|---|---|
| Przypomnienie o wizycie | przed umówioną godziną | 1 godz. | „Przypominamy o wizycie dnia {{data}} o godz. {{godzina}}. Do zobaczenia, {{imie}}!" |
| Podziękowanie po wizycie | po odbiorze auta | 30 min | „Dziękujemy za wizytę, {{imie}}! Mamy nadzieję, że jesteś zadowolony z usługi." |
| Zaproszenie po czasie | po odbiorze auta | 90 dni | „Cześć {{imie}}! Minęły 3 miesiące od Twojej ostatniej wizyty. Czas na kolejny detailing?" |

> Tylko przypomnienie przed wizytą patrzy na kalendarz rezerwacji. Podziękowanie i zaproszenie
> po miesiącach liczone są od momentu odbioru auta, bo minięcie umówionej godziny nie dowodzi,
> że klient przyjechał. Liczone od rezerwacji dziękowałyby za wizytę, której nie było.

### Wiadomości wysyłane po zdarzeniu

| Wiadomość | Wychodzi, gdy | Domyślna treść |
|---|---|---|
| Potwierdzenie rezerwacji | umówiono termin | „…potwierdzamy rezerwację na {{data}} o godz. {{godzina}}. Czekamy na Ciebie!" |
| Potwierdzenie zmiany terminu | przesunięto rezerwację | „…termin Twojej wizyty został zmieniony na {{data}} o godz. {{godzina}}…" |
| Auto gotowe do odbioru | zakończono prace | „…Twój pojazd {{pojazd}} {{rejestracja}} jest gotowy do odbioru. Zapraszamy!" |
| Link do karty wizyty | pracownik wysyła kartę | „Karta Twojej wizyty {{numer_wizyty}} … jest dostępna tutaj: {{link}}" |
| Link do karty rezerwacji | pracownik wysyła szczegóły terminu | „Szczegóły Twojej rezerwacji na {{data}} … znajdziesz tutaj: {{link}}" |
| Propozycja usług dodatkowych | studio proponuje rozszerzenie zakresu | „Odpisz TAK, żeby do rezerwacji dodać usługi: {{uslugi}}. Łącznie {{kwota}} PLN brutto." |
| Prośba o podpis | dokument czeka na podpis klienta | „Dokument „{{dokument}}" czeka na Twój podpis… {{link}}" |

### Wiadomości poza automatyzacją

Jeśli studio nie włączyło przypomnień na stałe, ale pracownik zaznaczył je przy konkretnej
rezerwacji, wiadomość wyjdzie godzinę przed terminem. Zaznaczenie przełamuje wyłącznik, ale nie
tworzy treści.

Do każdej wizyty można zaplanować jeden SMS na wskazaną godzinę, na przykład informację, że lakier
potrzebuje jeszcze doby na utwardzenie. Treść pracownik pisze sam albo prosi system o propozycję.
Numer telefonu jest zapamiętywany w chwili planowania.

Wiadomość danego rodzaju wychodzi dla danej rezerwacji dokładnie raz, nawet jeśli warunki spełnią
się ponownie.

### Nazwa nadawcy i budżet SMS

SMS może wychodzić z nazwą studia zamiast numeru, po formalnym potwierdzeniu u operatora. Proces
przeprowadzany jest w aplikacji: system generuje upoważnienie nadawcy, studio podpisuje je
elektronicznie lub wgrywa skan, dokument trafia do weryfikacji. Do tego czasu wiadomości wychodzą
z numeru dostawcy.

Kredyty SMS rozliczane są per studio. System pobiera kredyt przed wysyłką i zwraca go przy błędzie
operatora. Kredyty dokupuje się w pakietach, nowe studio dostaje pulę startową. Brak środków
blokuje wysyłkę.

### Automatyczne e-maile

| Wiadomość | Wychodzi, gdy |
|---|---|
| Potwierdzenie przyjęcia pojazdu | auto zostało przyjęte, opcjonalnie z protokołem w załączniku |
| Auto gotowe do odbioru | zakończono prace |
| Link do karty wizyty lub rezerwacji | pracownik wysyła kartę klientowi |
| Rozliczenie miesiąca dla kontrahenta | zamknięto okres rozliczeniowy, raport w załączniku |

### Szablony i zmienne

Każdy rodzaj wiadomości ma z góry określony zestaw zmiennych. Szablon z nieznaną zmienną jest
odrzucany przy zapisie, z listą dopuszczalnych podpowiedzi w komunikacie.

| Grupa | Zmienne |
|---|---|
| Klient | imię, nazwisko, imię i nazwisko (tylko w e-mailach) |
| Termin | data, godzina |
| Pojazd i wizyta | pojazd, numer rejestracyjny, numer wizyty |
| Link i sprzedaż dodatkowa | link, lista usług, kwota, nazwa dokumentu |
| Zlecenia zbiorcze | kontrahent, okres, kwota brutto, liczba wpisów |
| Kampanie | marka, model, ostatnia usługa, data ostatniej wizyty, liczba dni od wizyty |

Nie ma zmiennych opisujących samo studio: nazwy, telefonu, adresu ani godzin otwarcia. Te dane
wpisuje się w szablon jako zwykły tekst. Cztery wiadomości mają treść ustaloną na stałe, bo dyktuje
ją przebieg sprawy: dwie dotyczące zmiany zakresu usług, reset hasła i zaproszenie pracownika.

## Kampanie marketingowe

Kampania jednorazowa jest przygotowywana, planowana i wysyłana w wybranym momencie. Kampania
automatyczna działa w tle i sama wyłapuje klientów spełniających warunek, na przykład 180 dni
po powłoce ceramicznej. Można ją wstrzymać i wznowić. Kanał: SMS, e-mail albo oba.

### Dobór odbiorców

Filtry można łączyć: liczba wizyt, jak dawno klient był ostatnio, ile łącznie zostawił w studiu,
z jakich usług korzystał, a z jakich nie, kiedy ostatnio korzystał z danej usługi, marka i model
auta, rocznik, klient prywatny czy firma, data pierwszej wizyty. Poszczególne osoby dodaje się
i wyklucza ręcznie.

Osobny przełącznik decyduje o klientach zapisanych tylko z numeru telefonu, bez imienia. Kreator
domyślnie ich pomija, bo wiadomość zaczynająca się od „Cześć !" szkodzi bardziej, niż pomaga.

Warunek uruchomienia kampanii automatycznej to wybrane usługi, liczba dni po usłudze, godzina
wysyłki oraz opcja pominięcia klientów, którzy w międzyczasie już byli.

### Zabezpieczenia wbudowane w wysyłkę

- godziny ciszy, domyślnie od 20:00 do 8:00. Wysyłka trafiająca w to okno jest przesuwana
  na jego koniec,
- limit częstotliwości, domyślnie jedna wiadomość na siedem dni do tej samej osoby niezależnie
  od liczby kampanii,
- zgody marketingowe sprawdzane ponownie w chwili wysyłki, nie tylko przy budowaniu listy,
- rezygnacja odnotowywana bez względu na źródło: odpowiedź STOP, kliknięcie w e-mailu, ręczne
  oznaczenie,
- kreator na każdym kroku pokazuje, ilu klientów pasuje do filtrów, ilu odpadnie i ile kredytów
  pochłonie wysyłka,
- stopki SMS i e-mail ustawiane raz dla całego studia.

## Skrzynka pocztowa

Poczta studia trafia do systemu i staje się częścią kartoteki klienta zamiast żyć w osobnym
programie.

### Podłączenie skrzynki

Studio podaje swój adres, a system rozpoznaje dostawcę i sposób logowania. Jeśli poczta wymaga
zgody przez okno dostawcy, onboarding prowadzi na ten ekran zamiast pytać o hasło. Hasła skrzynek
są przechowywane w postaci zaszyfrowanej. Konto może być aktywne, wymagać ponownego zalogowania
albo zostać wyłączone.

### Synchronizacja

System nasłuchuje na serwerze pocztowym, więc nowa wiadomość pojawia się od razu. Wiadomości są
rozkładane na treść i załączniki, razem z obrazkami osadzonymi w treści, przechodzą oczyszczenie
z niebezpiecznego kodu, a przy zapisie odcinana jest cytowana historia i stopka.

Stan przeczytania działa w obie strony. Wiadomość otwarta w telefonie przestaje być nieprzeczytana
w systemie i odwrotnie. Wysłane odpowiedzi lądują w folderze Wysłane na serwerze.

Rozpoznawanie poczty automatycznej odsiewa newslettery i autorespondery od realnych zapytań.

### Praca w skrzynce

Wątki z wyszukiwaniem, oznaczanie jako przeczytane, odpowiadanie i wysyłanie nowych wiadomości,
podgląd i pobieranie załączników, etykiety własne, archiwizowanie wątków, osobiste podpisy każdego
pracownika oraz korekta treści przed wysłaniem.

### Konwersacja przypięta do klienta

Na podstawie adresu nadawcy system buduje kartę kontaktu: kim jest ta osoba, jakie ma auta, kiedy
była ostatnio i ile już zostawiła w studiu. W wątku widać oznaczenie, czy piszący jest klientem,
zapytaniem, czy kimś nowym, a jednym kliknięciem wyświetla się pozostałe wątki tej samej osoby.
Do adresu prowadzi się notatki z pełną historią zmian.

Rozmowę można zamienić w zapytanie ofertowe. Zapytanie e-mailowe nie kopiuje przy tym wiadomości,
jego historia jest wątkiem.

### Oznaczenie nadawcy jako formularz

Powiadomienia z formularza przychodzą zawsze z tego samego adresu, a klient jest dopiero w treści
maila. Pracownik oznacza jeden taki mail jako lead z formularza, a system zapamiętuje nadawcę.
Od tej chwili każda kolejna wiadomość z tego adresu jest odczytywana i od razu staje się leadem,
bez wchodzenia do skrzynki.

Oznaczonych nadawców widać na liście razem z licznikiem utworzonych leadów i datą ostatniego.
Oznaczenie da się wyłączyć. Z jednego maila nigdy nie powstają dwa leady, a wiadomości starsze niż
samo oznaczenie są pomijane, więc doczytanie starego folderu nie zaleje listy dawno obsłużonymi
zgłoszeniami.

### Połączenia telefoniczne

Połączenia przychodzące prowadzone są w tej samej kartotece: rejestracja, przyjęcie, odrzucenie
i uzupełnienie informacji. Kliknięcie numeru w systemie powoduje, że sparowany telefon pracownika
dostaje powiadomienie i od razu dzwoni.

## Zapytania i lejek sprzedaży

Zapytania trafiają do systemu z korespondencji e-mail, z oznaczonego nadawcy formularza,
z połączenia telefonicznego, z nagrania głosowego zrobionego telefonem oraz z wpisu ręcznego.

Każde zapytanie ma kategorię odpowiadającą temu, o co klient pyta: powłoka ceramiczna, folia
ochronna i oklejanie, korekta lakieru, detailing wnętrza, mycie i pielęgnacja, pełny detailing,
inne.

### Powody, dla których zapytanie nie kończy się zleceniem

Lista jest zamknięta, bo tylko taką da się zsumować. Rozdziela dwie rzeczy, które w bazie wyglądają
tak samo, a w rachunku zupełnie inaczej.

| Realna strata | To nie była strata |
|---|---|
| za drogo, brak wolnego terminu, klient przestał odpowiadać, wybrał konkurencję, za daleko od studia, tylko sprawdzał cenę, stan auta wyklucza usługę, sprzedał albo zmienił auto, inny powód | sami odmówiliśmy, poza zakresem usług, odłożył decyzję na później, spam |

Wrzucenie odmów własnych do sumy strat kazałoby właścicielowi gonić przychód, którego świadomie
nie chciał, i psuło statystykę tym bardziej, im lepiej studio kwalifikuje zapytania.

### Prowadzenie zapytania

Wycena pozycjami z ceną zamrażaną w chwili przygotowania oferty, oznaczenia własne, notatki,
przypisanie opiekuna, alert o sprawie stojącej w miejscu oraz pomiar czasu pierwszej odpowiedzi.
Auto jest rozpoznawane z treści korespondencji, przy czym do bazy trafia zawsze marka ze słownika,
nigdy surowy tekst klienta.

Zapytanie zamienia się w rezerwację razem z przygotowaną wyceną i zachowuje powiązanie ze źródłem,
więc widać, ile zapytań kończy się umówionym terminem. Analityka lejka pokazuje, skąd przychodzą
zapytania, o co pytają, ile z nich zamienia się w zlecenia i dlaczego pozostałe nie.

## Kartoteka klienta i pojazdu

### Karta klienta

- dane osobowe i firmowe. Dane firmy można dopiąć i odpiąć bez usuwania osoby,
- pojazdy klienta. Jedno auto może mieć wielu właścicieli, jedna osoba wiele aut,
- historia wizyt z wykonanymi usługami,
- podsumowanie przychodu, czyli ile ten klient łącznie zostawił w studiu,
- historia komunikacji: SMS-y, e-maile, wątki korespondencji i połączenia telefoniczne w jednym
  miejscu,
- notatki, dokumenty, zgody z aktualnym statusem oraz wysyłka SMS wprost z karty.

> **Ochrona danych osobowych.** Dostęp do danych osobowych jest osobnym uprawnieniem, niezależnym
> od dostępu do pracy. Pracownik warsztatu bez niego normalnie korzysta z kalendarza i kart wizyt,
> ale zamiast nazwiska i telefonu widzi gwiazdki. Widoki, których sensem są dane osobowe, nie są mu
> pokazywane. Dzięki temu dostęp do systemu dostaje cały zespół, a baza klientów zostaje
> u właściciela.

### Karta pojazdu

Dane techniczne, właściciele przypisywani i odpinani, historia wizyt i rezerwacji, komentarze,
notatki, dokumenty, zdjęcia i galeria. Auto wyszukuje się po numerze rejestracyjnym. Marka i model
wpisane przez pracownika są dopasowywane do katalogu pojazdów, więc statystyki po markach
nie rozjeżdżają się przez literówki. Pojazdy mają przypisany segment, co pozwala różnicować cennik.

### Kontakty w telefonie

Baza klientów może być udostępniona telefonom pracowników jako książka adresowa, synchronizowana
automatycznie. Eksportowani są aktywni klienci z numerem telefonu, dostęp zabezpieczają osobne
hasła urządzeń, które da się unieważnić. Przy przychodzącym połączeniu telefon pokazuje nazwisko
klienta zamiast nieznanego numeru.

---

# Część IV. Zespół

Konta, uprawnienia, zadania i pełna historia tego, kto co zrobił.

## Konta i role

### Dostęp do systemu

- rejestracja studia ze sprawdzeniem adresu, siły hasła, nazwy studia i akceptacji regulaminu,
- logowanie z sesją, którą da się natychmiast unieważnić,
- samodzielny reset hasła, link ważny 30 minut z minutową przerwą między prośbami,
- kod PIN do szybkiego przełączania osoby na wspólnym stanowisku, z zachowaniem informacji, kto
  wykonał daną operację.

### Pracownicy

Dane kadrowe są oddzielone od konta w systemie, więc pracownika można prowadzić w ewidencji bez
dawania mu dostępu do aplikacji. Konto zakłada się osobno, razem z zaproszeniem. Hasło da się
zmienić, konto zablokować albo usunąć.

### Role i uprawnienia

Lista uprawnień jest zamknięta. Administrator nie wymyśla własnych, tylko składa z gotowych klocków
role odpowiadające stanowiskom w studiu. Uprawnień jest 25 i każde istnieje dlatego, że da się
wskazać realne stanowisko potrzebujące go bez sąsiednich.

Uprawnienia są powiązane zależnościami. Zaznaczenie jednego włącza wszystkie, bez których byłoby
bezużyteczne, a system domyka zapisany zestaw. Nie da się utworzyć roli „umawia wizyty, ale nie
widzi klientów", która kończyłaby się komunikatem o braku dostępu przy pierwszym kliknięciu.

```
Podgląd wizyt i kalendarza
   → Podgląd danych osobowych
      → Podgląd cen usług w wizycie
         → Tworzenie i edycja wizyt oraz rezerwacji
            → Usuwanie wizyt i dokumentów
            → Usuwanie zdjęć
            → Usuwanie klientów i pojazdów
```

| Obszar | Uprawnienia |
|---|---|
| Wizyty i kalendarz | Podgląd wizyt i kalendarza, Podgląd danych osobowych, Podgląd cen usług w wizycie, Tworzenie i edycja wizyt oraz rezerwacji, Usuwanie wizyt i dokumentów, Usuwanie zdjęć, Usuwanie klientów i pojazdów, Zlecenia zbiorcze |
| Finanse | Faktury i dokumenty przychodowe, Zarządzanie kasą, Podgląd raportów finansowych, Powiadomienia o zarobku po wizycie |
| Pracownicy | Zarządzanie pracownikami i ich kontami, Płace |
| Komunikacja i marketing | Wysyłanie wiadomości do klientów, Marketing i social media |
| Pozostałe | Podgląd statystyk, Praca z zapytaniami, Podgląd i realizacja zadań, Tworzenie i przypisywanie zadań, Podgląd historii aktywności firmy |

Rozstrzygnięcia warte uwagi:

- zlecenia zbiorcze stoją osobno. Obsługa kontrahentów B2B nie potrzebuje kalendarza studia ani
  kartoteki klientów detalicznych, a recepcja nie musi widzieć stawek kontrahentów,
- powiadomienie o zarobku to nie raport. Właściciel może chcieć kwoty na telefon, nie oddając
  nikomu wglądu w rozliczenia, a księgowość raportów bez powiadomień przy każdym odbiorze auta,
- historia aktywności jest osobnym uprawnieniem, bo obejmuje zdarzenia kadrowo-płacowe
  i bezpieczeństwa,
- kalendarz i pojazdy nie są osobnymi obszarami. Wpis w kalendarzu jest wizytą albo rezerwacją,
  a dostęp do aut wynika z dostępu do wizyt i klientów,
- właściciel ma dostęp do wszystkiego z definicji.

## Zadania i czas pracy

Zadanie ma tytuł, opis, status wykonania, autora i osobę, która je zamknęła, wraz z datami.
Usunięcie jest odwracalne.

Przypisanie realizowane jest przez widoczność. Zadanie kieruje się do wszystkich w studiu,
do wskazanych osób albo do wszystkich pełniących określoną rolę. Właściciel widzi wszystko, autor
widzi swoje zadania, poza tym decyduje wskazanie odbiorcy.

- widok zespołowy: lista zadań, archiwum wykonanych, tworzenie, edycja i usuwanie,
- zadanie z nagrania głosowego. Pracownik dyktuje zadanie do telefonu, system zamienia je na wpis,
- widok pracownika: moje zadania, licznik nieprzeczytanych, oznaczanie jako przeczytane,
  odhaczanie wykonania.

Ewidencja czasu pracy prowadzi wpisy dzienne i miesięczne okresy rozliczeniowe zamykane
po zatwierdzeniu, z automatycznym liczeniem nadgodzin.

## Historia aktywności

Jeden wspólny dziennik odpowiada na pytanie, kto, co, kiedy i na jaką kwotę. Obejmuje klientów,
pojazdy, wizyty, rezerwacje, usługi, zapytania, protokoły, zgody, połączenia, dane studia,
użytkowników, finanse, kasę, pracowników, zadania, bezpieczeństwo i obsługę door to door.

Zdarzenia mają przypisaną wagę, więc usunięcie wizyty wyróżnia się na liście inaczej niż dodanie
komentarza. Wpisy są formułowane pełnymi zdaniami po polsku, a historia pojedynczej wizyty, klienta
czy pojazdu pochodzi z tego samego dziennika.

---

# Część V. Rozwój biznesu

Obsługa flot, obserwacja konkurencji i liczby, na których podejmuje się decyzje.

## Zlecenia zbiorcze

Moduł dla studiów obsługujących floty, komisy i podwykonawstwo, gdzie rozliczenie odbywa się raz
na miesiąc z całym kontrahentem, a nie po każdym aucie. Jest odcięty od obsługi klienta
detalicznego.

- kontrahenci: nazwa, NIP, adres, osoba kontaktowa, e-mail, telefon, notatki, status współpracy,
- wpisy: data usługi, marka, model, numer rejestracyjny, VIN, wykonane usługi z kwotami i stawką
  podatku, notatki, informacja o rozliczeniu,
- własny cennik zleceń zbiorczych, oddzielny od detalicznego,
- zdjęcia przy wpisie,
- podpowiadanie pojazdów z kartoteki i z wcześniejszych wpisów tego kontrahenta.

### Odczyt VIN ze zdjęcia

Zamiast przepisywać siedemnastoznakowy numer z tabliczki, pracownik robi zdjęcie, a system
odczytuje numer. Wynik jest twardo sprawdzany: musi mieć dokładnie 17 dopuszczalnych znaków,
w przeciwnym razie system prosi o ręczne wpisanie i nigdy nie dopowiada brakującego znaku.

### Rozliczenie miesiąca

- raport za okres, czyli zestawienie wykonanych usług dla kontrahenta,
- zamknięcie okresu w jednym z dwóch trybów: wszystkie wpisy z zakresu dat albo wyłącznie jeszcze
  nierozliczone. Wynikiem jest liczba wpisów, suma netto i brutto oraz opcjonalna wysyłka raportu
  e-mailem, na adres z kartoteki albo jednorazowo wskazany inny,
- historia zamknięć z zapisaną kopią raportu dokładnie w tej postaci, w jakiej został wysłany.

## Monitoring konkurencji

Studio wskazuje profile Instagram, które chce śledzić. Jeden z nich oznacza jako własny i to on
staje się punktem odniesienia we wszystkich porównaniach. Nieudane pobranie danych można ponowić
ręcznie.

| Pobieranie danych | Kiedy |
|---|---|
| Pełna aktualizacja historii | w niedziele nad ranem |
| Lekka aktualizacja dzienna | codziennie o 6:30 |

Dane zapisywane są jako zdjęcia stanu w czasie: posty, liczba obserwujących, statystyki tygodniowe.
Widać nie tylko, ile mają teraz, ale też jak to się zmieniało.

### Co studio dostaje

| Widok | Zawartość |
|---|---|
| Przegląd | każda liczba pokazywana ze zmianą i punktem odniesienia, nigdy sama |
| Porównanie | własny profil zestawiony z obserwowanymi, ze szczegółem wybranego tygodnia |
| Puls konkurencji | lista tego, co wydarzyło się w minionym tygodniu |
| Treść | analiza publikacji i mapa pokazująca, w które dni i godziny konkurencja publikuje |
| Hashtagi, sugestie, werdykt tygodnia | używane oznaczenia, podpowiedzi dla własnego profilu, tygodniowe podsumowanie |

Puls konkurencji liczą reguły z jawnymi progami, bez modelu językowego i bez opóźnień. Za normę
profilu przyjmuje się jego własne zachowanie z pół roku wstecz, więc porównanie dotyczy tego, jak
konkurent zachowuje się względem siebie. Wychwytywane zdarzenia to własny post, własne milczenie,
przyspieszenie i spowolnienie publikacji, post wyraźnie powyżej normy, nowy temat oraz skok
i spadek liczby obserwujących.

Werdykt tygodnia to jedno zdanie na profil: milczał, wyróżnił się, przyspieszył albo publikował
jak zwykle.

> Wnioski formułowane są według schematu „co się stało, dlaczego to ważne, co możesz zrobić".
> Ten sam wniosek nie powstanie dwa razy, a tygodniowo studio dostaje najwyżej pięć nowych,
> wybranych według ważności. Przypuszczenia są oznaczone jako hipoteza, nigdy jako fakt.

### Przygotowywanie postów

System proponuje treść posta, ucząc się na podobnych publikacjach, które wcześniej zadziałały.
Reakcje studia na obserwowane posty wpływają na dobór inspiracji, więc propozycje z czasem lepiej
trafiają w styl konkretnego studia.

## Statystyki i pulpit

Przegląd działalności, rozbicie przychodu i liczby wizyt w czasie, statystyki pojedynczej kategorii
i pojedynczej usługi, lista wizyt z wybranego okresu oraz zestawienie usług nieprzypisanych
do żadnej kategorii.

Kategorie usług są warstwą raportową nakładaną na cennik, więc właściciel sam decyduje, co składa
się na pielęgnację, a co na korektę lakieru. Usługi wpisywane ręcznie, spoza cennika, również
trafiają do statystyk.

Cennik pilnuje historii: przy edycji usługi stara wersja jest archiwizowana, a nie nadpisywana,
więc wizyta sprzed roku pokazuje cenę, która wtedy obowiązywała. Osobno prowadzone są pakiety usług.

Pulpit podsumowuje dzień: umówione terminy, przychód i najważniejsze wskaźniki, odświeżane
na bieżąco. Osobno wyświetlane są podpowiedzi dotyczące niedokończonej konfiguracji, które da się
zamknąć na stałe.

---

# Część VI. Platforma

Praca poza biurkiem, model licencjonowania, bezpieczeństwo i technologia.

## Praca z telefonu

Bez pełnego logowania, przez kod QR albo skróty na ekranie telefonu, pracownik ma dostęp do:

- robienia zdjęć i zaznaczania uszkodzeń przy przyjęciu auta,
- dyktowania notatek i zapytań, gdzie nagranie zamienia się w wpis w systemie,
- podpisywania dokumentów,
- obsługi wybierania numeru z komputera.

Powiadomienia trafiają na sparowane telefony w dwóch sytuacjach: przy wybieraniu numeru
z komputera oraz jako informacja o zarobku po każdej zamkniętej wizycie. Ta druga idzie wyłącznie
do osób z odpowiednim uprawnieniem, sprawdzanym po stronie odbiorcy. Awaria usługi powiadomień
nie zamienia zamkniętej wizyty w błąd.

## Plany i moduły

Obok uprawnień działa druga bramka: co studio ma wykupione.

| Plan | Zakres |
|---|---|
| Podstawowy | kalendarz, wizyty, klienci, pojazdy, dokumenty, galeria |
| Pełny | wszystko |

Moduły dokupywane osobno: asystent przy obsłudze zapytań, monitoring konkurencji na Instagramie,
automatyzacja kontaktu z klientem, kampanie marketingowe, podpisy elektroniczne, kontrola nad
finansami, statystyki.

Niektóre funkcje wymagają dwóch modułów naraz. Prośba o podpis na telefonie klienta potrzebuje
podpisów elektronicznych i komunikacji, bo link jedzie SMS-em. Kredyty SMS są dostępne dla każdego
modułu, który wysyła wiadomości.

Zmiana planu obejmuje proporcjonalne rozliczenie różnicy, a obniżenie planu wchodzi w życie
z końcem opłaconego okresu. Abonament i pakiety SMS opłaca się przez bramkę płatniczą.

Konto demonstracyjne z wygenerowanymi danymi pozwala pokazać system bez wprowadzania czegokolwiek
do realnej bazy. Problem można zgłosić z poziomu aplikacji.

## Bezpieczeństwo

Każde studio jest osobnym najemcą we wspólnej bazie, z izolacją wierszową. Każdy rekord nosi
identyfikator studia, każde zapytanie filtruje po nim, a szczelność podziału jest kontrolowana
osobno.

- ograniczenie liczby zapytań z jednego źródła,
- nagłówki zabezpieczające przeglądarkę,
- identyfikator korelacji pozwalający prześledzić operację w logach,
- ukrywanie danych osobowych przed osobami bez uprawnienia, na poziomie, przez który nie da się
  przejść omijając interfejs.

System prowadzi też analitykę użycia z podziałem na studia. Kilka rozstrzygnięć decyduje
o wiarygodności tych liczb:

| Obszar | Reguła |
|---|---|
| Czas pracy w systemie | laptop zostawiony otwarty na noc dopisuje półtorej minuty, nie szesnaście godzin. Sesja bez aktywności zamykana jest wstecznie, na ostatnim śladzie obecności. Sesje krótsze niż pół minuty i bez interakcji nie liczą się |
| Nieużywane funkcje | funkcja jest uznawana za martwą po trzech miesiącach bez użycia, a raport pozostaje oznaczony jako niewiarygodny, dopóki obserwacja nie trwa wystarczająco długo |
| Błędy | zbierane z kontekstem, z ograniczeniem liczby zgłoszeń |
| Wydajność | zapis danych analitycznych odbywa się w tle, partiami, i nie spowalnia pracy |
| Retencja | szczegółowe zapisy kasowane po ustalonym czasie, dzienne podsumowania zostają na stałe |
| Dostęp | konsola analityczna zamknięta do czasu świadomego otwarcia osobnym kluczem. Narzędzia raportowe czytają wyłącznie tabele metryk, bez dostępu do danych klientów, wizyt i faktur |

## Podstawa techniczna

### Baza danych

| Element | Wersja |
|---|---|
| PostgreSQL | 15+ |
| pgvector | indeks HNSW, metryka cosinusowa, 1536 wymiarów |
| HikariCP | wersja zarządzana przez Spring Boot |
| Flyway | 88 migracji |
| Redis | sesje, cache, tokeny, kolejki zdarzeń |

### Backend

| Element | Wersja |
|---|---|
| Kotlin | 2.0.0 |
| Java | 17 |
| Spring Boot | 3.2.5 |
| Spring Dependency Management | 1.1.4 |
| Gradle | 8.14 |
| Spring AI | 1.0.0 |

Moduły frameworka: web, validation, websocket, security, session z Redisem, data-jpa, cache, aop,
actuator.

| Biblioteka | Wersja | Zastosowanie |
|---|---|---|
| kotlinx-coroutines | 1.8.0 | równoległe budowanie kontekstów walidacji |
| AWS SDK v2 | 2.21.0 | magazyn dokumentów, zdjęć i protokołów |
| Apache PDFBox | 3.0.1 | wypełnianie formularzy, składanie podpisanych PDF |
| metadata-extractor | 2.19.0 | orientacja zdjęć |
| thumbnailator | 0.4.20 | miniatury |
| jsoup | 1.17.2 | oczyszczanie treści e-maili |
| BouncyCastle | 1.78.1 | pieczęć PAdES, znacznik czasu RFC 3161 |
| KSeF SDK | 3.0.18 | integracja z KSeF |
| SMSAPI SDK | 3.0.1 | wysyłka SMS |
| JavaMail | 2.0.1 | SMTP, IMAP |
| Resilience4j | 2.2.0 | odporność integracji GUS |
| MockK | 1.13.10 | testy |

### Panel

| Element | Wersja | Element | Wersja |
|---|---|---|---|
| React | 19.2.0 | FullCalendar | 6.1.20 |
| TypeScript | 5.9.3 | Recharts | 3.7.0 |
| Vite | 7.2.4 | styled-components | 6.3.1 |
| React Router | 7.12.0 | Tailwind CSS | 4.1.18 |
| TanStack Query | 5.90.16 | lucide-react | 0.563.0 |
| Axios | 1.13.2 | dinero.js | 2.0.0-alpha.14 |
| React Hook Form | 7.70.0 | pdfjs-dist | 6.1.200 |
| Zod | 4.3.5 | qrcode.react | 4.1.0 |
| dompurify | 3.4.14 | Radix Dialog | 1.1.15 |
| Vitest | 4.1.6 | ESLint | 9.39.1 |

### Kiosk tabletowy

| Element | Wersja | Element | Wersja |
|---|---|---|---|
| React | 19.1.0 | pdfjs-dist | 5.3.31 |
| TypeScript | 5.8.3 | Playwright | 1.53.0 |
| Vite | 6.3.5 | Vitest | 3.2.4 |

### Integracje i infrastruktura

| System | Rola |
|---|---|
| KSeF | wysyłka faktur sprzedaży, pobieranie faktur kosztowych, UPO, kody QR |
| GUS BIR | dane kontrahenta po NIP |
| SMSAPI | SMS wychodzące i odpowiedzi klientów |
| OpenAI | propozycje treści, rozpoznawanie danych, transkrypcja nagrań |
| RapidAPI | dane profili Instagram |
| AWS S3 | dokumenty, protokoły, zdjęcia, zgody |
| Przelewy24 | abonament i pakiety SMS |
| CloudFlare Email Workers | poczta przychodząca |
| Web Push | powiadomienia na telefon |
| CardDAV | kontakty w telefonie |
| Prometheus, Grafana | monitoring i analityka |
| Jenkins, Docker | budowanie i uruchamianie |

### Wzorce

- podział kodu po funkcjach biznesowych, nie po warstwach technicznych,
- walidacja złożona z osobnych reguł, z równoległym pobieraniem danych potrzebnych do sprawdzenia,
- niezmienne snapshoty cen i danych pojazdu w wizycie,
- kwoty w groszach, z inwariantem sumy netto, podatku i brutto sprawdzanym w konstruktorze,
- maskowanie danych osobowych na granicy serializacji.
