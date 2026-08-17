ALTER TABLE studio_settings ADD COLUMN visit_number_format VARCHAR(100);
ALTER TABLE studio_settings ADD COLUMN visit_number_sequence_length INTEGER NOT NULL DEFAULT 5;
