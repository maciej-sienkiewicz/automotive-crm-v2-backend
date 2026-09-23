-- Rozliczenia: lista obecności ma stan i widać, kto nad nią pracował.
--
-- Dotąd po wygenerowaniu listy plik trafiał do folderu Pobrane jednej osoby, a system
-- o nim zapominał. Przy kilku administratorach nie było wiadomo, czy lista jest już
-- sprawdzona i wysłana. Teraz każda lista leży w zakładce Rozliczenia ze stanem:
--   GENERATED - wygenerowana, czeka na zatwierdzenie,
--   APPROVED  - sprawdzona i zatwierdzona (docelowo też: wysłana do księgowości).

ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'GENERATED';
-- Imię i nazwisko przepisane w chwili zdarzenia, jak signer_name: konto może zmienić
-- nazwę albo zniknąć, a rozliczenie ma zostać czytelne.
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(200);
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS approved_by UUID;
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS approved_by_name VARCHAR(200);

-- Autor list sprzed tej zmiany: zapisywany był tylko identyfikator konta.
UPDATE attendance_sheets s
SET created_by_name = btrim(u.first_name || ' ' || u.last_name)
FROM users u
WHERE u.id = s.created_by
  AND s.created_by_name IS NULL;

-- Podpis złożony pod listą był jej potwierdzeniem („Podpis osoby potwierdzającej"),
-- więc podpisana lista jest zatwierdzona - przez podpisującego, w chwili podpisu.
UPDATE attendance_sheets
SET status = 'APPROVED',
    approved_at = signed_at,
    approved_by = signed_by,
    approved_by_name = signer_name
WHERE signed_file_s3_key IS NOT NULL
  AND status = 'GENERATED';
