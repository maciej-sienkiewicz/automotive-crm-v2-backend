# meta-ig-resolver

Odczytuje nazwę profilu na Instagramie ze strony reklamodawcy w Bibliotece
reklam Meta. Osobny kontener, bo niesie Chromium.

## Po co to istnieje

Publiczne `ads_archive` **nie ma pola z Instagramem** — sprawdzone, nie ma go
w żadnym wariancie zapytania. Webowa Biblioteka reklam pokazuje ten uchwyt
w sekcji „Informacje o reklamodawcy", ale dociąga go dopiero JavaScriptem po
rozwinięciu sekcji. Backend nie ma jak tam sięgnąć; przeglądarka ma.

Nazwy profili wyprowadzamy z trzech źródeł, w kolejności od najpewniejszego:

1. **Podpis reklamy** — gdy reklama kieruje wprost na `instagram.com/nazwa`.
   Za darmo, w oficjalnym API, bez tego kontenera.
2. **Ta usługa** — pole `ig_username` ze strony reklamodawcy.
3. **Strona firmy** — link do Instagrama wyłuskany ze stopki.

## Skąd bierze dane — i dlaczego nie z HTML-a

Nie z wyrenderowanego widoku, tylko z **odpowiedzi sieciowej**, którą strona
sama pobiera.

Powód jest konkretny. W wyrenderowanym widoku uchwyt Facebooka i uchwyt
Instagrama są nie do odróżnienia inaczej niż po przesunięciu ikony w arkuszu
sprite'ów — `-605px` kontra `-639px`. Kod oparty na współrzędnej w obrazku
przestałby działać przy pierwszej przebudowie arkusza (nazwa pliku jest
hashem) i — co gorsza — zacząłby **po cichu** zwracać alias Facebooka jako
Instagram. W odpowiedzi sieciowej te same dane mają nazwy: `ig_username`
i `page_alias`.

**Zasada nadrzędna: przy jakiejkolwiek wątpliwości zwracamy `null`.** Nigdy
`page_alias`, nigdy „ten drugi w kolejności", nigdy zgadywania z nazwy firmy.
Brak nazwy jest tani — błędna nazwa to cudzy profil pokazany właścicielowi
studia jako konkurent.

## Kontrakt

```
POST /resolve  {"pageId":"101137765342120"}
  → 200 {"status":"ok",      "igUsername":"pro_garage_performance", "tookMs":9123}
  → 200 {"status":"empty",   "igUsername":null}   reklamodawca nie ma Instagrama
  → 200 {"status":"blocked", "igUsername":null}   nie dotarliśmy do danych
  → 200 {"status":"timeout"|"error", ...}
  → 429 {"status":"busy"}                          trwa inne sprawdzenie
  → 400 {"error":"..."}                            złe żądanie

GET /health → 200 {"status":"up","busy":false}
```

Rozróżnienie `empty` od reszty jest sednem alertowania: pierwsze to normalny
wynik, pozostałe znaczą „nie udało się sprawdzić".

## Budowanie i wdrożenie

Nie jest częścią potoku backendu — buduje się osobno, bo zmienia się rzadko.

Usługa siedzi w compose za **profilem** `ig-resolver` i dopóki profil jest
nieaktywny, `docker compose` w ogóle jej nie widzi — nie próbuje jej pobrać ani
uruchomić. Nie jest to drobiazg: `docker compose pull` ciąga wszystkie usługi
z pliku i **przerywa całe wdrożenie**, gdy jednej brakuje w registry. Bez profilu
sam fakt istnienia tego wpisu wywracałby wdrożenie backendu u każdego, kto nie
zbudował wcześniej obrazu.

Kolejność jest więc obowiązkowa — najpierw obraz, potem profil:

```bash
# 1. zbuduj i wypchnij obraz
docker build -t 127.0.0.1:5000/meta-ig-resolver:latest ./meta-ig-resolver
docker push 127.0.0.1:5000/meta-ig-resolver:latest

# 2. dopiero teraz w /opt/apps/prod/.env
COMPOSE_PROFILES=ig-resolver
ENV_META_IG_RESOLVER_ENABLED=true

# 3. wdrożenie
docker compose --env-file /opt/apps/prod/.env up -d
```

Wyłączenie: usuń `COMPOSE_PROFILES` z `.env` i zatrzymaj kontener ręcznie
(`docker rm -f meta-ig-resolver`) — compose przestanie nim zarządzać, więc sam
go nie usunie.

## Ograniczenia, o których trzeba wiedzieć

**Regulamin Meta zabrania automatycznego zbierania danych z ich serwisów.**
Uruchomienie tej usługi jest decyzją świadomą.

**Ścieżka kliknięć opiera się na napisach interfejsu** („Zobacz szczegóły
reklamy", „Informacje o reklamodawcy"). Odczyt danych jest odporny, wyzwolenie
ich pobrania — nie. Gdy Meta przebuduje stronę, przestanie działać. Przestanie
**cicho i bezpiecznie**: `null` zamiast złej nazwy.

Tę ciszę wykrywa **kanarek** (`MetaIgCanary` po stronie backendu): raz na dobę
pyta o stronę, o której wiemy, że profil ma. Gdy przestaje go widzieć, miernik
`crm_meta_ig_canary` schodzi do zera i po dwóch godzinach odzywa się alert
`MetaIgResolverPadl`. Bez kanarka awaria byłaby niewidoczna — każdy odczyt
zacząłby wracać jako „reklamodawca nie ma Instagrama", czyli jako wynik
normalny i nie do odróżnienia od prawdy.

**Obraz waży ~700 MB** (Chromium z Debiana zamiast pełnego obrazu Playwrighta
z trzema przeglądarkami). Kontener ma limit 512 MB pamięci i rotację logów —
stoi na maszynie, która hostuje także produkcyjną bazę.

**Jedno żądanie naraz.** Każde uruchamia własne Chromium; dwa równolegle przy
limicie 512 MB kończą się ubiciem przez OOM. Przy kilkunastu wywołaniach na
miesiąc kolejka nikomu nie przeszkadza.
