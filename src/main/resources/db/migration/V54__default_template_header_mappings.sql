-- Default acceptance-protocol templates: fill the header section too.
-- Adds the provider name (Usługodawca), protocol number and receiving-employee
-- mappings to every already-provisioned default template. New templates get
-- these mappings from DefaultProtocolFieldMappings at creation time.

-- Hibernate-generated enum CHECK constraint predates the PROVIDER_NAME and
-- RECEIVED_BY_NAME CrmDataKey values — rebuild it first (same pattern as V36:
-- DROP + ADD CONSTRAINT NOT VALID, guards new rows only), otherwise both this
-- migration's inserts and any runtime insert with the new keys fail with 23514.
ALTER TABLE protocol_field_mappings
    DROP CONSTRAINT IF EXISTS protocol_field_mappings_crm_data_key_check;
ALTER TABLE protocol_field_mappings
    ADD CONSTRAINT protocol_field_mappings_crm_data_key_check
        CHECK (crm_data_key IN (
            'VEHICLE_PLATE', 'VEHICLE_BRAND', 'VEHICLE_MODEL', 'VEHICLE_BRAND_MODEL',
            'VEHICLE_COLOR', 'VEHICLE_YEAR', 'VEHICLE_ENGINE_TYPE',
            'CUSTOMER_FULL_NAME', 'CUSTOMER_PHONE', 'CUSTOMER_EMAIL', 'CUSTOMER_COMPANY_NIP',
            'VISIT_MILEAGE', 'VISIT_NUMBER', 'VISIT_DATE', 'VISIT_COMPLETED_DATE',
            'TOTAL_NET_AMOUNT', 'TOTAL_GROSS_AMOUNT', 'TOTAL_VAT_AMOUNT',
            'VEHICLE_KEYS_RECEIVED', 'VEHICLE_DOCUMENTS_RECEIVED',
            'SERVICES_LIST', 'NOTES',
            'STUDIO_NAME', 'PROVIDER_NAME', 'RECEIVED_BY_NAME',
            'CURRENT_DATE', 'CURRENT_DATETIME'
        )) NOT VALID;

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
