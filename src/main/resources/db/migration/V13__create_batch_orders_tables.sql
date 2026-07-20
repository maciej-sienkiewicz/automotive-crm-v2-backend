-- Migration: Create batch orders management tables
-- Description: Batch contractors and order entries for B2B monthly settlement
-- Date: 2026-07-20

CREATE TABLE IF NOT EXISTS batch_contractors (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    tax_id VARCHAR(50),
    address TEXT,
    contact_person_name VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(50),
    notes TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_batch_contractors_studio_id
    ON batch_contractors(studio_id);

CREATE INDEX IF NOT EXISTS idx_batch_contractors_studio_active
    ON batch_contractors(studio_id, is_active);

CREATE TABLE IF NOT EXISTS batch_order_entries (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    contractor_id UUID NOT NULL,
    service_date DATE NOT NULL,
    vehicle_make VARCHAR(100),
    vehicle_model VARCHAR(100),
    vehicle_license_plate VARCHAR(20),
    net_amount_cents BIGINT NOT NULL DEFAULT 0,
    gross_amount_cents BIGINT NOT NULL DEFAULT 0,
    vat_rate INTEGER NOT NULL DEFAULT 23,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_batch_entry_contractor
        FOREIGN KEY (contractor_id)
        REFERENCES batch_contractors(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_batch_order_entries_studio_id
    ON batch_order_entries(studio_id);

CREATE INDEX IF NOT EXISTS idx_batch_order_entries_contractor
    ON batch_order_entries(studio_id, contractor_id);

CREATE INDEX IF NOT EXISTS idx_batch_order_entries_service_date
    ON batch_order_entries(studio_id, service_date);

CREATE TABLE IF NOT EXISTS batch_order_entry_services (
    entry_id UUID NOT NULL,
    service_name VARCHAR(500) NOT NULL,
    sort_order INTEGER NOT NULL,

    CONSTRAINT fk_entry_service_entry
        FOREIGN KEY (entry_id)
        REFERENCES batch_order_entries(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_batch_entry_services_entry_id
    ON batch_order_entry_services(entry_id);

COMMENT ON TABLE batch_contractors IS 'B2B contractors for batch monthly settlements';
COMMENT ON TABLE batch_order_entries IS 'Individual service entries within a batch contractor settlement';
COMMENT ON TABLE batch_order_entry_services IS 'List of services performed for a single batch order entry';
COMMENT ON COLUMN batch_order_entries.net_amount_cents IS 'Net price in cents (grosze)';
COMMENT ON COLUMN batch_order_entries.gross_amount_cents IS 'Gross price in cents (grosze) including VAT';
