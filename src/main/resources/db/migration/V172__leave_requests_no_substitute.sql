-- Wnioski urlopowe bez osoby zastępującej i bez wiersza „Podstawa uprawnienia" na PDF.
--
-- Zastępstw nie prowadzimy: kolumna znika razem z polem w API i na dokumencie.
--
-- pdf_layout_version: stemple (podpis pracownika, decyzja) piszą w stałe miejsca strony,
-- a nowy układ przesuwa część „Decyzja pracodawcy". Wnioski już wygenerowane dostają 1
-- i decyzję w swoim dawnym układzie; nowe zapisuje aplikacja z wersją bieżącą (2).
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS pdf_layout_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE leave_requests ALTER COLUMN pdf_layout_version SET DEFAULT 2;
ALTER TABLE leave_requests DROP COLUMN IF EXISTS substitute_employee_id;
