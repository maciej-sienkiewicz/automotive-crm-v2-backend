-- ═══════════════════════════════════════════════════════════════════════════════
-- Stan rozpoznawania pojazdu na leadzie.
--
-- Marka i model czytają się z korespondencji przez LLM już PO utworzeniu leada —
-- samo oznaczenie musi być natychmiastowe. Bez zapisanego stanu tabela nie umiałaby
-- odróżnić „jeszcze nie wiadomo" od „sprawdziliśmy i nie ma": w obu przypadkach
-- kolumna jest pusta, a to dwie różne informacje dla człowieka, który patrzy.
--
-- Dwie wartości wystarczą, bo trzeci stan wynika z danych:
--   PENDING            → rozpoznanie w toku (interfejs pokazuje spinner),
--   DONE + marka       → rozpoznano,
--   DONE + brak marki  → nie rozpoznano (błąd LLM albo klient nie podał auta).
--
-- Istniejące leady dostają DONE: nikt ich nie analizuje, więc nie mają prawa
-- kręcić spinnerem w nieskończoność.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS vehicle_detection_status VARCHAR(20) NOT NULL DEFAULT 'DONE';

-- Nowe leady z wątku pocztowego ustawiają PENDING jawnie w kodzie; default DONE
-- chroni wszystkie pozostałe ścieżki (telefon, formularz, notatka głosowa),
-- w których nikt nie analizuje korespondencji.
