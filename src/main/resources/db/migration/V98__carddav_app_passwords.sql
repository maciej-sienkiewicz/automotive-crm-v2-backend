-- Automatyczna konfiguracja kontaktów na iPhonie (profil .mobileconfig).
--
-- Serwer CardDAV uwierzytelniał dotąd Basic auth wyłącznie hasłem konta —
-- stąd ręczne przepisywanie danych w ustawieniach telefonu. Hasło aplikacyjne
-- to sekret wygenerowany per TELEFON: ląduje w profilu konfiguracyjnym,
-- użytkownik go nigdy nie widzi, a odwołanie odcina jeden telefon bez
-- dotykania konta ani pozostałych urządzeń.
CREATE TABLE IF NOT EXISTS carddav_app_passwords (
    id           UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    user_id      UUID NOT NULL,
    device_name  VARCHAR(120) NOT NULL,
    secret_hash  VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Ostatnie udane logowanie synchronizacji — mówi, czy profil na telefonie żyje.
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

-- Basic auth niesie tylko e-mail, więc przy każdym żądaniu synchronizacji
-- sprawdzamy aktywne hasła aplikacyjne tego użytkownika.
CREATE INDEX IF NOT EXISTS idx_carddav_app_passwords_active_user
    ON carddav_app_passwords (user_id) WHERE revoked_at IS NULL;

-- Jednorazowy link instalacyjny. Niesie hasło aplikacyjne otwartym tekstem,
-- bo profil generujemy dopiero przy pobraniu — dlatego żyje minuty, ma jedno
-- użycie, a sekret jest zerowany w chwili pobrania.
CREATE TABLE IF NOT EXISTS carddav_provisionings (
    id              UUID PRIMARY KEY,
    app_password_id UUID NOT NULL REFERENCES carddav_app_passwords(id) ON DELETE CASCADE,
    token           VARCHAR(64) NOT NULL UNIQUE,
    secret_plain    VARCHAR(64),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
