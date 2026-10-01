-- ═══════════════════════════════════════════════════════════════════════════════
-- Wnioski urlopowe (projekt PRJ/2026/09/01, §2.2–2.4).
--
-- Wniosek to dokument ze statusem, dwoma podpisami i historią. Urlop jako FAKT zostaje
-- w employee_leaves — to z niego czytają kalendarz zespołu, lista obecności i usuwanie
-- pracownika. Zatwierdzenie wniosku dopisuje tam wiersz z odnośnikiem leave_request_id
-- w tej samej transakcji, odwołanie go usuwa.
--
-- Poza tym wydaniem (świadomie bez kolumn): wniosek w imieniu pracownika, podpis zdalny
-- na tablecie albo przez SMS (employee_signature_request_id, status
-- AWAITING_EMPLOYEE_SIGNATURE) i wymiar urlopu. Dojdą razem z funkcją, która ich używa.
--
-- Bez CHECK-ów na wartościach enumów (NoEnumCheckConstraintsTest): nowa wartość enuma
-- nie może wymagać migracji, żeby aplikacja w ogóle wstała.
-- ═══════════════════════════════════════════════════════════════════════════════

-- Pozostałość starego modułu kadr (commit 09085f35, wycofany w 7936155f): Hibernate
-- założył wtedy sam, poza Flyway, tabelę leave_requests o innym schemacie (status,
-- business_days_count, reviewed_by…). Na bazach, które ją mają, „CREATE TABLE IF NOT
-- EXISTS" niżej po cichu ją pomijał, a migracja padała dopiero na indeksie nowej
-- kolumny — tak wyłożył się start produkcji 01.10.2026. Starej tabeli nie kasujemy
-- (to dane, nikt ich świadomie nie porzucił) ani nie przenosimy (wnioski bez podpisów
-- i dokumentów nie są wnioskami w nowym znaczeniu): odsuwamy ją pod nazwę
-- leave_requests_legacy razem z kluczem głównym, bo nazwa leave_requests_pkey jest
-- globalna w schemacie i zderzyłaby się z kluczem nowej tabeli. Rozpoznajemy ją po
-- braku kolumny, której stary schemat nie miał, więc ponowne uruchomienie na bazie
-- z nową tabelą niczego nie rusza.
DO $$
BEGIN
    IF to_regclass('leave_requests') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM information_schema.columns
           WHERE table_schema = current_schema()
             AND table_name = 'leave_requests'
             AND column_name = 'document_sha256'
       ) THEN
        ALTER TABLE leave_requests RENAME TO leave_requests_legacy;
        IF EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'leave_requests_pkey'
              AND conrelid = 'leave_requests_legacy'::regclass
        ) THEN
            -- Zmiana nazwy ograniczenia zmienia też nazwę indeksu, który je realizuje.
            ALTER TABLE leave_requests_legacy RENAME CONSTRAINT leave_requests_pkey TO leave_requests_legacy_pkey;
        END IF;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS leave_requests (
    id                           UUID PRIMARY KEY,
    studio_id                    UUID          NOT NULL,
    -- „WU/2026/0012", unikalny w obrębie studia. Nadawany przy utworzeniu szkicu, bo
    -- jest wydrukowany na dokumencie, który pracownik podpisuje.
    number                       VARCHAR(32)   NOT NULL,
    employee_id                  UUID          NOT NULL,
    -- Konto pracownika z chwili złożenia: zakaz rozpatrzenia własnego wniosku
    -- i adresat powiadomienia o decyzji.
    employee_user_id             UUID,
    -- Istniejący LeaveType bez SICK. „Na żądanie" to flaga przy ANNUAL, a nie nowy
    -- typ — konsumenci employee_leaves nie wymagają przez to żadnej zmiany.
    leave_type                   VARCHAR(30)   NOT NULL,
    on_demand                    BOOLEAN       NOT NULL DEFAULT FALSE,
    start_date                   DATE          NOT NULL,
    end_date                     DATE          NOT NULL,
    -- Dni robocze policzone przy utworzeniu i zamrożone: są wydrukowane na dokumencie.
    working_days                 INTEGER       NOT NULL,
    reason                       VARCHAR(1000),
    substitute_employee_id       UUID,
    status                       VARCHAR(40)   NOT NULL,
    origin                       VARCHAR(20)   NOT NULL DEFAULT 'SELF_SERVICE',
    created_by                   UUID          NOT NULL,
    created_by_name              VARCHAR(200),
    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- H1: PDF bez podpisów, pokazany pracownikowi do podpisu.
    document_s3_key              VARCHAR(500)  NOT NULL,
    document_sha256              VARCHAR(64)   NOT NULL,

    -- H2: dokument po podpisie pracownika. Osobny obiekt, H1 zostaje nietknięty.
    employee_signed_at           TIMESTAMPTZ,
    employee_signature_method    VARCHAR(20),
    employee_signed_pdf_s3_key   VARCHAR(500),
    employee_signed_sha256       VARCHAR(64),
    employee_signer_ip           VARCHAR(45),
    employee_signer_user_agent   VARCHAR(500),

    -- Decyzja: kto, na jakiej podstawie i z jaką rolą — migawka z chwili decyzji, bo
    -- rola może się później zmienić, a podpisany dokument nie.
    decided_by                   UUID,
    decided_by_name              VARCHAR(200),
    decided_by_basis             VARCHAR(20),
    decided_by_role_name         VARCHAR(200),
    decided_at                   TIMESTAMPTZ,
    decision_note                VARCHAR(1000),
    decision_signature_method    VARCHAR(20),
    -- Skrót dokumentu, który widział rozpatrujący (musi być równy H2).
    decision_input_sha256        VARCHAR(64),
    decision_signer_ip           VARCHAR(45),
    decision_signer_user_agent   VARCHAR(500),

    -- H3: dokument z oboma podpisami i kartą podpisów. Nigdy nie jest nadpisywany.
    final_pdf_s3_key             VARCHAR(500),
    final_sha256                 VARCHAR(64),

    -- Wpis w employee_leaves utworzony przy zatwierdzeniu.
    employee_leave_id            UUID,

    -- Wycofanie przez pracownika albo odwołanie zatwierdzonego urlopu.
    cancelled_at                 TIMESTAMPTZ,
    cancelled_by                 UUID,
    cancel_reason                VARCHAR(1000),

    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- Blokada optymistyczna jako druga linia obrony; pierwszą jest warunkowy UPDATE
    -- „… WHERE status = 'PENDING'".
    version                      BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ck_leave_requests_range CHECK (end_date >= start_date),
    CONSTRAINT uq_leave_requests_number UNIQUE (studio_id, number)
);

