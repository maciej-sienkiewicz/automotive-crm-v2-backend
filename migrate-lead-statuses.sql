-- Migration: update lead statuses to new lifecycle model
-- Run this ONCE before deploying the new version of the application.
--
-- Old statuses → new statuses:
--   IN_PROGRESS  → stays IN_PROGRESS  (lead was being worked on — now means "answered, waiting for client")
--   CONVERTED    → COMPLETED          (visit happened; CONFIRMED is reserved for "appointment created")
--   ABANDONED    → LOST               (contact lost or offer rejected)
--
-- New statuses introduced (no existing rows):
--   NEW       — just received, nobody responded yet
--   CONFIRMED — reservation created from this lead
--   NO_SHOW   — appointment was scheduled but client didn't show

BEGIN;

ALTER TABLE leads ADD COLUMN IF NOT EXISTS appointment_id UUID;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS visit_id UUID;

UPDATE leads SET status = 'COMPLETED' WHERE status = 'CONVERTED';
UPDATE leads SET status = 'LOST'      WHERE status = 'ABANDONED';

COMMIT;
