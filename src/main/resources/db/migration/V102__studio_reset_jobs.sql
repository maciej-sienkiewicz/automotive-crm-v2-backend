-- Przebieg wyczyszczenia konta ("Wyczyść konto" w Ustawieniach → Bezpieczeństwo).
--
-- Jeden wiersz na uruchomienie. current_step rośnie po każdym zatwierdzonym kroku
-- purge'u, więc po padzie instancji job wznawia się dokładnie od pierwszego
-- niezatwierdzonego kroku (StudioResetJobRunner). Status trzymany jako VARCHAR
-- bez CHECK-a wyliczającego wartości enuma — patrz V101 i NoEnumCheckConstraintsTest:
-- taki CHECK jest ręczną kopią enuma z kodu i rozjeżdża się przy każdej nowej stałej.
CREATE TABLE IF NOT EXISTS studio_reset_jobs (
    id                 UUID PRIMARY KEY,
    studio_id          UUID NOT NULL,
    -- Owner, który zlecił reset — jedyne konto użytkownika, które przetrwa czyszczenie.
    requested_by       UUID NOT NULL,
    -- Do wpisu audytowego po zakończeniu — principal nie jest już wtedy dostępny.
    requested_by_name  VARCHAR(200) NOT NULL,
    wipe_company_data  BOOLEAN NOT NULL DEFAULT FALSE,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_step       INTEGER NOT NULL DEFAULT 0,
    total_steps        INTEGER NOT NULL DEFAULT 0,
    current_step_name  VARCHAR(200),
    error              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Podwaja rolę heartbeatu przy stanie RUNNING (StudioResetJobRunner odświeża go
    -- po każdym kroku) — martwy przebieg jest przez to odróżnialny od żywego.
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);

-- Odczyt aktywnego/ostatniego joba studia (start blokujący duplikat, GET /reset/latest).
CREATE INDEX IF NOT EXISTS idx_studio_reset_jobs_studio
    ON studio_reset_jobs (studio_id);
