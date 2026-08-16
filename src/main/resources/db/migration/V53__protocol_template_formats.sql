-- Protocol templates: PDF/HTML format support, upload verification status and
-- the default-template flag (guards the check-in template invariant).
--
-- Deployed environments run Hibernate with ddl-auto=validate, so the columns
-- added to ProtocolTemplateEntity must exist before the session factory boots.

ALTER TABLE protocol_templates
    ADD COLUMN IF NOT EXISTS file_format VARCHAR(10) NOT NULL DEFAULT 'PDF',
    ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

-- Templates uploaded before verification existed are already in active use —
-- grandfather them in as VERIFIED so nothing in the visit pipeline blocks on
-- them. New templates get their status set explicitly by the application.
UPDATE protocol_templates SET verification_status = 'VERIFIED';
