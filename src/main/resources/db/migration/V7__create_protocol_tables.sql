-- Migration: Create protocol management tables
-- Description: Multi-tenant protocol management system with fillable PDF templates
-- Date: 2026-01-18

-- Table: protocol_templates
-- Represents fillable PDF templates (AcroForms) for visit protocols
CREATE TABLE IF NOT EXISTS protocol_templates (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    s3_key VARCHAR(500) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_protocol_templates_studio
    ON protocol_templates(studio_id);

CREATE INDEX IF NOT EXISTS idx_protocol_templates_studio_active
    ON protocol_templates(studio_id, is_active);

-- Table: protocol_field_mappings
-- Maps PDF form fields to CRM data sources
CREATE TABLE IF NOT EXISTS protocol_field_mappings (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    template_id UUID NOT NULL,
    pdf_field_name VARCHAR(200) NOT NULL,
    crm_data_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Foreign key to protocol_templates
    CONSTRAINT fk_mapping_template
        FOREIGN KEY (template_id)
        REFERENCES protocol_templates(id)
        ON DELETE CASCADE,

    -- Unique constraint: one CRM key per PDF field per template
    CONSTRAINT uq_mapping_template_field UNIQUE (template_id, pdf_field_name)
);

CREATE INDEX IF NOT EXISTS idx_protocol_mappings_template
    ON protocol_field_mappings(studio_id, template_id);

-- Table: protocol_rules
-- Defines when a protocol template should be required for a visit
CREATE TABLE IF NOT EXISTS protocol_rules (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    template_id UUID NOT NULL,
    trigger_type VARCHAR(50) NOT NULL,  -- GLOBAL_ALWAYS or SERVICE_SPECIFIC
    stage VARCHAR(50) NOT NULL,         -- CHECK_IN or CHECK_OUT
    service_id UUID,                    -- Required for SERVICE_SPECIFIC rules
    is_mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Foreign key to protocol_templates
    CONSTRAINT fk_rule_template
        FOREIGN KEY (template_id)
        REFERENCES protocol_templates(id)
        ON DELETE CASCADE,

    -- Constraint: SERVICE_SPECIFIC rules must have service_id
    CONSTRAINT chk_service_specific_has_service
        CHECK (
            (trigger_type = 'SERVICE_SPECIFIC' AND service_id IS NOT NULL) OR
            (trigger_type = 'GLOBAL_ALWAYS' AND service_id IS NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_protocol_rules_studio_stage
    ON protocol_rules(studio_id, stage);

CREATE INDEX IF NOT EXISTS idx_protocol_rules_template
    ON protocol_rules(template_id);

CREATE INDEX IF NOT EXISTS idx_protocol_rules_service
    ON protocol_rules(service_id);

CREATE INDEX IF NOT EXISTS idx_protocol_rules_display_order
    ON protocol_rules(studio_id, display_order);

-- Table: visit_protocols
-- Represents an instance of a protocol for a specific visit
CREATE TABLE IF NOT EXISTS visit_protocols (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    visit_id UUID NOT NULL,
    template_id UUID NOT NULL,
    stage VARCHAR(50) NOT NULL,         -- CHECK_IN or CHECK_OUT
    is_mandatory BOOLEAN NOT NULL,
    status VARCHAR(50) NOT NULL,        -- PENDING, READY_FOR_SIGNATURE, SIGNED
    filled_pdf_s3_key VARCHAR(500),     -- S3 key for filled PDF (before signature)
    signed_pdf_s3_key VARCHAR(500),     -- S3 key for signed and flattened PDF
    signed_at TIMESTAMP,
    signed_by VARCHAR(200),             -- Name of person who signed
    signature_image_s3_key VARCHAR(500), -- S3 key for signature image
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Foreign key to protocol_templates
    CONSTRAINT fk_visit_protocol_template
        FOREIGN KEY (template_id)
        REFERENCES protocol_templates(id)
        ON DELETE RESTRICT,

    -- Constraint: SIGNED status requires signature data
    CONSTRAINT chk_signed_requires_data
        CHECK (
            (status = 'SIGNED' AND signed_pdf_s3_key IS NOT NULL AND signed_at IS NOT NULL AND signed_by IS NOT NULL) OR
            (status != 'SIGNED')
        )
);

CREATE INDEX IF NOT EXISTS idx_visit_protocols_visit
    ON visit_protocols(studio_id, visit_id);

CREATE INDEX IF NOT EXISTS idx_visit_protocols_template
    ON visit_protocols(template_id);

CREATE INDEX IF NOT EXISTS idx_visit_protocols_status
    ON visit_protocols(visit_id, status);

CREATE INDEX IF NOT EXISTS idx_visit_protocols_stage
    ON visit_protocols(visit_id, stage);

-- Comments for documentation
COMMENT ON TABLE protocol_templates IS 'Fillable PDF templates (AcroForms) for visit protocols';
COMMENT ON TABLE protocol_field_mappings IS 'Maps PDF form fields to CRM data sources';
COMMENT ON TABLE protocol_rules IS 'Defines when protocols are required (GLOBAL or SERVICE_SPECIFIC)';
COMMENT ON TABLE visit_protocols IS 'Protocol instances for specific visits with signature tracking';

COMMENT ON COLUMN protocol_rules.trigger_type IS 'GLOBAL_ALWAYS: required for all visits; SERVICE_SPECIFIC: required only if visit includes specific service';
COMMENT ON COLUMN protocol_rules.stage IS 'CHECK_IN: required at arrival; CHECK_OUT: required at handover';
COMMENT ON COLUMN visit_protocols.status IS 'PENDING: generated; READY_FOR_SIGNATURE: PDF filled; SIGNED: signature applied and PDF flattened (immutable)';
