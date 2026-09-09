-- Faktyczne usunięcie enumowych CHECK-ów — poprawka błędnego wzorca z V101 i V117.
--
-- ## Co było nie tak
--
-- V101 (globalnie) oraz V117 (punktowo dla communication_log.status) miały skasować
-- CHECK-i wyliczające dozwolone wartości enuma. Obie szukały ich wzorcem
-- `LIKE '%= ANY (ARRAY[%'`.
--
-- Postgres normalizuje jednak listę stałych na kolumnie typu varchar do postaci
-- z rzutowaniem tablicy na text[]:
--
--     ((status)::text = ANY ((ARRAY['SENT'::character varying, ...])::text[]))
--
-- czyli `= ANY (` a po nim DRUGI nawias `(ARRAY[`. Wzorzec zakładał jeden nawias
-- (`= ANY (ARRAY[`), a to nie jest podciąg `= ANY ((ARRAY[`. Efekt: pętle w V101 i V117
-- dopasowywały ZERO ograniczeń, kończyły się bez błędu (Flyway zapisał je jako success),
-- a ograniczenia zostawały w schemacie. Dokładnie tak samo mielił po cichu
-- EnumCheckConstraintDropper przy każdym starcie.
--
-- ## Skutek na produkcji
--
-- communication_log_status_check nadal dopuszczał wyłącznie 'SENT', 'RECEIVED', 'FAILED'.
-- Pierwszy wpis ze statusem QUEUED — wiadomość odłożona do kolejki poza oknem wysyłki
-- 12:00–18:00 (np. Karta Wizyty wysyłana wieczorem) — wywracał INSERT, a przez zatrutą
-- transakcję całą operację wysyłki. Zostawały też pełne, lecz równie niepotrzebne,
-- CHECK-i na communication_log.channel i communication_log.message_type.
--
-- ## Ta migracja
--
-- Powtarza globalne sprzątanie z V101, ale wzorcem `LIKE '%= ANY (%ARRAY[%'`, który łapie
-- obie formy: z jednym i z podwójnym nawiasem. Ograniczenia biznesowe (minutes 0–1440,
-- zakresy dat) nie mają tej postaci i zostają nietknięte. Ten sam poprawiony wzorzec
-- dostał EnumCheckConstraintDropper, więc na bazach zakładanych przez Hibernate te
-- ograniczenia znikają już przy starcie aplikacji. Idempotentna: DROP ... IF EXISTS.

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
          AND pg_get_constraintdef(c.oid) LIKE '%= ANY (%ARRAY[%'
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

    RAISE NOTICE 'Enumowe CHECK-i (poprawiony wzorzec): usunięto % ograniczeń', dropped_count;
END $$;
