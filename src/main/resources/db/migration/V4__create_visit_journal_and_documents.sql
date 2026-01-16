-- Migration: Add journal entries and documents to visits
-- Description: Journal entries for internal notes and customer communication, documents for various file types
-- Date: 2026-01-16

-- Table: visit_journal_entries
-- Journal entries for tracking internal notes and customer communication
CREATE TABLE IF NOT EXISTS visit_journal_entries (
    id UUID PRIMARY KEY,
    visit_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    created_by UUID NOT NULL,
    created_by_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,

    -- Foreign key to visits
    CONSTRAINT fk_journal_entry_visit
        FOREIGN KEY (visit_id)
        REFERENCES visits(id)
        ON DELETE CASCADE
);

-- Indexes for visit_journal_entries table
CREATE INDEX IF NOT EXISTS idx_visit_journal_entries_visit_id
    ON visit_journal_entries(visit_id);

CREATE INDEX IF NOT EXISTS idx_visit_journal_entries_created_at
    ON visit_journal_entries(visit_id, created_at);

-- Table: visit_documents
-- Documents attached to visits (photos, PDFs, protocols)
CREATE TABLE IF NOT EXISTS visit_documents (
    id UUID PRIMARY KEY,
    visit_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_id VARCHAR(255) NOT NULL,
    file_url VARCHAR(500) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    uploaded_by UUID NOT NULL,
    uploaded_by_name VARCHAR(200) NOT NULL,
    category VARCHAR(100),

    -- Foreign key to visits
    CONSTRAINT fk_visit_document_visit
        FOREIGN KEY (visit_id)
        REFERENCES visits(id)
        ON DELETE CASCADE
);

-- Indexes for visit_documents table
CREATE INDEX IF NOT EXISTS idx_visit_documents_visit_id
    ON visit_documents(visit_id);

CREATE INDEX IF NOT EXISTS idx_visit_documents_uploaded_at
    ON visit_documents(visit_id, uploaded_at);

-- Comments for documentation
COMMENT ON TABLE visit_journal_entries IS 'Journal entries for tracking internal notes and customer communication';
COMMENT ON TABLE visit_documents IS 'Documents attached to visits (photos, PDFs, protocols)';

COMMENT ON COLUMN visit_journal_entries.type IS 'INTERNAL_NOTE or CUSTOMER_COMMUNICATION';
COMMENT ON COLUMN visit_journal_entries.is_deleted IS 'Soft delete flag for journal entries';
COMMENT ON COLUMN visit_documents.type IS 'PHOTO, PDF, or PROTOCOL';
COMMENT ON COLUMN visit_documents.category IS 'Optional category for document organization (e.g., przyjecie, protokoly)';
