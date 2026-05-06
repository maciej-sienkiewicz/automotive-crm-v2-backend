-- Add relevance_score column to tracked_keywords.
-- Populated by KeywordExpansionService during monthly keyword expansion.
-- Score range: 0.0 (not relevant) → 1.0 (highly relevant).
-- NULL until the first expansion run completes.

ALTER TABLE tracked_keywords
    ADD COLUMN IF NOT EXISTS relevance_score DOUBLE PRECISION;

CREATE INDEX IF NOT EXISTS idx_tracked_keywords_relevance_score
    ON tracked_keywords (relevance_score DESC NULLS LAST);
