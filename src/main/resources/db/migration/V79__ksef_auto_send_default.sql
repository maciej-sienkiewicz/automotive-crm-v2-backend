-- Domyślna odpowiedź na pytanie „czy wysłać fakturę do KSeF?" przy wydaniu pojazdu.
--
-- Wysyłka do KSeF przestaje być niejawnym skutkiem wystawienia faktury: modal
-- wydania pojazdu ma przełącznik, a to ustawienie decyduje o jego początkowej
-- pozycji. TRUE zachowuje dotychczasowe zachowanie (wysyłka automatyczna) dla
-- wszystkich istniejących studiów.

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS ksef_auto_send_default BOOLEAN NOT NULL DEFAULT TRUE;
