-- Migration: Create consent management tables
-- Description: Multi-tenant consent management system with versioned templates
-- Date: 2026-01-14

-- Table: consent_definitions
-- Represents types of consent documents (e.g., "RODO", "Marketing")
CREATE TABLE IF NOT EXISTS consent_definitions (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    slug VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Indexes for multi-tenancy and performance
    CONSTRAINT uq_consent_defs_studio_slug UNIQUE (studio_id, slug)
);

CREATE INDEX IF NOT EXISTS idx_consent_defs_studio_active
    ON consent_definitions(studio_id, is_active);

-- Table: consent_templates
-- Represents specific versions of consent document PDFs
CREATE TABLE IF NOT EXISTS consent_templates (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    definition_id UUID NOT NULL,
    version INTEGER NOT NULL,
    s3_key VARCHAR(500) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    requires_resign BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Foreign key to consent_definitions
    CONSTRAINT fk_template_definition
        FOREIGN KEY (definition_id)
        REFERENCES consent_definitions(id)
        ON DELETE CASCADE,

    -- Unique version per definition
    CONSTRAINT uq_template_def_version UNIQUE (definition_id, version)
);

CREATE INDEX IF NOT EXISTS idx_consent_templates_studio_def
    ON consent_templates(studio_id, definition_id);

CREATE INDEX IF NOT EXISTS idx_consent_templates_active
    ON consent_templates(definition_id, is_active);

-- Table: customer_consents
-- Immutable, append-only record of customer consent signatures
CREATE TABLE IF NOT EXISTS customer_consents (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    template_id UUID NOT NULL,
    signed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    witnessed_by UUID NOT NULL,

    -- Foreign key to consent_templates
    CONSTRAINT fk_consent_template
        FOREIGN KEY (template_id)
        REFERENCES consent_templates(id)
        ON DELETE RESTRICT

    -- Note: No foreign key to customers table to avoid circular dependencies
    -- The application layer enforces referential integrity
);

CREATE INDEX IF NOT EXISTS idx_customer_consents_customer
    ON customer_consents(studio_id, customer_id);

CREATE INDEX IF NOT EXISTS idx_customer_consents_template
    ON customer_consents(template_id);

CREATE INDEX IF NOT EXISTS idx_customer_consents_signed_at
    ON customer_consents(signed_at);

-- Comments for documentation
COMMENT ON TABLE consent_definitions IS 'Types of consent documents that customers must sign';
COMMENT ON TABLE consent_templates IS 'Versioned PDF templates for each consent definition';
COMMENT ON TABLE customer_consents IS 'Immutable audit log of customer consent signatures';

COMMENT ON COLUMN consent_templates.requires_resign IS 'If true, customers must re-sign even if they signed older versions';
COMMENT ON COLUMN customer_consents.witnessed_by IS 'Employee/user who witnessed/recorded the signature';
