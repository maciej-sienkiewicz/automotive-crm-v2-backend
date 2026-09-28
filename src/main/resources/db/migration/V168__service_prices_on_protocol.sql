-- Ceny usług na protokole przyjęcia pojazdu.
--
-- Protokół wypisuje nazwy usług i jedną kwotę łączną. Część studiów chce przy każdej
-- usłudze jej cenę w nawiasie, część - świadomie tylko sumę (klient nie negocjuje
-- pozycji). FALSE zachowuje dotychczasowy wygląd dla wszystkich istniejących studiów
-- i jest domyślny dla nowych.

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS service_prices_on_protocol BOOLEAN NOT NULL DEFAULT FALSE;
