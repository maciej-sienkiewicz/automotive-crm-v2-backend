-- Add customer_id and name fields to visit_documents for enhanced document management
-- This allows fast historical lookups by customer and provides human-readable document titles

-- Add customer_id column (denormalized for fast customer history queries)
ALTER TABLE visit_documents
ADD COLUMN customer_id UUID;

-- Add name column for human-readable document titles
ALTER TABLE visit_documents
ADD COLUMN name VARCHAR(255);

-- Backfill customer_id from visits table for existing records
UPDATE visit_documents vd
SET customer_id = v.customer_id
FROM visits v
WHERE vd.visit_id = v.id
AND vd.customer_id IS NULL;

-- Backfill name from file_name for existing records
UPDATE visit_documents
SET name = file_name
WHERE name IS NULL;

-- Make customer_id and name NOT NULL after backfilling
ALTER TABLE visit_documents
ALTER COLUMN customer_id SET NOT NULL;

ALTER TABLE visit_documents
ALTER COLUMN name SET NOT NULL;

-- Add foreign key constraint to customers table
ALTER TABLE visit_documents
ADD CONSTRAINT fk_visit_document_customer
    FOREIGN KEY (customer_id)
    REFERENCES customers(id)
    ON DELETE CASCADE;

-- Add index for customer-based queries
CREATE INDEX IF NOT EXISTS idx_visit_documents_customer_id
    ON visit_documents(customer_id, uploaded_at DESC);

-- Update table comment
COMMENT ON TABLE visit_documents IS 'Documents attached to visits with customer denormalization for fast historical lookups';
COMMENT ON COLUMN visit_documents.customer_id IS 'Denormalized customer reference for fast historical queries';
COMMENT ON COLUMN visit_documents.name IS 'Human-readable document title (e.g., "Intake Protocol - PO12345")';
COMMENT ON COLUMN visit_documents.type IS 'PHOTO, PDF, PROTOCOL, INTAKE, OUTTAKE, DAMAGE_MAP, or OTHER';
