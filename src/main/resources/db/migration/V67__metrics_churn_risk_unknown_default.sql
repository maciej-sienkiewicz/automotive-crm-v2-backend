-- ═══════════════════════════════════════════════════════════════════════════════
-- Domyślna wartość churn_risk: 'HEALTHY' → 'UNKNOWN'.
--
-- ## Dlaczego to osobna migracja, a nie poprawka w V65
--
-- Bo V65 już się wykonała na produkcji. Zmiana zaaplikowanej migracji zmienia jej sumę
-- kontrolną, a Flyway przy starcie porównuje ją z zapisaną w flyway_schema_history
-- i odmawia migracji przy rozjeździe. Ponieważ w profilu produkcyjnym Flyway startuje
-- razem z aplikacją, skutkiem jest pętla restartów całego CRM-a — dokładnie to zdarzyło
-- się po edycji V65, i dokładnie dlatego ta zmiana jest tutaj, a nie tam.
--
-- Reguła bez wyjątków: migracja raz wykonana na jakimkolwiek środowisku jest niezmienna.
-- Dotyczy także komentarzy — suma kontrolna liczona jest z całego pliku, nie z samego SQL.
--
-- ## Co poprawia
--
-- Wiersz snapshotu jest zakładany, zanim TenantHealthCalculator policzy wynik. Z domyślną
-- wartością 'HEALTHY' powstawała para niespójna: health_score = 0 (najgorszy możliwy wynik)
-- z etykietą mówiącą „zdrowy". Kalkulator łapie własne wyjątki i tylko loguje, więc jego
-- awaria pomalowałaby cały board retencyjny na zielono — awaria w kierunku pochlebnym,
-- najgorszy możliwy tryb błędu dla modułu metryk.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE metric_daily_studio_snapshots
    ALTER COLUMN churn_risk SET DEFAULT 'UNKNOWN';

-- Wiersze zaseedowane przez backfill z V65 dostały wtedy jeszcze 'HEALTHY'.
-- Predykat jest precyzyjny: wynik 0 przy etykiecie 'HEALTHY' to właśnie ta niespójna para —
-- wiersz policzony naprawdę i mający 0 punktów zostałby oznaczony jako 'CRITICAL',
-- nigdy jako zdrowy. Nie ruszamy więc żadnego realnie wyliczonego wiersza.
UPDATE metric_daily_studio_snapshots
SET churn_risk = 'UNKNOWN'
WHERE health_score = 0
  AND churn_risk = 'HEALTHY';
