-- ═══════════════════════════════════════════════════════════════════════════════
-- Zlecenia zbiorcze: rozliczenie jest zamknięte, a jego dokument niezmienny.
--
-- is_correction: wpis rozliczony, który ktoś świadomie odblokował do korekty.
-- Dotąd każda edycja rozliczonego wpisu cicho zdejmowała mu is_closed i wpis
-- trafiał do rozliczenia drugi raz. Teraz rozliczonego wpisu nie da się zmienić
-- bez odblokowania, a odblokowany niesie ślad, że już raz był rozliczony.
--
-- snapshot_json: pozycje w chwili rozliczenia. PDF z historii składano z żywych
-- wpisów (close_history_id), więc późniejsza korekta lub ponowne rozliczenie
-- zmieniały dokument, który klient już dostał. NULL = rozliczenie sprzed tej
-- migracji; dla takich PDF nadal powstaje z wpisów, bo niczego lepszego nie ma.
--
-- closed_by_user_name: kto rozliczył. Kolumnę o tej nazwie usunęła V26; wraca
-- jako zwykły tekst (imię i nazwisko w chwili rozliczenia), bez klucza do users.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE batch_order_entries
    ADD COLUMN IF NOT EXISTS is_correction BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE batch_order_close_history
    ADD COLUMN IF NOT EXISTS snapshot_json JSONB,
    ADD COLUMN IF NOT EXISTS closed_by_user_name VARCHAR(255);
