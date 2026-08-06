-- Activity feed v2 for the audit module.
--
-- Adds the columns the owner-facing activity board needs: who really acted (employee,
-- customer, system), how much attention the event deserves, which business objects it
-- touches, and how much money was involved. Business context lands in indexed columns
-- rather than the `metadata` JSON, so "everything around this customer" stays an index
-- scan instead of a full-table JSON filter.

-- ── Actor ─────────────────────────────────────────────────────────────────────
-- Before v2 every event had to borrow an employee's user_id; scheduled jobs wrote an
-- all-zeroes UUID to satisfy NOT NULL. user_id now holds the actor's id in the namespace
-- implied by actor_type, and is NULL for automated actors.

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(20) NOT NULL DEFAULT 'EMPLOYEE';

ALTER TABLE audit_logs
    ALTER COLUMN user_id DROP NOT NULL;

UPDATE audit_logs
SET actor_type = 'SYSTEM',
    user_id    = NULL
WHERE user_id = '00000000-0000-0000-0000-000000000000';

-- Historical rows with no captured name would render as a blank row in the feed.
UPDATE audit_logs
SET user_display_name = CASE actor_type
                            WHEN 'SYSTEM' THEN 'System'
                            ELSE 'Nieznany pracownik'
                        END
WHERE user_display_name IS NULL
   OR btrim(user_display_name) = '';

-- ── Presentation ──────────────────────────────────────────────────────────────

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS severity VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS channel VARCHAR(30) NOT NULL DEFAULT 'WEB_PANEL';

-- Backfill severity from the action so historical rows sort sensibly in the feed
-- instead of all landing on the NORMAL default. Mirrors AuditAction's declared severity.
UPDATE audit_logs
SET severity = 'CRITICAL'
WHERE action IN (
    'VISIT_DELETED', 'DOCUMENT_DELETED', 'CASH_ADJUSTED', 'EMPLOYEE_TERMINATED',
    'COMPENSATION_SET', 'PAYROLL_GENERATED', 'PAYROLL_CONFIRMED', 'PAYROLL_PAID',
    'BONUS_ADDED', 'BONUS_DELETED', 'CROSS_TENANT_ACCESS_ATTEMPT', 'ACCOUNT_LOCKED'
);

UPDATE audit_logs
SET severity = 'HIGH'
WHERE action IN (
    'DELETE', 'PHOTO_DELETED', 'SERVICE_REMOVED', 'VISIT_CREATED', 'VISIT_CANCELLED',
    'VISIT_COMPLETED', 'VISIT_REJECTED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_DELETED',
    'PROTOCOL_SIGNED', 'CONSENT_REVOKED', 'LEAD_CONVERTED', 'LEAD_LOST',
    'LEAD_QUOTE_UPDATED', 'OWNER_REMOVED', 'VISIT_ADDED', 'COMPANY_DELETED',
    'DOCUMENT_ISSUED', 'DOCUMENT_STATUS_CHANGED', 'DOCUMENT_NUMBER_UPDATED',
    'DOCUMENT_RESTORED', 'CONTRACT_CREATED', 'CONTRACT_ENDED', 'WORK_TIME_REJECTED',
    'WORK_TIME_ENTRY_DELETED', 'LEAVE_REJECTED', 'LOGIN_FAILURE'
);

UPDATE audit_logs
SET severity = 'LOW'
WHERE action IN (
    'PHOTO_ADDED', 'COMMENT_ADDED', 'COMMENT_UPDATED', 'NOTE_ADDED', 'NOTE_UPDATED',
    'LEAD_COMMENT_ADDED', 'LEAD_COMMENT_UPDATED', 'LEAD_ACKNOWLEDGED', 'LOGIN_SUCCESS'
);

UPDATE audit_logs SET channel = 'SYSTEM' WHERE actor_type = 'SYSTEM';

-- ── Business context ──────────────────────────────────────────────────────────

ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS customer_id       uuid;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS customer_name     VARCHAR(300);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS vehicle_id        uuid;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS vehicle_name      VARCHAR(300);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS visit_id          uuid;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS visit_name        VARCHAR(300);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS appointment_id    uuid;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS appointment_name  VARCHAR(300);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS amount_gross      NUMERIC(19, 2);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS currency          VARCHAR(3);
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS correlation_id    uuid;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS ip_address        VARCHAR(45);

-- Historical rows recorded these in the metadata JSON. Lift the ones that were written
-- consistently enough to be worth keeping; the rest stay reachable through `metadata`.
UPDATE audit_logs
SET visit_id = (metadata ->> 'visitId')::uuid
WHERE visit_id IS NULL
  AND (metadata ->> 'visitId') ~ '^[0-9a-fA-F-]{36}$';

UPDATE audit_logs
SET appointment_id = (metadata ->> 'appointmentId')::uuid
WHERE appointment_id IS NULL
  AND (metadata ->> 'appointmentId') ~ '^[0-9a-fA-F-]{36}$';

UPDATE audit_logs
SET customer_id = (metadata ->> 'customerId')::uuid
WHERE customer_id IS NULL
  AND (metadata ->> 'customerId') ~ '^[0-9a-fA-F-]{36}$';

-- The module already identifies the subject for entity-scoped rows.
UPDATE audit_logs SET customer_id = entity_id::uuid
WHERE customer_id IS NULL AND module = 'CUSTOMER' AND entity_id ~ '^[0-9a-fA-F-]{36}$';

UPDATE audit_logs SET vehicle_id = entity_id::uuid
WHERE vehicle_id IS NULL AND module = 'VEHICLE' AND entity_id ~ '^[0-9a-fA-F-]{36}$';

UPDATE audit_logs SET visit_id = entity_id::uuid
WHERE visit_id IS NULL AND module = 'VISIT' AND entity_id ~ '^[0-9a-fA-F-]{36}$';

UPDATE audit_logs SET appointment_id = entity_id::uuid
WHERE appointment_id IS NULL AND module = 'APPOINTMENT' AND entity_id ~ '^[0-9a-fA-F-]{36}$';

-- ── Enum constraint ───────────────────────────────────────────────────────────
-- V36 pinned the module list in a CHECK constraint. Every new AuditModule (CAMPAIGN,
-- COMMUNICATION, WORK_TIME, VISIT_CARD — and whatever comes next) would fail to insert
-- until someone remembered to write a migration. The entity now declares
-- columnDefinition = varchar(50) so Hibernate stops re-generating it; validity of the
-- value is the application's concern, exactly as it already is for audit_logs.action.

ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_module_check;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_action_check;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_actor_type_check;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_severity_check;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_channel_check;

-- ── Indexes ───────────────────────────────────────────────────────────────────
-- The feed pages with keyset pagination over (created_at DESC, id DESC); the drill-down
-- indexes are partial because the context columns are NULL on most rows.

CREATE INDEX IF NOT EXISTS idx_audit_logs_studio_created_id
    ON audit_logs (studio_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_studio_customer
    ON audit_logs (studio_id, customer_id, created_at DESC)
    WHERE customer_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_logs_studio_vehicle
    ON audit_logs (studio_id, vehicle_id, created_at DESC)
    WHERE vehicle_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_logs_studio_visit
    ON audit_logs (studio_id, visit_id, created_at DESC)
    WHERE visit_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_logs_studio_actor_type
    ON audit_logs (studio_id, actor_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_correlation
    ON audit_logs (correlation_id)
    WHERE correlation_id IS NOT NULL;
