-- Sesje zdjęciowe bez rezerwacji.
--
-- Check-in „z marszu" (/checkin/new) zaczyna od zdjęć pojazdu — rezerwacja ani
-- wizyta jeszcze nie istnieją, więc frontend tworzy sesję bez appointment_id.
-- Dopóki kolumna była NOT NULL, POST /api/photo-sessions kończył się 400 i cały
-- krok dokumentacji fotograficznej był w tym scenariuszu nieosiągalny.
-- Powiązanie z wizytą i tak powstaje później, przy claimowaniu sesji (visit_id).
ALTER TABLE IF EXISTS photo_upload_sessions
    ALTER COLUMN appointment_id DROP NOT NULL;
