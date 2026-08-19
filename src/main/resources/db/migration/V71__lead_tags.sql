-- ═══════════════════════════════════════════════════════════════════════════════
-- Tags on a lead, replacing the single "category" as the "what are they asking
-- about" axis of the analytics.
--
-- One category per lead was a poor fit for how enquiries actually arrive: "PPF on
-- the front, correction on the rest and a coating afterwards" is three subjects in
-- one message, and forcing it into one bucket meant the analytics answered a
-- question nobody asked. Tags are many per lead, so the same enquiry can count
-- toward every service it touches.
--
-- Existing categories are copied over, so the analytics keeps its history and the
-- column can be dropped later without losing anything.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS lead_tags (
    lead_id    UUID NOT NULL,
    -- Code from the LeadTag dictionary. A closed list is the price of aggregation:
    -- free text cannot be grouped into "what do people ask us about".
    tag_code   VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (lead_id, tag_code)
);

CREATE INDEX IF NOT EXISTS idx_lead_tags_code ON lead_tags(tag_code);

-- Historia: dotychczasowa kategoria staje się pierwszym tagiem leada. Kody słownika
-- tagów są nadzbiorem kodów kategorii, więc przepisanie jest tożsamościowe.
INSERT INTO lead_tags (lead_id, tag_code, created_at)
SELECT id, category, COALESCE(created_at, NOW())
FROM leads
WHERE category IS NOT NULL
ON CONFLICT DO NOTHING;
