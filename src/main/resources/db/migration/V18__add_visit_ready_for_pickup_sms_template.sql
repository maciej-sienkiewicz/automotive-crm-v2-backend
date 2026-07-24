ALTER TABLE sms_automation_configs
    ADD COLUMN visit_ready_for_pickup_enabled        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN visit_ready_for_pickup_message_template TEXT   NOT NULL DEFAULT 'Drogi/a {{imie}}, Twój pojazd jest gotowy do odbioru w {{studio}}. Zapraszamy!';
