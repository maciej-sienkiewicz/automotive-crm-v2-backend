-- Zgłoszenia z formularzy na stronach studiów dostają własne wątki.
--
-- Robot formularza (WP Mail SMTP, WordPress, Elementor…) wysyła każde zgłoszenie
-- z jednego adresu — często z adresu samego studia — pod jednym tematem, a klienta
-- wpisuje w nagłówek Reply-To. Wątkowanie „ten sam temat + ten sam nadawca" sklejało
-- więc zgłoszenia dziesiątek ludzi w jedną rozmowę, a odpowiedź z CRM-a szła na adres
-- robota, czyli do studia. Szczegóły: CommThreadKind, InboundRouter.

-- Reply-To wiadomości: adres, na który odpowiada każdy program pocztowy.
ALTER TABLE comm_messages
    ADD COLUMN IF NOT EXISTS reply_to_email VARCHAR(320),
    ADD COLUMN IF NOT EXISTS reply_to_name  VARCHAR(255);

-- DIRECT | FORM | SYSTEM. Istniejące wątki są zwykłymi rozmowami — dotychczasowe
-- wielkie wątki formularzy rozplata automat po synchronizacji skrzynki
-- (FormThreadAutoUntangler); untangled_at zapisuje, że wątek już przejrzał.
ALTER TABLE comm_threads
    ADD COLUMN IF NOT EXISTS kind             VARCHAR(10) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN IF NOT EXISTS relay_email      VARCHAR(320),
    ADD COLUMN IF NOT EXISTS title            VARCHAR(300),
    ADD COLUMN IF NOT EXISTS screening        VARCHAR(20),
    ADD COLUMN IF NOT EXISTS screening_reason VARCHAR(300),
    ADD COLUMN IF NOT EXISTS untangled_at     TIMESTAMPTZ;

-- Duplikat zgłoszenia (ta sama osoba wysłała formularz dwa razy w kilka minut) i wątek
-- zwrotów skrzynki szukane są przy imporcie KAŻDEJ wiadomości — po indeksie.
CREATE INDEX IF NOT EXISTS idx_comm_threads_account_kind_participant
    ON comm_threads (account_id, kind, participant_email, last_message_at);

-- Zakładka „Odrzucone" to garść wierszy na tle całej skrzynki.
CREATE INDEX IF NOT EXISTS idx_comm_threads_screening
    ON comm_threads (studio_id, last_message_at)
    WHERE screening IS NOT NULL;
