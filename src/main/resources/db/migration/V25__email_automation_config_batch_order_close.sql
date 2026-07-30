-- Migration: Add batch_order_close email template columns to email_automation_configs
-- Description: Allows studios to configure the email template sent when closing a batch month

ALTER TABLE email_automation_configs
    ADD COLUMN batch_order_close_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN batch_order_close_subject_template TEXT,
    ADD COLUMN batch_order_close_body_template TEXT;
