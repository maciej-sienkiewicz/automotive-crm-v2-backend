# Dashboardy metryk w Grafanie — wdrożenie

Warstwa wizualizacji dla modułu metryk. Trzy dashboardy w folderze **Detailing CRM**,
provisionowane z repo — Grafana jest tu tylko przeglądarką, źródłem prawdy są pliki.

| Dashboard | UID | Czyta z | Odpowiada na |
|---|---|---|---|
| Platforma — przegląd | `crm-platform-overview` | Prometheus + Postgres | Ile mamy kont, ile płacą, czy rosną, co się psuje |
| Platforma — tenanci i czas pracy | `crm-platform-usage` | Postgres | Ile godzin i kto (właściciel vs pracownik), kogo tracimy, kto płaci za nieużywany moduł |
| Platforma — audyt API i defekty | `crm-platform-api-audit` | Postgres | Co można usunąć z kodu, jakie defekty są otwarte i kogo dotknęły |

---

## Dlaczego dwa źródła danych

Nie z przypadku — z ograniczeń narzędzi.

**Prometheus** dostał subskrypcje, bo wymóg brzmiał „czas rzeczywisty" i bo to jedyne
serie, na których da się oprzeć regułę alertu. Siedem serii, stała kardynalność.

**Postgres** dostał całą resztę, bo Prometheus jej nie uniesie:

- `studio_id` jako etykieta przy kilkuset tenantach × kilkuset endpointach to setki tysięcy
  serii — kardynalność, która zabija instancję Prometheusa, a nie tylko ją spowalnia;
