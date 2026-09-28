-- ═══════════════════════════════════════════════════════════════════════════════
-- „Faktury wystawia księgowość": tryb studia, w którym CRM nie wystawia faktur.
--
-- Zgłoszenie biznesu: studio wybiera przy wydaniu „Faktura", nie wysyła jej do KSeF,
-- a fakturę wystawia potem księgowość. CRM miał wtedy własną fakturę (liczoną do
-- przychodu) i dostawał z KSeF drugą, księgowości — ta sama sprzedaż dwa razy,
-- a pilnowanie tego spadało na ludzi. Automatyczne łączenie obu faktur biznes
-- odrzucił świadomie: przy tej samej kwocie w tym samym tygodniu łatwo połączyć
-- nie te dokumenty.
--
-- Rozwiązanie bez łączenia: przy włączonej fladze wybór „Faktura" nie tworzy żadnej
-- faktury w CRM. Powstaje dokument finansowy z formą płatności i ruchem w kasie,
-- oznaczony invoiced_externally = TRUE — przychód niesie wyłącznie faktura
-- księgowości pobrana z KSeF, więc dokument nie wchodzi do sum przychodu. Obok
-- powstaje zgłoszenie w external_invoice_requests: lista „Do zafakturowania",
-- na której człowiek ręcznie odhacza „Faktura wystawiona".
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS invoices_issued_externally BOOLEAN NOT NULL DEFAULT FALSE;

-- Dokument, którego przychód niesie faktura wystawiona poza CRM. Sam dokument zostaje
-- zapisem płatności: forma płatności, status, termin, kasa, raport form płatności.
ALTER TABLE financial_documents
    ADD COLUMN IF NOT EXISTS invoiced_externally BOOLEAN NOT NULL DEFAULT FALSE;

-- Co księgowość ma wystawić. Kwoty są kopiowane z dokumentu w chwili zgłoszenia:
-- korekta zgłasza kwoty ujemne faktury, którą koryguje, a nie kwoty swojego storna.
--
-- kind:   INVOICE             — faktura za sprzedaż (dokument invoiced_externally)
--         INVOICE_TO_RECEIPT  — faktura do paragonu; paragon zostaje przychodem
--         CORRECTION          — korekta faktury już wystawionej przez księgowość
-- status: PENDING   — czeka na księgowość
--         ISSUED    — człowiek odhaczył „Faktura wystawiona" (numer opcjonalny)
--         WITHDRAWN — nieaktualne, zanim księgowość je wystawiła (poprawka rozliczenia)
CREATE TABLE IF NOT EXISTS external_invoice_requests (
    id                      UUID         PRIMARY KEY,
    studio_id               UUID         NOT NULL,
    visit_id                UUID,
    financial_document_id   UUID         NOT NULL,
    kind                    VARCHAR(30)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    corrects_request_id     UUID,
    buyer_nip               VARCHAR(20),
    buyer_name              VARCHAR(255),
    buyer_address_line1     VARCHAR(255),
    buyer_address_line2     VARCHAR(255),
    buyer_email             VARCHAR(255),
    total_net               BIGINT       NOT NULL,
    total_vat               BIGINT       NOT NULL,
    total_gross             BIGINT       NOT NULL,
    external_invoice_number VARCHAR(100),
    issued_at               TIMESTAMPTZ,
    issued_by               UUID,
    issued_by_name          VARCHAR(255),
    withdrawn_at            TIMESTAMPTZ,
    settlement_correction_id UUID,
    created_by              UUID         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ext_inv_req_studio_status
    ON external_invoice_requests (studio_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ext_inv_req_document
    ON external_invoice_requests (financial_document_id);
CREATE INDEX IF NOT EXISTS idx_ext_inv_req_visit
    ON external_invoice_requests (visit_id);
