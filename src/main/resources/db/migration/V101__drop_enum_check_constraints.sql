-- Usunięcie CHECK-ów wyliczających dozwolone wartości enumów.
--
-- ## Decyzja
--
-- Rezygnujemy z tej klasy ograniczeń w całym schemacie. Nie dlatego, że walidacja jest
-- zła, tylko dlatego, że ta konkretna walidacja nie ma jak być prawdziwa: lista wartości
-- powstaje raz, przy zakładaniu tabeli przez `ddl-auto=update`, i nigdy się nie
-- aktualizuje. Enum w Kotlinie rośnie, baza zostaje w tyle, a rozjazd wychodzi dopiero na
-- produkcji — przy pierwszym użyciu nowej stałej.
--
-- Rachunek jest jednostronny. Jedynym pisarzem tych kolumn jest Hibernate, który mapuje
-- enuma na tekst, więc wartość spoza enuma nie ma jak tam trafić: CHECK chroni przed
-- czymś, co i tak nie może się zdarzyć. Cena bywa za to dotkliwa:
--
--  * `SIGNATURE_LINK_SMS` w `communication_log` — INSERT wykonuje się przy commicie, więc
--    naruszenie wywracało transakcję PO wysłaniu SMS-a. Klient dostawał wiadomość,
--    a żądanie podpisu znikało wraz z rollbackiem: „Link jest nieprawidłowy lub wygasł".
--  * `COMMUNICATION`, `VISIT_CARD`, `CAMPAIGN`, `WORK_TIME` w `audit_logs.module` — audyt
--    pisze we własnej transakcji i łyka wyjątki, więc wpisy ginęły po cichu. Cztery
--    moduły nie istniały w Aktywności.
--
-- To trzeci taki incydent. Ograniczenie, które regularnie psuje produkcję, a niczego nie
-- chroni, jest kosztem bez pokrycia.
--
-- ## Zakres
--
-- Kasujemy WYŁĄCZNIE ograniczenia o postaci `... = ANY (ARRAY['A', 'B', ...])` — tak
-- Postgres normalizuje `CHECK (kol IN (...))`. Ograniczenia biznesowe zostają:
-- `work_time_entries.minutes` (0–1440) i `chk_calendar_events_range` nie mają tej postaci
-- i nie zostaną ruszone.
--
-- ## Uwaga operacyjna
--
-- Ta migracja porządkuje bazę, która już istnieje. NIE powstrzymuje Hibernate'a przed
-- wygenerowaniem nowych CHECK-ów przy zakładaniu kolejnej tabeli z enumem — tym zajmuje
-- się [EnumCheckConstraintDropper], uruchamiany przy każdym starcie aplikacji. Migracja
-- jest tu dla porządku i dla środowisk, gdzie schemat aktualizuje się ręcznie
-- (`spring.flyway.enabled=false` — migracje nie idą automatycznie przy starcie).
--
-- Zastępuje V100, która próbowała jeszcze te listy dopisywać. Jeśli V100 została już
-- zastosowana, ta migracja po prostu usunie to, co tamta poprawiła — wynik jest ten sam.

DO $$
DECLARE
    constraint_row RECORD;
    dropped_count  INT := 0;
BEGIN
    FOR constraint_row IN
        SELECT c.conrelid::regclass::text AS table_name,
               c.conname                  AS constraint_name
        FROM pg_constraint c
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE c.contype = 'c'
          AND n.nspname = current_schema()
          AND pg_get_constraintdef(c.oid) LIKE '%= ANY (ARRAY[%'
        ORDER BY 1, 2
    LOOP
        EXECUTE format(
            'ALTER TABLE %s DROP CONSTRAINT IF EXISTS %I',
            constraint_row.table_name,
            constraint_row.constraint_name
        );
        RAISE NOTICE 'Usunięto enumowy CHECK % z tabeli %',
            constraint_row.constraint_name, constraint_row.table_name;
        dropped_count := dropped_count + 1;
    END LOOP;

    RAISE NOTICE 'Enumowe CHECK-i: usunięto % ograniczeń', dropped_count;
END $$;
