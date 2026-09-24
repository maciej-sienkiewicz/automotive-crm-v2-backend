-- ═══════════════════════════════════════════════════════════════════════════════
-- Konfigurator stopki: projekt (motyw, dane, styl) obok wyrenderowanego HTML-a.
--
-- body_html pozostaje JEDYNYM źródłem wysyłanej treści. design_json służy wyłącznie
-- temu, żeby kreator otworzył się z tym, co użytkownik ustawił poprzednio - z samego
-- HTML-a motywu nie da się tego odtworzyć (kolor, czcionka, styl ikon, puste pola).
--
-- NULL = stopka pisana zwykłym tekstem, czyli każda stopka sprzed tej migracji;
-- żaden backfill nie jest potrzebny.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE comm_user_signatures ADD COLUMN IF NOT EXISTS design_json TEXT;
