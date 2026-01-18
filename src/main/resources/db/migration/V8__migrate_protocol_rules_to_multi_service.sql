-- Migration: Update protocol_rules to support multiple services
-- Description: Changes protocol_rules from single service_id to many-to-many relationship with services
-- Date: 2026-01-18

-- Step 1: Create new table for protocol_rule_services (many-to-many relationship)
CREATE TABLE IF NOT EXISTS protocol_rule_services (
    protocol_rule_id UUID NOT NULL,
    service_id UUID NOT NULL,

    -- Foreign key to protocol_rules
    CONSTRAINT fk_protocol_rule_services_rule
        FOREIGN KEY (protocol_rule_id)
        REFERENCES protocol_rules(id)
        ON DELETE CASCADE,

    -- Primary key: combination of rule and service
    PRIMARY KEY (protocol_rule_id, service_id)
);

-- Create indexes for efficient lookups
CREATE INDEX IF NOT EXISTS idx_protocol_rule_services_rule_id
    ON protocol_rule_services(protocol_rule_id);

CREATE INDEX IF NOT EXISTS idx_protocol_rule_services_service_id
    ON protocol_rule_services(service_id);

-- Step 2: Migrate existing data from service_id column to new table
-- Only migrate rows where service_id is not NULL
INSERT INTO protocol_rule_services (protocol_rule_id, service_id)
SELECT id, service_id
FROM protocol_rules
WHERE service_id IS NOT NULL;

-- Step 3: Drop the old constraint that required service_id for SERVICE_SPECIFIC rules
ALTER TABLE protocol_rules
    DROP CONSTRAINT IF EXISTS chk_service_specific_has_service;

-- Step 4: Drop the old index on service_id
DROP INDEX IF EXISTS idx_protocol_rules_service;

-- Step 5: Drop the service_id column (no longer needed)
ALTER TABLE protocol_rules
    DROP COLUMN IF EXISTS service_id;

-- Comments for documentation
COMMENT ON TABLE protocol_rule_services IS 'Many-to-many relationship between protocol rules and services (SERVICE_SPECIFIC trigger type)';
COMMENT ON COLUMN protocol_rule_services.protocol_rule_id IS 'Reference to the protocol rule';
COMMENT ON COLUMN protocol_rule_services.service_id IS 'Reference to the service that triggers this protocol rule';
