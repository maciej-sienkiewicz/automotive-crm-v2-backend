ALTER TABLE sms_automation_configs
    ADD COLUMN sms_sender_name            VARCHAR(11) DEFAULT NULL,
    ADD COLUMN sms_api_name_confirmed     BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN sms_auth_document_s3_key   TEXT        DEFAULT NULL,
    ADD COLUMN sms_auth_document_name     TEXT        DEFAULT NULL;
