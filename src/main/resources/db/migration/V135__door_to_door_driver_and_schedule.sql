-- Door to Door: kierowca, termin dostarczenia i jawne włączenie usługi.
--
-- Właściciel studia zgłosił, że przy zlecaniu dowozu musi wskazać, KTO wiezie
-- i NA KIEDY. Dotąd rekord przechowywał wyłącznie dwa adresy i uwagi, więc
-- ustalenia padały przez telefon i nie zostawały nigdzie w systemie.
--
-- Tabela `door_to_door` powstała historycznie przez hibernate ddl-auto=update,
-- nie z migracji, dlatego kolumny dokładamy warunkowo (IF NOT EXISTS): na
-- środowiskach deweloperskich Hibernate mógł je już założyć z encji, a migracja
-- i tak musi istnieć dla profilu docker, który startuje z ddl-auto=validate.

ALTER TABLE door_to_door
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Bez klucza obcego do employees: rekord dowozu ma przeżyć usunięcie pracownika,
-- a nazwisko trzymamy obok w migawce (`driver_name`), żeby historyczna wizyta
-- nadal pokazywała, kto wiózł auto.
ALTER TABLE door_to_door
    ADD COLUMN IF NOT EXISTS driver_id UUID;

ALTER TABLE door_to_door
    ADD COLUMN IF NOT EXISTS driver_name VARCHAR(200);

ALTER TABLE door_to_door
    ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMP;

-- Istniejące rekordy to usługi już zlecone - wszystkie zostają włączone.
UPDATE door_to_door SET enabled = TRUE WHERE enabled IS NULL;

-- Lista "co dziś rozwozimy": kierowca + termin w obrębie studia.
CREATE INDEX IF NOT EXISTS idx_d2d_driver_scheduled
    ON door_to_door (studio_id, driver_id, scheduled_at);