- retencja tu potrzebna to lata („ile godzin to studio pracowało w marcu"), a Prometheus
  trzyma dni;
- grupa błędów ma **cykl życia** (NEW → ACKNOWLEDGED → RESOLVED, wykrywanie regresji) —
  to encja, a metryka Prometheusa jest tylko liczbą w czasie.

Konsekwencja, którą trzeba było dopowiedzieć od razu: bez datasource'u Postgres Grafana
nie widzi z tego modułu **niczego** poza subskrypcjami.

---

## Wdrożenie

### 1. Migracje

```bash
psql "$DB_URL" -f src/main/resources/db/migration/V65__metrics_module.sql
psql "$DB_URL" -f src/main/resources/db/migration/V66__metrics_grafana_readonly.sql
```

Obie są idempotentne — bezpiecznie puścić ponownie.

### 2. Hasło dla roli Grafany

V66 tworzy rolę `grafana_ro` **bez hasła**, więc nie da się nią połączyć, dopóki ktoś go
nie ustawi. To celowe: hasło zacommitowane do repo to hasło, które wyciekło.

```bash
NEW_PASS=$(openssl rand -base64 32)
psql "$DB_URL" -c "ALTER ROLE grafana_ro WITH PASSWORD '$NEW_PASS';"
echo "ENV_GRAFANA_DB_PASSWORD=$NEW_PASS"   # → do .env deploymentu
```

Co ta rola widzi: tabele `metric_*` plus dwukolumnowy katalog studiów (id, nazwa).
Czego **nie** widzi: klientów, wizyt, faktur, użytkowników, `audit_logs` ani
`metric_error_events` — pojedyncze wystąpienia błędów zostają poza zasięgiem, bo komunikat
wyjątku potrafi zacytować dane klienta, a ta Grafana ma włączony anonimowy podgląd
(`GF_AUTH_ANONYMOUS_ENABLED: true`). Stack trace jest za uwierzytelnionym
`GET /api/internal/metrics/errors/{fingerprint}`.

Granica jest sprawdzona wykonaniem, nie deklaracją — patrz sekcja „Weryfikacja".

### 3. Zmienne środowiskowe

```bash
ENV_GRAFANA_DB_PASSWORD=<z kroku 2>
# opcjonalne, domyślnie dziedziczone po ENV_DB_*:
# ENV_GRAFANA_DB_HOST, ENV_GRAFANA_DB_PORT, ENV_GRAFANA_DB_NAME
# ENV_GRAFANA_DB_SSLMODE (domyślnie disable; ustaw require, jeśli baza jest poza hostem)
```

### 4. Usunięcie starego dashboardu

**To nie zadzieje się samo.** Poprzedni provider miał `disableDeletion: true`, co znaczy,
że Grafana nie kasuje zaprovisionowanego dashboardu po zniknięciu jego pliku — zostaje
osierocony we własnej bazie Grafany. Dlatego „Detailing CRM – Observability" był dalej
widoczny tygodniami po usunięciu z repo i wyglądał na aktualny.

```bash
curl -X DELETE -u admin:"$GRAFANA_ADMIN_PASSWORD" \
  https://grafana.detailboost.pl/api/dashboards/uid/detailing-crm-obs-v1
```

Nowy provider ma `disableDeletion: false`, więc od teraz repo jest źródłem prawdy w obie
strony: usunięcie pliku usuwa dashboard.

Uwaga: kasujesz **widok**, nie dane. Prometheus dalej zbiera JVM, latency i błędy HTTP —
serie żyją, po prostu nic ich nie rysuje. Odtworzenie dashboardu technicznego to
przywrócenie jednego pliku JSON z historii gita.

### 5. Restart i pierwsze dane

```bash
docker compose up -d grafana
```

Wykresy biznesowe czytają dzienne snapshoty, więc są puste, dopóki roll-up nie policzy
pierwszego dnia (03:10, plus odświeżenie co godzinę). Żeby nie czekać do nocy:

```bash
curl -X POST -H "X-Platform-Key: $PLATFORM_METRICS_KEY" \
  "https://api.detailboost.pl/api/internal/metrics/recompute?date=$(date +%F)"
```

Rezerwacje, wizyty i logowania wypełnią się wstecz z tabel źródłowych. Czas sesji i błędy
słusznie zaczynają od zera — nic ich wcześniej nie mierzyło, a wymyślanie wartości byłoby
gorsze niż widoczna luka.

---

## Jak czytać te dashboardy

**Dzisiejszy słupek jest niepełny.** Wszystko poza sekcją subskrypcji to dzienny snapshot
narastający do końca doby. Porównywanie dzisiaj z wczoraj przed 23:59 zawsze pokaże spadek.

**„Odsetek sesji odrzuconych" to metryka pomiaru, nie biznesu.** Mówi, ile sesji odpadło
jako puste (poniżej 30 s zaangażowania albo zero interakcji). Gwałtowna zmiana oznacza, że
heartbeat po stronie frontu przestał działać — i widać to od razu, zamiast miesiąc później,
gdy ktoś zauważy, że użycie „urosło" o 40% bez ani jednego nowego klienta.

**„Dni obserwacji" na audycie API jest warunkiem, nie ciekawostką.** Poniżej 30 dni żadna
z liczb w tej sekcji nie jest podstawą do usuwania czegokolwiek — endpoint raportu
kwartalnego wygląda identycznie jak martwy po trzech dniach danych. Wiążąca klasyfikacja
(z tym zabezpieczeniem wbudowanym) jest w `GET /api/internal/metrics/api-audit`; dashboard
pokazuje surowe fakty, żeby ta sama reguła nie żyła w dwóch miejscach i nie rozjechała się.

**`Nieprzeliczony` w kolumnie ryzyka** znaczy, że drugi przebieg roll-upu (health score) nie
policzył tego wiersza — nie że klient jest zdrowy. Wcześniej takie wiersze miały wynik 0
z etykietą „Zdrowy", czyli parę niespójną: awaria kalkulatora pomalowałaby cały board na
zielono.

---

## Weryfikacja

Migracje, całe SQL z roll-upów i handlerów oraz wszystkie 32 zapytania z dashboardów zostały
wykonane na lokalnym PostgreSQL 16 z zaseedowanymi danymi dwóch studiów. Sprawdzone zostały
także wartości, nie tylko składnia — MRR, podział czasu OWNER/EMPLOYEE, wykluczenie pustych
sesji, sumy rezerwacji i SMS zgadzają się z ręcznym wyliczeniem.

Granica uprawnień `grafana_ro` przetestowana wykonaniem: 8 tabel/widoków czyta,
7 (klienci, wizyty, użytkownicy, rezerwacje, audit_logs, surowa tabela studios,
wystąpienia błędów) odmawia, zapis i DDL odmawia.

Czego **nie** zweryfikowano: renderowania w samej Grafanie. Schemat panelu, mapowania
kolorów i interpolacja zmiennej `$studio` są napisane pod Grafanę 11.0 i sprawdzone jako
poprawny JSON, ale nie zostały otwarte w przeglądarce.
