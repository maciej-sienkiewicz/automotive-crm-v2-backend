-- Migration: Create visit management tables with immutable snapshots
-- Description: Visit module with vehicle/customer snapshots and granular service tracking
-- Date: 2026-01-16

-- Table: visits
-- Represents vehicle check-ins with immutable snapshots for audit and financial integrity
CREATE TABLE IF NOT EXISTS visits (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    visit_number VARCHAR(50) NOT NULL,
    customer_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    appointment_id UUID NOT NULL,

    -- Immutable vehicle snapshots - frozen at visit creation
    brand_snapshot VARCHAR(100) NOT NULL,
    model_snapshot VARCHAR(100) NOT NULL,
    license_plate_snapshot VARCHAR(20) NOT NULL,
    vin_snapshot VARCHAR(17),
    year_of_production_snapshot INTEGER NOT NULL,
    color_snapshot VARCHAR(50),
    engine_type_snapshot VARCHAR(20) NOT NULL,

    -- Visit status and dates
    status VARCHAR(50) NOT NULL,
    scheduled_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_date TIMESTAMP WITH TIME ZONE,

    -- Arrival details
    mileage_at_arrival BIGINT,
    fuel_level VARCHAR(20),
    keys_handed_over BOOLEAN NOT NULL DEFAULT FALSE,
    documents_handed_over BOOLEAN NOT NULL DEFAULT FALSE,
    is_very_dirty BOOLEAN NOT NULL DEFAULT FALSE,
    inspection_notes TEXT,
    technical_notes TEXT,

    -- Audit fields
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- Unique visit number per studio
    CONSTRAINT uq_visits_studio_visit_number UNIQUE (studio_id, visit_number)
);

-- Indexes for visits table
CREATE INDEX IF NOT EXISTS idx_visits_studio_id
    ON visits(studio_id);

CREATE INDEX IF NOT EXISTS idx_visits_studio_customer
    ON visits(studio_id, customer_id);

CREATE INDEX IF NOT EXISTS idx_visits_studio_vehicle
    ON visits(studio_id, vehicle_id);

CREATE INDEX IF NOT EXISTS idx_visits_studio_status
    ON visits(studio_id, status);

CREATE INDEX IF NOT EXISTS idx_visits_studio_scheduled
    ON visits(studio_id, scheduled_date);

CREATE INDEX IF NOT EXISTS idx_visits_appointment_id
    ON visits(appointment_id);

CREATE INDEX IF NOT EXISTS idx_visits_created_by
    ON visits(created_by);

CREATE INDEX IF NOT EXISTS idx_visits_updated_by
    ON visits(updated_by);

-- Table: visit_service_items
-- Individual services within a visit with granular status tracking
-- Prices are frozen at the moment of adding to visit
CREATE TABLE IF NOT EXISTS visit_service_items (
    id UUID PRIMARY KEY,
    visit_id UUID NOT NULL,
    service_id UUID NOT NULL,
    service_name VARCHAR(255) NOT NULL,

    -- Frozen pricing snapshot (amounts in cents)
    base_price_net BIGINT NOT NULL,
    vat_rate INTEGER NOT NULL,
    adjustment_type VARCHAR(50) NOT NULL,
    adjustment_value BIGINT NOT NULL,
    final_price_net BIGINT NOT NULL,
    final_price_gross BIGINT NOT NULL,

    -- Granular status tracking
    status VARCHAR(50) NOT NULL,

    -- Optional note
    custom_note TEXT,

    -- Creation timestamp (immutable once created)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- Foreign key to visits
    CONSTRAINT fk_service_item_visit
        FOREIGN KEY (visit_id)
        REFERENCES visits(id)
        ON DELETE CASCADE
);

-- Indexes for visit_service_items table
CREATE INDEX IF NOT EXISTS idx_visit_service_items_visit_id
    ON visit_service_items(visit_id);

CREATE INDEX IF NOT EXISTS idx_visit_service_items_service_id
    ON visit_service_items(service_id);

CREATE INDEX IF NOT EXISTS idx_visit_service_items_status
    ON visit_service_items(visit_id, status);

-- Comments for documentation
COMMENT ON TABLE visits IS 'Vehicle check-ins with immutable snapshots for audit and financial integrity';
COMMENT ON TABLE visit_service_items IS 'Services within a visit with frozen prices and granular status tracking';

COMMENT ON COLUMN visits.brand_snapshot IS 'Immutable brand snapshot at visit creation';
COMMENT ON COLUMN visits.model_snapshot IS 'Immutable model snapshot at visit creation';
COMMENT ON COLUMN visits.license_plate_snapshot IS 'Immutable license plate snapshot at visit creation';
COMMENT ON COLUMN visits.vin_snapshot IS 'Immutable VIN snapshot at visit creation';
COMMENT ON COLUMN visits.mileage_at_arrival IS 'Vehicle mileage in kilometers/miles at arrival';

COMMENT ON COLUMN visit_service_items.base_price_net IS 'Base net price in cents (grosze)';
COMMENT ON COLUMN visit_service_items.final_price_net IS 'Final net price in cents after adjustments';
COMMENT ON COLUMN visit_service_items.final_price_gross IS 'Final gross price in cents including VAT';
COMMENT ON COLUMN visit_service_items.status IS 'PENDING, APPROVED, IN_PROGRESS, COMPLETED, or REJECTED';

-- Table: visit_photos
-- Photo documentation for visits
CREATE TABLE IF NOT EXISTS visit_photos (
    id UUID PRIMARY KEY,
    visit_id UUID NOT NULL,
    photo_type VARCHAR(50) NOT NULL,
    file_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    description TEXT,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- Foreign key to visits
    CONSTRAINT fk_visit_photo_visit
        FOREIGN KEY (visit_id)
        REFERENCES visits(id)
        ON DELETE CASCADE
);

-- Indexes for visit_photos table
CREATE INDEX IF NOT EXISTS idx_visit_photos_visit_id
    ON visit_photos(visit_id);

CREATE INDEX IF NOT EXISTS idx_visit_photos_photo_type
    ON visit_photos(photo_type);

-- Table: photo_upload_sessions
-- Temporary sessions for mobile photo uploads
CREATE TABLE IF NOT EXISTS photo_upload_sessions (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    appointment_id UUID NOT NULL,
    token VARCHAR(500) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes for photo_upload_sessions table
CREATE INDEX IF NOT EXISTS idx_photo_sessions_studio_id
    ON photo_upload_sessions(studio_id);

CREATE INDEX IF NOT EXISTS idx_photo_sessions_appointment_id
    ON photo_upload_sessions(appointment_id);

CREATE INDEX IF NOT EXISTS idx_photo_sessions_expires_at
    ON photo_upload_sessions(expires_at);

-- Comments for new tables
COMMENT ON TABLE visit_photos IS 'Photo documentation for vehicle check-ins';
COMMENT ON TABLE photo_upload_sessions IS 'Temporary sessions for mobile photo uploads (JWT tokens)';

COMMENT ON COLUMN visits.fuel_level IS 'Fuel level at arrival: EMPTY, QUARTER, HALF, THREE_QUARTERS, FULL';
COMMENT ON COLUMN visits.is_very_dirty IS 'Indicates if vehicle was extremely dirty at arrival';
COMMENT ON COLUMN visits.inspection_notes IS 'Technical inspection notes from check-in';
