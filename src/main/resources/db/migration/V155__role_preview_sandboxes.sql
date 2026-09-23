-- Podgląd roli: administrator ogląda CRM oczami pracownika z daną rolą.
--
-- Podgląd działa na prawdziwym backendzie, ale w piaskownicy - jednorazowym studiu z danymi
-- przykładowymi, bez haseł i bez możliwości zalogowania, do którego wchodzi się wyłącznie
-- jednorazowym kodem, pod osobnym adresem (np. podglad.detailboost.pl). Piaskownica znika
-- przy wyjściu z podglądu, a najpóźniej po upływie jej czasu życia.

-- Rodzaj studia: REGULAR (studio klienta), DEMO (konto demo z ekranu logowania),
-- ROLE_PREVIEW (piaskownica podglądu roli). Nadawany raz, przy zakładaniu studia, i nigdy
-- nie zmieniany - od niego zależą blokady logowania, wysyłek na zewnątrz i zadań platformy.
ALTER TABLE studios ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR';

CREATE INDEX IF NOT EXISTS idx_studios_kind_non_regular ON studios (kind) WHERE kind <> 'REGULAR';

-- Konta demo sprzed tej zmiany. Tabelę demo_accounts zakłada Hibernate, nie migracja -
-- tam, gdzie jej jeszcze nie ma, nie ma też czego oznaczać.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'demo_accounts'
    ) THEN
        EXECUTE $sql$
            UPDATE studios s
            SET kind = 'DEMO'
            WHERE s.kind = 'REGULAR'
              AND EXISTS (SELECT 1 FROM demo_accounts d WHERE d.studio_id = s.id)
        $sql$;
    END IF;
END $$;

-- Rejestr piaskownic: skąd piaskownica się wzięła, kto ją otworzył, jaką rolę podgląda,
-- jak się do niej wchodzi i kiedy wygasa.
--
-- entry_code_hash - SHA-256 jednorazowego kodu wejścia (sam kod nigdy nie trafia do bazy),
-- entered_at      - kiedy kod wymieniono na sesję; kod działa dokładnie raz,
-- session_id      - sesja pracownika piaskownicy, usuwana razem z piaskownicą,
-- last_activity_at / expires_at - wygasanie po bezczynności i bezwzględne.
CREATE TABLE IF NOT EXISTS role_preview_sandboxes (
    id                      UUID PRIMARY KEY,
    sandbox_studio_id       UUID         NOT NULL,
    source_studio_id        UUID         NOT NULL,
    created_by_user_id      UUID         NOT NULL,
    created_by_name         VARCHAR(200) NOT NULL,
    owner_user_id           UUID         NOT NULL,
    employee_user_id        UUID         NOT NULL,
    role_id                 UUID         NOT NULL,
    role_name               VARCHAR(100) NOT NULL,
    initial_permissions     TEXT         NOT NULL,
    initial_track_work_time BOOLEAN      NOT NULL,
    entry_code_hash         VARCHAR(64)  NOT NULL,
    entry_code_expires_at   TIMESTAMPTZ  NOT NULL,
    entered_at              TIMESTAMPTZ,
    session_id              VARCHAR(100),
    created_at              TIMESTAMPTZ  NOT NULL,
    last_activity_at        TIMESTAMPTZ  NOT NULL,
    expires_at              TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_role_preview_sandboxes_studio ON role_preview_sandboxes (sandbox_studio_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_preview_sandboxes_code ON role_preview_sandboxes (entry_code_hash);
CREATE INDEX IF NOT EXISTS idx_role_preview_sandboxes_source ON role_preview_sandboxes (source_studio_id);
CREATE INDEX IF NOT EXISTS idx_role_preview_sandboxes_expires ON role_preview_sandboxes (expires_at);
