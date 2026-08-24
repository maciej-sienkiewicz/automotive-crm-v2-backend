-- Niepodpisana zgoda nie jest dokumentem wizyty.
--
-- Wiersz w visit_documents zakładany był przy generowaniu dokumentu do podpisu,
-- więc klient, który zgody nie podpisał, zostawiał po sobie pusty formularz
-- widoczny w dokumentach wizyty. Od teraz wiersz powstaje dopiero po podpisie;
-- tutaj sprzątamy te, które zdążyły powstać wcześniej.
--
-- Kasujemy wyłącznie wiersze wskazujące na WYPEŁNIONY (niepodpisany) plik zgody,
-- której protokół nie jest podpisany. Podpisane dokumenty zostają nietknięte.
DELETE FROM visit_documents d
USING visit_protocols vp
WHERE d.file_id = vp.filled_pdf_s3_key
  AND vp.consent_definition_id IS NOT NULL
  AND vp.status <> 'SIGNED'
  AND d.category = 'consent';
