-- ═══════════════════════════════════════════════════════════════════════════════
-- Baseline tabel kasy: cash_registers i cash_operations.
--
-- Obie tabele powstały wyłącznie przez Hibernate (ddl-auto=update) i nie miały
-- żadnego CREATE TABLE w repozytorium — każda nowa kolumna w kasie była zakładem,
-- że baza produkcyjna wygląda tak, jak myślimy. IF NOT EXISTS: na bazie, gdzie
-- tabele już są, migracja niczego nie zmienia; na czystej bazie (flyway +
-- ddl-auto=validate) zakłada je w kształcie zgodnym z encjami. Wzór: V46.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS cash_registers (
    id          UUID                     PRIMARY KEY,
    studio_id   UUID                     NOT NULL,
    balance     BIGINT                   NOT NULL,
    currency    VARCHAR(3)               NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_cash_registers_studio_id UNIQUE (studio_id)
);

CREATE INDEX IF NOT EXISTS idx_cash_registers_studio_id ON cash_registers(studio_id);

CREATE TABLE IF NOT EXISTS cash_operations (
    id                     UUID                     PRIMARY KEY,
    studio_id              UUID                     NOT NULL,
    cash_register_id       UUID                     NOT NULL,
    amount                 BIGINT                   NOT NULL,
    balance_before         BIGINT                   NOT NULL,
    balance_after          BIGINT                   NOT NULL,
    operation_type         VARCHAR(30)              NOT NULL,
    comment                VARCHAR(500),
    financial_document_id  UUID,
    created_by             UUID                     NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cash_ops_studio_id   ON cash_operations(studio_id);
CREATE INDEX IF NOT EXISTS idx_cash_ops_register_id ON cash_operations(cash_register_id);
CREATE INDEX IF NOT EXISTS idx_cash_ops_created_at  ON cash_operations(studio_id, created_at);
CREATE INDEX IF NOT EXISTS idx_cash_ops_document_id ON cash_operations(financial_document_id);
