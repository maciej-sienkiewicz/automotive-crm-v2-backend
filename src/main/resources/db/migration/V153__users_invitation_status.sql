-- Status zaproszenia pracownika.
--
-- Karta pracownika pokazywała „Konto aktywne" od chwili utworzenia konta, choć pracownik
-- nie ustawił jeszcze hasła z zaproszenia: users.is_active mówi tylko, że konto nie jest
-- zablokowane. Nowe kolumny:
--   invitation_pending - konto z zaproszenia, którego pracownik jeszcze nie aktywował;
--                        gaśnie, gdy ustawi hasło z linku albo pierwszy raz wejdzie do
--                        aplikacji (/api/auth/me),
--   invitation_sent_at - kiedy doszło ostatnie zaproszenie (do wyliczenia ważności linku
--                        i dla „Wyślij maila ponownie").

ALTER TABLE users ADD COLUMN IF NOT EXISTS invitation_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS invitation_sent_at TIMESTAMPTZ;

-- Istniejące konta: aktywacja nie była nigdzie zapisywana. Pierwsze wejście do aplikacji
-- nadaje jednak kontu mobile_token (/api/auth/me -> MobileTokenService.ensureToken) i nic
-- go potem nie kasuje - konto pracownika bez niego to konto, z którego nikt się jeszcze
-- nie zalogował. Takie konta oznaczamy jako czekające; zaproszenie wyszło przy ich
-- utworzeniu. Gdyby któryś pracownik jednak z konta korzystał, pierwsze wejście do
-- aplikacji zdejmie znacznik samo.
--
-- users.mobile_token i employees.user_id zakłada Hibernate, nie migracja - na środowisku,
-- które ich jeszcze nie ma, rozpoznanie nie ma na czym się oprzeć i konta zostają jak dotąd.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'mobile_token'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'employees' AND column_name = 'user_id'
    ) THEN
        EXECUTE $sql$
            UPDATE users u
            SET invitation_pending = TRUE,
                invitation_sent_at = u.created_at
            WHERE NOT u.is_owner
              AND u.mobile_token IS NULL
              AND EXISTS (SELECT 1 FROM employees e WHERE e.user_id = u.id)
        $sql$;
    END IF;
END $$;
