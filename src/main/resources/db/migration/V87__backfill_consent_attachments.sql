-- Podpisane elektronicznie zgody nie miały wskazanego pliku: attachment_s3_key
-- zostawał pusty, więc karta klienta twierdziła, że zgody nie ma czym pokazać
-- („Nie dołączyłeś skanu zgody"), mimo że podpisany PDF leżał na protokole wizyty.
--
-- Dowiązujemy istniejące zgody do podpisanego dokumentu tej samej definicji,
-- tego samego klienta i tego samego studia. Dopasowanie idzie po dokumencie
-- podpisanym najbliżej momentu udzielenia zgody — jeden klient może mieć wiele
-- wizyt, a zgoda powstaje w tej samej transakcji co podpis protokołu.
UPDATE customer_consents cc
SET attachment_s3_key = matched.signed_pdf_s3_key
FROM (
    SELECT DISTINCT ON (c.id)
        c.id AS consent_id,
        vp.signed_pdf_s3_key
    FROM customer_consents c
    JOIN consent_templates ct
      ON ct.id = c.template_id
    JOIN visit_protocols vp
      ON vp.consent_definition_id = ct.definition_id
     AND vp.studio_id = c.studio_id
     AND vp.signed_pdf_s3_key IS NOT NULL
    JOIN visits v
      ON v.id = vp.visit_id
     AND v.customer_id = c.customer_id
    WHERE c.attachment_s3_key IS NULL
    ORDER BY c.id, ABS(EXTRACT(EPOCH FROM (vp.signed_at - c.signed_at)))
) AS matched
WHERE cc.id = matched.consent_id
  AND cc.attachment_s3_key IS NULL;

-- Ta sama luka po stronie dokumentów wizyty: podpisana zgoda nie miała własnego
-- wiersza w visit_documents, więc w sekcji „Dokumenty" widać było tylko protokół
-- przyjęcia. Dokładamy wiersz wskazujący na podpisany plik zgody.
--
-- file_url jest wyliczany przy każdym odczycie z file_id (presigned URL żyje
-- kilkanaście minut), więc wstawiamy pusty ciąg zamiast martwego adresu.
INSERT INTO visit_documents (
    id, visit_id, customer_id, type, name, file_name, file_id, file_url,
    uploaded_at, uploaded_by, uploaded_by_name, category
)
SELECT
    gen_random_uuid(),
    vp.visit_id,
    v.customer_id,
    'PROTOCOL',
    LEFT(cd.name || ' — ' || v.visit_number, 255),
    LEFT('ZGD_' || v.visit_number || '.pdf', 255),
    vp.signed_pdf_s3_key,
    '',
    COALESCE(vp.signed_at, vp.updated_at),
    v.created_by,
    'System',
    'consent'
FROM visit_protocols vp
JOIN visits v ON v.id = vp.visit_id
JOIN consent_definitions cd ON cd.id = vp.consent_definition_id
WHERE vp.consent_definition_id IS NOT NULL
  AND vp.signed_pdf_s3_key IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM visit_documents d WHERE d.file_id = vp.signed_pdf_s3_key
  );
