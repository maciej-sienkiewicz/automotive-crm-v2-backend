-- ═══════════════════════════════════════════════════════════════════════════════
-- Numeracja dokumentów finansowych: licznik zamiast COUNT + 1.
--
-- Numer PAR/{rok}/{n} był liczony jako „liczba nieusuniętych dokumentów w roku + 1".
-- Po usunięciu dokumentu następny dostawał numer już wydany, a dwa równoległe
-- wystawienia dostawały ten sam numer. Licznik rośnie tylko w górę; aplikacja
-- zwiększa go upsertem pod blokadą wiersza. Pierwsze użycie serii startuje od
-- najwyższego numeru zapisanego na dokumentach (łącznie z usuniętymi).
--
-- Bez indeksu unikalnego na financial_documents.document_number: dane sprzed tej
-- migracji mogą już zawierać powtórzone numery, a indeks nie dałby się założyć.
-- Duplikaty można znaleźć zapytaniem:
--   SELECT studio_id, document_number, count(*) FROM financial_documents
--   GROUP BY 1, 2 HAVING count(*) > 1;
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS financial_document_number_sequences (
    studio_id  UUID        NOT NULL,
    prefix     VARCHAR(10) NOT NULL,
    year       INTEGER     NOT NULL,
    last_value BIGINT      NOT NULL,
    PRIMARY KEY (studio_id, prefix, year)
);
