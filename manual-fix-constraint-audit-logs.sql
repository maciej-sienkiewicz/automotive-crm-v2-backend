-- Manual fix for audit_logs_action_check constraint
-- Run this ONLY if Flyway migration fails or Flyway is not yet active
-- Connect to database and run: psql -U postgres -d detailing_crm -f manual-fix-constraint-audit-logs.sql

-- Drop old constraint (which is missing the Finance audit actions)
ALTER TABLE audit_logs
DROP CONSTRAINT IF EXISTS audit_logs_action_check;

-- Add new constraint with all current AuditAction enum values
ALTER TABLE audit_logs
ADD CONSTRAINT audit_logs_action_check
CHECK (action IN (
    -- CRUD operations
    'CREATE', 'UPDATE', 'DELETE',

    -- Status transitions
    'STATUS_CHANGE',

    -- Sub-entity operations
    'PHOTO_ADDED', 'PHOTO_DELETED', 'DOCUMENT_ADDED',
    'COMMENT_ADDED', 'COMMENT_UPDATED', 'COMMENT_DELETED',
    'NOTE_ADDED', 'NOTE_UPDATED', 'NOTE_DELETED',

    -- Visit-specific actions
    'SERVICE_ADDED', 'SERVICE_UPDATED', 'SERVICE_REMOVED', 'SERVICES_UPDATED',
    'VISIT_CONFIRMED', 'VISIT_CANCELLED', 'VISIT_COMPLETED',
    'VISIT_REJECTED', 'VISIT_MARKED_READY', 'VISIT_ARCHIVED',

    -- Appointment-specific
    'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESTORED', 'APPOINTMENT_DELETED',
    'APPOINTMENT_CONVERTED', 'APPOINTMENT_ABANDONED',

    -- Protocol-specific
    'PROTOCOL_GENERATED', 'PROTOCOL_SIGNED',

    -- Consent-specific
    'CONSENT_GRANTED', 'CONSENT_REVOKED',

    -- Lead-specific
    'LEAD_CONVERTED', 'LEAD_ABANDONED',

    -- Inbound-specific
    'CALL_ACCEPTED', 'CALL_REJECTED',

    -- Vehicle-specific
    'OWNER_ADDED', 'OWNER_REMOVED', 'APPOINTMENT_ADDED', 'VISIT_ADDED',

    -- Company data
    'COMPANY_UPDATED', 'COMPANY_DELETED',

    -- Finance (previously missing – cause of the constraint violation)
    'DOCUMENT_ISSUED', 'DOCUMENT_STATUS_CHANGED', 'DOCUMENT_DELETED', 'CASH_ADJUSTED'
));

-- Verify the constraint
SELECT
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint
WHERE conname = 'audit_logs_action_check';
