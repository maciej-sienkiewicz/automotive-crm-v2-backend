-- V4: Fix audit_logs_action_check constraint
-- Finance audit actions (DOCUMENT_ISSUED, DOCUMENT_STATUS_CHANGED, DOCUMENT_DELETED, CASH_ADJUSTED)
-- were added to AuditAction enum but not to the database CHECK constraint.

ALTER TABLE audit_logs
DROP CONSTRAINT IF EXISTS audit_logs_action_check;

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

    -- Finance
    'DOCUMENT_ISSUED', 'DOCUMENT_STATUS_CHANGED', 'DOCUMENT_DELETED', 'CASH_ADJUSTED'
));
