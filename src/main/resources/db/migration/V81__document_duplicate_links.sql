-- Powiązania zdublowanych dokumentów: jedna sprzedaż (albo jeden zakup)
-- udokumentowana dwa razy — raz w CRM-ie, raz w programie księgowym.
--
-- Rejestry zostają rozdzielone (financial_documents, ksef_revenue_invoices,
-- ksef_invoices), więc informacja o parze nie ma gdzie zamieszkać w żadnym
-- z nich — stąd osobna tabela. Sam skutek dla statystyk zapisujemy jednak
-- w mechanizmie, który już istnieje i który każde zapytanie sumujące już
-- respektuje: excluded_at na dokumencie przegrywającym. Dzięki temu detektor
-- nie wymaga zmiany ani jednego zapytania raportowego.
--
-- winner / loser: wygrywa dokument o wyższej mocy dowodowej (faktura
-- w KSeF bije dokument spoza KSeF). Przegrywający przestaje liczyć się
-- do sum, ale zostaje w bazie i na listach.
--
-- Indeksy częściowe: para „aktywna" może być tylko jedna na dokument, ale
-- odrzucona (dismissed) zostaje w tabeli jako czarna lista — tej samej pary
-- nie proponujemy drugi raz.

CREATE TABLE IF NOT EXISTS document_duplicate_links (
    id            UUID        PRIMARY KEY,
    studio_id     UUID        NOT NULL,

    winner_kind   VARCHAR(24) NOT NULL,
    winner_id     UUID        NOT NULL,
    loser_kind    VARCHAR(24) NOT NULL,
    loser_id      UUID        NOT NULL,

    -- Kwota i data, po których para się dopasowała — do wyjaśnienia decyzji.
    total_gross   BIGINT      NOT NULL,
    issue_date    DATE        NOT NULL,

    detected_at   TIMESTAMPTZ NOT NULL,
    dismissed_at  TIMESTAMPTZ,
    dismissed_by  UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ddl_loser_active
    ON document_duplicate_links (studio_id, loser_kind, loser_id)
    WHERE dismissed_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ddl_winner_active
    ON document_duplicate_links (studio_id, winner_kind, winner_id)
    WHERE dismissed_at IS NULL;

-- Czarna lista: raz rozstrzygniętej pary nie proponujemy ponownie.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ddl_pair
    ON document_duplicate_links (studio_id, winner_kind, winner_id, loser_kind, loser_id);

CREATE INDEX IF NOT EXISTS ix_ddl_studio_detected
    ON document_duplicate_links (studio_id, detected_at DESC);
