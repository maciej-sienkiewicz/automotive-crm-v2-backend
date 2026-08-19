-- Tracks which revision of the bundled default protocol template each studio's
-- system template (protocol_templates.is_default = true) currently holds in S3.
--
-- Every studio gets its OWN COPY of the bundled file at
-- {studioId}/protocols/templates/{templateId}.pdf, seeded once by
-- DefaultProtocolTemplateProvisioner. The provisioner only ever runs for a studio
-- that has NO active check-in template, so shipping a corrected bundled file did
-- not reach studios that were already seeded — their S3 objects kept the old
-- content indefinitely.
--
-- DefaultProtocolTemplateRefreshRunner closes that gap: on startup it re-uploads
-- the bundled file over the existing s3Key of every default template whose
-- recorded revision is behind DefaultProtocolTemplateProvisioner.DEFAULT_TEMPLATE_REVISION.
-- Bumping that constant is how a new bundled file reaches existing studios.
--
-- Deliberately a separate table rather than a column on protocol_templates:
-- ProtocolTemplateEntity.fromDomain() rebuilds the row on every PATCH/verify save,
-- which would silently reset a column here and trigger a pointless re-upload.
CREATE TABLE protocol_template_default_revisions (
    template_id UUID PRIMARY KEY REFERENCES protocol_templates (id) ON DELETE CASCADE,
    revision    INTEGER                  NOT NULL,
    applied_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Revision 0 = "content predates revision tracking". Every template already seeded
-- is behind the current revision, so the first startup after this migration
-- refreshes them all — which is exactly the intent: the bundled file shipped with
-- a hardcoded third-party logo that must not remain on other studios' protocols.
INSERT INTO protocol_template_default_revisions (template_id, revision, applied_at)
SELECT id, 0, NOW()
FROM protocol_templates
WHERE is_default = TRUE;
