-- Default acceptance-protocol templates: fill the header section too.
-- Adds the provider name (Usługodawca), protocol number and receiving-employee
-- mappings to every already-provisioned default template. New templates get
-- these mappings from DefaultProtocolFieldMappings at creation time.

INSERT INTO protocol_field_mappings (id, studio_id, template_id, pdf_field_name, crm_data_key, created_at)
SELECT gen_random_uuid(), pt.studio_id, pt.id, m.field_name, m.crm_key, NOW()
FROM protocol_templates pt
CROSS JOIN (VALUES
    ('protocolnumber', 'VISIT_NUMBER'),
    ('receivedby',     'RECEIVED_BY_NAME'),
    ('provider',       'PROVIDER_NAME')
) AS m(field_name, crm_key)
WHERE pt.is_default = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM protocol_field_mappings pfm
      WHERE pfm.template_id = pt.id
        AND pfm.pdf_field_name = m.field_name
  );
