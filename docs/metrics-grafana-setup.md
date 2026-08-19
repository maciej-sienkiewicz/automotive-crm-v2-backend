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

## Co robi się samo przy deployu, a co ręcznie

Produkcja startuje z `-Dspring.profiles.active=docker-props` (ustawione w `deploy/Dockerfile`),
gdzie **Flyway jest włączony**, a `ddl-auto=validate`. To znaczy, że migracje wykonują się
przy starcie aplikacji, a Hibernate potem weryfikuje schemat względem encji.

### Samo, przy pierwszym starcie nowej wersji

| Co | Skąd |
|---|---|
| Tabele `metric_*` (10 sztuk, 151 kolumn) | Flyway → `V65__metrics_module.sql` |
| Indeksy, w tym częściowe | to samo |
| Seed pustych snapshotów za 90 dni wstecz | to samo |
| Widoki `metric_studio_directory`, `metric_endpoint_usage` | Flyway → `V66__metrics_reporting_views.sql` |
| Katalog endpointów (zasilany ze Springa) | `EndpointCatalogRegistrar` przy `ApplicationReadyEvent` |
| Harmonogram roll-upów (03:10 / 03:25), retencja (03:40) | `@Scheduled` w aplikacji |
| Serie subskrypcji w Prometheusie | `SubscriptionMetricsGauges`, odświeżane co 60 s |
| Dashboardy i datasource w Grafanie | provisioning z repo, po restarcie kontenera |

### Ręcznie — pięć kroków, każdy raz

**1. Rola bazodanowa dla Grafany** (wymaga uprawnień administratora bazy)

Świadomie **nie** jest to migracja Flyway. `CREATE ROLE` wymaga uprawnienia `CREATEROLE`,
którego użytkownik aplikacji nie ma — sprawdzone, kończy się `permission denied to create
role`. A ponieważ w produkcji Flyway startuje razem z aplikacją, taka migracja
zatrzymałaby start **całego CRM-a**, nie tylko dashboardów.

Kolejność ma znaczenie: **najpierw deploy aplikacji** (Flyway tworzy tabele i widoki),
**potem** ten skrypt. Odwrotnie GRANT-y padną na nieistniejących relacjach.

*Na hoście produkcyjnym nie ma `psql`, baza jest zewnętrzna (`ENV_DB_ADDR`) i nie stoi
jako kontener w compose, a katalog deploymentu nie musi leżeć w `~`.* Parametry połączenia
najpewniej wyciągnąć **wprost z działającego kontenera backendu** — to źródło aktualne
z definicji, w przeciwieństwie do pliku `.env`, którego trzeba szukać i który mógł zostać
podmieniony po ostatnim wdrożeniu:

```bash
BE=$(docker ps --filter "ancestor=127.0.0.1:5000/detailing-crm-backend" --format '{{.Names}}' | head -1)
[ -z "$BE" ] && BE=$(docker ps --format '{{.Names}}' | grep -i detailing-crm-backend | head -1)

eval "$(docker inspect "$BE" --format '{{range .Config.Env}}{{println .}}{{end}}' \
        | grep -E '^DB_(ADDR|PORT|NAME|USER|PASSWORD)=' | sed 's/^/export /')"
NET=$(docker inspect "$BE" --format '{{range $k,$v := .NetworkSettings.Networks}}{{println $k}}{{end}}' | head -1)

echo "baza: $DB_USER@$DB_ADDR:$DB_PORT/$DB_NAME   sieć: $NET"
```

Gdyby jednak przydał się sam katalog deploymentu (np. do kroku 3 i 4), Compose zapisuje go
w etykiecie kontenera:

```bash
docker inspect "$BE" --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
```

*Wariant A — bez instalowania czegokolwiek (klient z obrazu dockerowego):*

```bash
docker run --rm -i --network "$NET" -e PGPASSWORD="$DB_PASSWORD" postgres:16 \
  psql -h "$DB_ADDR" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -f - < deploy/sql/grafana-readonly-role.sql
```

*Wariant B — instalacja klienta na stałe:*

```bash
sudo apt install -y postgresql-client
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_ADDR" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -f deploy/sql/grafana-readonly-role.sql
```

*Wariant C — gdy na hoście w ogóle nie ma checkoutu repo:* wklej ten sam SQL przez stdin.
Ogranicznik `<<'SQL'` jest w apostrofach, więc bash nie rusza `$$` ani apostrofów w środku:

```bash
docker run --rm -i --network "$NET" -e PGPASSWORD="$DB_PASSWORD" postgres:16 \
  psql -h "$DB_ADDR" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f - <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
        CREATE ROLE grafana_ro WITH LOGIN;
    END IF;
END
$$;
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_ro', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO grafana_ro;
REVOKE CREATE ON SCHEMA public FROM grafana_ro;
GRANT SELECT ON metric_studio_directory         TO grafana_ro;
GRANT SELECT ON metric_endpoint_usage           TO grafana_ro;
GRANT SELECT ON metric_daily_studio_snapshots   TO grafana_ro;
GRANT SELECT ON metric_daily_platform_snapshots TO grafana_ro;
GRANT SELECT ON metric_user_sessions            TO grafana_ro;
GRANT SELECT ON metric_events                   TO grafana_ro;
GRANT SELECT ON metric_api_endpoints            TO grafana_ro;
GRANT SELECT ON metric_api_endpoint_daily       TO grafana_ro;
GRANT SELECT ON metric_studio_api_daily         TO grafana_ro;
GRANT SELECT ON metric_error_groups             TO grafana_ro;
GRANT SELECT ON metric_error_group_impacts      TO grafana_ro;
SELECT table_name, privilege_type FROM information_schema.table_privileges
WHERE grantee = 'grafana_ro' ORDER BY table_name;
SQL
```

