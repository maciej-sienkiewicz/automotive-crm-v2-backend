-- The sender header of an outbound SMS now has exactly one source of truth:
--   sms_automation_configs.sms_sender_name  +  sms_automation_configs.sms_api_name_confirmed
--
-- studio_settings.sms_api_name_confirmed was a second, competing flag: the outbound gateway
-- read it (together with the company name, which SMSAPI never confirmed), while studios
-- actually configured the sender name on the sms_automation_configs side. It is retired here,
-- after carrying over any studio that had been confirmed under the old flag.
--
-- Guarded by an existence check because the column predates this migration history and is
-- not present on every environment.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'studio_settings'
          AND column_name = 'sms_api_name_confirmed'
    ) THEN
        EXECUTE $sql$
            UPDATE sms_automation_configs c
               SET sms_api_name_confirmed = true,
                   updated_at             = now()
              FROM studio_settings s
             WHERE s.studio_id = c.studio_id
               AND s.sms_api_name_confirmed = true
               AND c.sms_api_name_confirmed = false
               AND c.sms_sender_name IS NOT NULL
               AND btrim(c.sms_sender_name) <> ''
        $sql$;

        ALTER TABLE studio_settings DROP COLUMN sms_api_name_confirmed;
    END IF;
END $$;
