-- Żądanie podpisu dotyczy protokołu wizyty ALBO listy obecności.
--
-- Do tej pory tablet i link z SMS-a obsługiwały wyłącznie protokoły wizyt, więc
-- visit_id i protocol_id były wymagane. Listę obecności zatwierdzający podpisuje teraz
-- tak samo - na tablecie studia albo na własnym telefonie - i jej żądanie nie ma ani
-- wizyty, ani protokołu, tylko arkusz.
ALTER TABLE signature_requests ALTER COLUMN visit_id DROP NOT NULL;
ALTER TABLE signature_requests ALTER COLUMN protocol_id DROP NOT NULL;
ALTER TABLE signature_requests ADD COLUMN IF NOT EXISTS attendance_sheet_id UUID;

CREATE INDEX IF NOT EXISTS idx_signature_requests_attendance_sheet
    ON signature_requests (studio_id, attendance_sheet_id);

-- Dokładnie jeden podmiot: żądanie bez żadnego (albo z oboma naraz) nie miałoby czego
-- oznaczyć jako podpisane po złożeniu podpisu.
ALTER TABLE signature_requests DROP CONSTRAINT IF EXISTS ck_signature_requests_subject;
ALTER TABLE signature_requests ADD CONSTRAINT ck_signature_requests_subject CHECK (
    (attendance_sheet_id IS NULL AND visit_id IS NOT NULL AND protocol_id IS NOT NULL)
    OR (attendance_sheet_id IS NOT NULL AND visit_id IS NULL AND protocol_id IS NULL)
);
