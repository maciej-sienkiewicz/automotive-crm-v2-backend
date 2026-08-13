-- Moduł faktur przychodowych KSeF.
--
-- ksef_revenue_invoices: jeden ledger dokumentów przychodowych — zarówno faktury
-- wystawione w CRM i wysłane do KSeF (source=CRM), jak i faktury sprzedażowe
-- pobrane z KSeF, a wystawione poza CRM (source=EXTERNAL, pull SUBJECT1).
-- Klucz biznesowy: ksef_number (unikalny per studio, gdy nadany) — faktura CRM
-- wracająca pullem jest dopasowywana po tym numerze (idempotentny upsert).
--
-- Kwoty w groszach (BIGINT, 1/100 PLN) — spójnie z modułem finance, nigdy float.
--
-- CREATE TABLE IF NOT EXISTS: migracja przechodzi zarówno na czystej bazie
-- produkcyjnej (flyway + ddl-auto=validate), jak i na dev (ddl-auto=update).

CREATE TABLE IF NOT EXISTS ksef_revenue_invoices (
    id                      UUID PRIMARY KEY,
    studio_id               UUID NOT NULL,

    -- CRM: wystawiona w CRM i wysyłana do KSeF | EXTERNAL: pobrana z KSeF (wystawiona poza CRM)
    source                  VARCHAR(10) NOT NULL DEFAULT 'CRM',

    -- Cykl życia wysyłki (tylko source=CRM):
    --   PENDING       – utworzona, wysyłka jeszcze nie podjęta
    --   SENDING       – trwa wysyłka (sesja otwarta)
    --   SUBMITTED     – przyjęta do sesji KSeF, oczekuje na numer KSeF
    --   ACCEPTED      – nadany numer KSeF (faktura prawnie wystawiona w KSeF)
    --   REJECTED      – odrzucona przez walidację KSeF (błąd trwały — wymaga poprawy danych)
    --   QUEUED_RETRY  – błąd łączności / niedostępność KSeF; tryb offline24, dosyłka schedulerem
    -- Dla source=EXTERNAL zawsze ACCEPTED (istnieje w KSeF z definicji).
    ksef_status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    invoice_number          VARCHAR(100) NOT NULL,
    ksef_number             VARCHAR(100),
    -- Numery referencyjne sesji interaktywnej (diagnostyka + dokończenie pollingu po restarcie)
    session_reference       VARCHAR(100),
    invoice_reference       VARCHAR(100),

    invoice_type            VARCHAR(10) NOT NULL DEFAULT 'VAT',      -- VAT | KOR
    original_invoice_id     UUID,                                    -- korekta → faktura pierwotna (nasze id)
    original_ksef_number    VARCHAR(100),                            -- korekta → numer KSeF faktury pierwotnej
    correction_reason       VARCHAR(500),

    issue_date              DATE NOT NULL,
    sale_date               DATE,

    -- Sprzedawca: snapshot danych studia z chwili wystawienia (audytowalność)
    seller_nip              VARCHAR(20),
    seller_name             VARCHAR(500),
    seller_address_line1    VARCHAR(500),
    seller_address_line2    VARCHAR(500),
    seller_country_code     VARCHAR(2) DEFAULT 'PL',
    seller_bank_account     VARCHAR(40),

    -- Nabywca: B2B (NIP) lub B2C (buyer_nip IS NULL → BrakID=1 w FA(3))
    buyer_nip               VARCHAR(20),
    buyer_name              VARCHAR(500),
    buyer_address_line1     VARCHAR(500),
    buyer_address_line2     VARCHAR(500),
    buyer_country_code      VARCHAR(2) DEFAULT 'PL',
    buyer_email             VARCHAR(255),

    total_net               BIGINT NOT NULL DEFAULT 0,
    total_vat               BIGINT NOT NULL DEFAULT 0,
    total_gross             BIGINT NOT NULL DEFAULT 0,
    currency                VARCHAR(3) NOT NULL DEFAULT 'PLN',

    payment_form            VARCHAR(20),                             -- PaymentForm.name
    payment_status          VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PAID | PENDING
    payment_due_date        DATE,
    paid_at                 TIMESTAMP WITH TIME ZONE,

    -- Wykrywanie podwójnego fakturowania (CRM vs EXTERNAL za tę samą transakcję):
    --   NONE | SUSPECTED | CONFIRMED_DUPLICATE (wyłączona ze statystyk) | DISMISSED
    duplicate_status        VARCHAR(20) NOT NULL DEFAULT 'NONE',
    duplicate_of_id         UUID,                                    -- druga faktura z podejrzanej pary

    -- Wygenerowany XML FA(3)/FA_KOR oraz UPO (XML) po przyjęciu przez KSeF
    invoice_xml             TEXT,
    upo_xml                 TEXT,
    invoice_hash            VARCHAR(100),                            -- SHA-256 (Base64) — link weryfikacyjny/QR

    send_attempts           INTEGER NOT NULL DEFAULT 0,
    last_send_error         VARCHAR(2000),
    first_queued_at         TIMESTAMP WITH TIME ZONE,                -- start okna offline24
    sent_at                 TIMESTAMP WITH TIME ZONE,
    accepted_at             TIMESTAMP WITH TIME ZONE,

    -- Kontekst CRM (opcjonalny)
    visit_id                UUID,
    customer_id             UUID,
    description             TEXT,
    note                    TEXT,

    created_by              UUID,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Numer KSeF jest globalnie unikalny w MF; unikalność per studio wystarcza i
-- umożliwia multi-tenancy. Partial index — NULL dla faktur jeszcze nie przyjętych.
CREATE UNIQUE INDEX IF NOT EXISTS idx_ksef_rev_studio_ksef_number
    ON ksef_revenue_invoices(studio_id, ksef_number) WHERE ksef_number IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ksef_rev_studio            ON ksef_revenue_invoices(studio_id);
CREATE INDEX IF NOT EXISTS idx_ksef_rev_studio_status     ON ksef_revenue_invoices(studio_id, ksef_status);
CREATE INDEX IF NOT EXISTS idx_ksef_rev_studio_source     ON ksef_revenue_invoices(studio_id, source);
CREATE INDEX IF NOT EXISTS idx_ksef_rev_studio_issue_date ON ksef_revenue_invoices(studio_id, issue_date);
CREATE INDEX IF NOT EXISTS idx_ksef_rev_retry             ON ksef_revenue_invoices(ksef_status)
    WHERE ksef_status IN ('QUEUED_RETRY', 'SUBMITTED', 'SENDING');
CREATE INDEX IF NOT EXISTS idx_ksef_rev_duplicates        ON ksef_revenue_invoices(studio_id, duplicate_status)
    WHERE duplicate_status <> 'NONE';

CREATE TABLE IF NOT EXISTS ksef_revenue_invoice_items (
    id                UUID PRIMARY KEY,
    invoice_id        UUID NOT NULL,
    line_number       INTEGER NOT NULL,
    name              VARCHAR(500) NOT NULL,
    unit              VARCHAR(20),
    quantity          NUMERIC(12, 3) NOT NULL DEFAULT 1,
    unit_price_net    BIGINT NOT NULL,                 -- grosze
    net_value         BIGINT NOT NULL,                 -- grosze
    vat_value         BIGINT NOT NULL DEFAULT 0,       -- grosze
    gross_value       BIGINT NOT NULL,                 -- grosze
    -- Stawka VAT jako tekst FA(3): 23 | 8 | 5 | 0 | zw
    vat_rate          VARCHAR(5) NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ksef_rev_items_invoice ON ksef_revenue_invoice_items(invoice_id);

-- Kursor delta-syncu przychodów (pull SUBJECT1) — analogicznie do last_expense_sync
ALTER TABLE ksef_sync_cursor ADD COLUMN IF NOT EXISTS last_revenue_sync TIMESTAMP WITH TIME ZONE;