Poprawny wynik to **11 wierszy**, same relacje `metric_*`. Jeśli poleci
`relation "metric_..." does not exist`, aplikacja z nową wersją jeszcze nie wystartowała
i Flyway nie założył tabel — wdróż ją najpierw.

**Zanim to uruchomisz, sprawdź, czy `$DB_USER` w ogóle może zakładać role** — to jest
dokładnie to uprawnienie, którego brak wyrzucił rolę z migracji:

```sql
SELECT rolname, rolsuper, rolcreaterole FROM pg_roles WHERE rolcanlogin;
```

Jeśli `rolcreaterole` i `rolsuper` są `f`, użyj konta administracyjnego bazy zamiast
`DB_USER` (u dostawcy zarządzanego zwykle konto założone przy tworzeniu instancji).

Następnie hasło — rola powstaje bez niego i do tego momentu nie da się nią połączyć:

```bash
NEW_PASS=$(openssl rand -base64 32)
docker run --rm --network "$NET" -e PGPASSWORD="$DB_PASSWORD" postgres:16 \
  psql -h "$DB_ADDR" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -c "ALTER ROLE grafana_ro WITH PASSWORD '$NEW_PASS';"
echo "ENV_GRAFANA_DB_PASSWORD=$NEW_PASS"    # → do .env, krok 2
```

**2. Zmienne środowiskowe** w `.env` deploymentu

```bash
ENV_GRAFANA_DB_PASSWORD=<hasło z kroku 1>     # bez wartości domyślnej — puste = brak połączenia
# opcjonalne, domyślnie dziedziczone po ENV_DB_*:
# ENV_GRAFANA_DB_HOST, ENV_GRAFANA_DB_PORT, ENV_GRAFANA_DB_NAME
# ENV_GRAFANA_DB_SSLMODE (domyślnie disable; ustaw require, jeśli baza jest poza hostem)
PLATFORM_METRICS_KEY=<klucz do /api/internal/metrics>   # puste = konsola API zwraca 503
```

**3. Usunięcie starego dashboardu**

To **nie zadzieje się samo** i nie zadziało się przez ostatnie tygodnie. Poprzedni provider
miał `disableDeletion: true`, więc Grafana nie kasuje zaprovisionowanego dashboardu po
zniknięciu jego pliku — zostaje osierocony w jej własnej bazie i dalej wygląda na aktualny.

```bash
curl -X DELETE -u admin:"$GRAFANA_ADMIN_PASSWORD" \
  https://grafana.detailboost.pl/api/dashboards/uid/detailing-crm-obs-v1
```

Kasujesz **widok**, nie dane — Prometheus dalej zbiera JVM, latency i błędy HTTP.
Nowy provider ma `disableDeletion: false`, więc od teraz repo jest źródłem prawdy w obie
strony i tego kroku nigdy więcej nie będzie.

**4. Restart Grafany**

```bash
docker compose up -d grafana
```

**5. Pierwsze przeliczenie** (inaczej wykresy biznesowe są puste do 03:10)

```bash
curl -X POST -H "X-Platform-Key: $PLATFORM_METRICS_KEY" \
  "https://api.detailboost.pl/api/internal/metrics/recompute?date=$(date +%F)"
```

Rezerwacje, wizyty i logowania wypełnią się wstecz z tabel źródłowych. Czas sesji i błędy
słusznie zaczynają od zera — nic ich wcześniej nie mierzyło, a wymyślanie wartości byłoby
gorsze niż widoczna luka.

### Do wdrożenia po stronie frontendu (osobno)

Heartbeat sesji i reporter błędów — bez nich czas pracy opiera się wyłącznie na sygnale
zapasowym (uwierzytelnione żądania API), a błędy frontowe nie są zbierane w ogóle.
Kontrakt w `docs/metrics-module-architecture.md`, sekcje 4 i 6.

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

Obie migracje, skrypt operacyjny, całe SQL z roll-upów i handlerów oraz wszystkie 32 zapytania
z dashboardów zostały wykonane na lokalnym PostgreSQL 16 z zaseedowanymi danymi dwóch studiów.
Sprawdzono też, że V66 przechodzi jako **zwykły użytkownik aplikacji** (czyli nie zablokuje
Flywaya przy starcie), a `CREATE ROLE` jako ten sam użytkownik faktycznie kończy się odmową —
dlatego rola jest poza migracjami.
Zweryfikowano również zgodność encji JPA ze schematem z V65 (151 kolumn w 10 tabelach, zero
rozjazdów), bo przy `ddl-auto=validate` każda brakująca kolumna to nieudany start aplikacji. Sprawdzone zostały
także wartości, nie tylko składnia — MRR, podział czasu OWNER/EMPLOYEE, wykluczenie pustych
sesji, sumy rezerwacji i SMS zgadzają się z ręcznym wyliczeniem.

Granica uprawnień `grafana_ro` przetestowana wykonaniem: 8 tabel/widoków czyta,
7 (klienci, wizyty, użytkownicy, rezerwacje, audit_logs, surowa tabela studios,
wystąpienia błędów) odmawia, zapis i DDL odmawia.

Czego **nie** zweryfikowano: renderowania w samej Grafanie. Schemat panelu, mapowania
kolorów i interpolacja zmiennej `$studio` są napisane pod Grafanę 11.0 i sprawdzone jako
poprawny JSON, ale nie zostały otwarte w przeglądarce.
