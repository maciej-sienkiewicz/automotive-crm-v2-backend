-- 1) The "Opublikuj coś – ostatni post jest starszy niż 2 tygodnie" gap was generated
--    unconditionally (lastPostAt hardcoded to null in InsightEngine), so every stored
--    PROFILE_GAP insight is suspect. Dismiss the visible ones; the engine regenerates
--    them correctly on the next weekly run when the profile genuinely scores < 70.
UPDATE instagram_insights
SET status = 'DISMISSED', updated_at = now()
WHERE type = 'PROFILE_GAP' AND status = 'NEW';

-- 2) Suggestion quality: store the suggested account's follower count so micro-profiles
--    can be filtered out and ranking can prefer accounts of comparable scale.
ALTER TABLE instagram_profile_suggestions ADD COLUMN follower_count INTEGER;
