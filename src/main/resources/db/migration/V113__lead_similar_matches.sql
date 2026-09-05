-- Zapisany wynik doboru podobnych zleceń — jeden wiersz na leada.
--
-- Do tej pory dobór liczył się przy każdym otwarciu sekcji. Teraz liczy się RAZ,
-- w tle, gdy tylko auto leada jest rozstrzygnięte (rozpoznane, nierozpoznane po
-- próbie albo wpisane ręcznie), a otwarcie leada zastaje wynik gotowy.
-- „Sprawdź ponownie" przelicza na życzenie — np. gdy indeks urósł albo cennik
-- się zmienił.
--
-- W wierszu leżą wyłącznie identyfikatory zleceń z rangą dopasowania; kwoty
-- i nazwy usług doczytują się z bazy przy każdym odczycie, żeby nie pokazywać
-- wczorajszych cen. Leady sprzed tej migracji nie mają wiersza i policzą się
-- leniwie przy pierwszym otwarciu — celowo bez backfillu.
CREATE TABLE IF NOT EXISTS lead_similar_matches (
    lead_id      UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    -- SERVICE_NOT_IN_CATALOG | VEHICLE_UNKNOWN | NULL
    empty_reason VARCHAR(40),
    -- pary "visitId;RANGA" rozdzielone |, w kolejności doboru
    matches      TEXT NOT NULL DEFAULT '',
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_lead_similar_matches_studio
    ON lead_similar_matches (studio_id);
