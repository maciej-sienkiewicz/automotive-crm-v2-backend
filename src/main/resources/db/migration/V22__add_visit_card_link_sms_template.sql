ALTER TABLE sms_automation_configs
    ADD COLUMN visit_card_link_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN visit_card_link_message_template TEXT NOT NULL DEFAULT '{{studio}}: Karta Twojej wizyty jest dostępna tutaj: {{link}}';