-- Kolejka rozpatrujących (status + najnowsze) i historia/kolizje jednego pracownika.
CREATE INDEX IF NOT EXISTS idx_leave_requests_queue    ON leave_requests (studio_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_leave_requests_employee ON leave_requests (studio_id, employee_id, start_date);
CREATE UNIQUE INDEX IF NOT EXISTS uq_leave_requests_employee_leave
    ON leave_requests (employee_leave_id) WHERE employee_leave_id IS NOT NULL;

-- Numeracja WU/{rok}/{nr} per studio. Licznik rośnie tylko w górę i jest zwiększany
-- upsertem pod blokadą wiersza, więc dwa równoległe wnioski nie dostaną tego samego
-- numeru, a numer raz wydany nie wraca (jak financial_document_number_sequences, V165).
CREATE TABLE IF NOT EXISTS leave_request_counters (
    studio_id   UUID     NOT NULL,
    year        INTEGER  NOT NULL,
    last_value  BIGINT   NOT NULL,
    PRIMARY KEY (studio_id, year)
);

-- employee_leaves powstało przed Flyway (PRE_FLYWAY_TABLES): tylko ALTER, IF NOT EXISTS.
-- Jeden wniosek = najwyżej jeden wpis urlopu; indeks unikalny pilnuje tego w bazie.
ALTER TABLE employee_leaves ADD COLUMN IF NOT EXISTS leave_request_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_employee_leaves_request ON employee_leaves (leave_request_id);
