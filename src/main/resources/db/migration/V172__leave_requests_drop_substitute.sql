-- ═══════════════════════════════════════════════════════════════════════════════
-- Wnioski urlopowe bez osoby zastępującej (kontrakt v2 z 01.10.2026).
--
-- Decyzja biznesu: wniosek urlopowy nie wskazuje osoby zastępującej. Pole znika z API,
-- walidacji i dokumentu PDF, więc kolumna nie ma już właściciela w kodzie, a zostawiona
-- udawałaby, że ta informacja gdzieś jeszcze żyje. Wnioski złożone przed tą zmianą niczego nie tracą: ich
-- podpisane pliki PDF w magazynie są niezmienne i nadal pokazują zastępcę, którego
-- pracownik podpisał — kolumna była tylko kopią tego, co jest na dokumencie.
--
-- V171 już przeszła na produkcji, dlatego zmiana idzie osobną migracją, a nie edycją
-- tamtej. IF EXISTS: baza założona od zera po tej zmianie też przechodzi bez błędu.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE leave_requests DROP COLUMN IF EXISTS substitute_employee_id;
