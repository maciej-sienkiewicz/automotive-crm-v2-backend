-- Logo studia: warianty generowane z jednego wgranego pliku + przełącznik na dokumentach.
--
-- Właściciel warsztatu wgrywa JEDEN plik. Backend robi z niego dwa rastry i (dla SVG)
-- zachowuje oczyszczony wektor:
--   logo_s3_key        — wariant do aplikacji (menu boczne, ustawienia); to ten klucz
--                        podpisujemy i zwracamy jako logoUrl, tak jak dotąd,
--   logo_print_s3_key  — wariant do dokumentów: PNG w rozdzielczości do druku A4,
--                        stemplowany przez PDFBox w nagłówku protokołów i zgód,
--   logo_vector_s3_key — oczyszczony SVG (bez skryptów i odwołań zewnętrznych);
--                        trafia do szablonów HTML, gdzie wektor daje ostrość na każdym DPI.
--
-- logo_s3_key istniało wcześniej tylko z ddl-auto=update (bez migracji) — stąd
-- IF NOT EXISTS, żeby produkcja z ddl-auto=validate wystartowała niezależnie od tego,
-- czy kolumna już tam jest.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS logo_s3_key VARCHAR(500);

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS logo_print_s3_key VARCHAR(500);

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS logo_vector_s3_key VARCHAR(500);

-- „Czy umieszczać logo na dokumentach?" DEFAULT TRUE: kto wgrał logo, oczekuje go
-- na papierze — wyłączenie jest świadomą decyzją (np. własny szablon z logiem w treści).
-- Logo istniejące przed tą migracją (logo_s3_key bez logo_print_s3_key) jest
-- przetwarzane w locie przy generowaniu dokumentu, więc żaden backfill nie jest potrzebny.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS logo_on_documents BOOLEAN NOT NULL DEFAULT TRUE;
