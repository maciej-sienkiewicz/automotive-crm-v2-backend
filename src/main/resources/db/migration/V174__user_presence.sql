-- ═══════════════════════════════════════════════════════════════════════════════
-- Okno pracownika: „Ostatnio w aplikacji" i „Ostatnie logowanie".
--
-- Dwie różne kolumny, bo sesja żyje długo: kto zalogował się w poniedziałek i nie
-- zamknął karty, pracuje w aplikacji cały tydzień bez nowego logowania. Samo
-- last_login_at pokazywałoby wtedy „poniedziałek" przy kimś, kto był minutę temu.
--
-- last_login_at - udane logowanie hasłem albo PIN-em (przełącznik profili).
-- last_seen_at  - ostatnie zapytanie API zalogowanego użytkownika, zapisywane najwyżej
--                 raz na godzinę (UserPresenceService), żeby aktywność nie pisała do bazy
--                 przy każdym kliknięciu.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;
