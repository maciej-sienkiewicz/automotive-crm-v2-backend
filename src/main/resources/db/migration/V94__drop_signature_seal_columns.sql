-- Pieczętowanie kwalifikowane (PAdES + znacznik czasu RFC 3161) zostało usunięte
-- z systemu: krok nigdy nie był włączony na produkcji (signing.seal.enabled=false),
-- a jego kolumny tylko zaśmiecały model. Dokumenty podpisane wcześniej pozostają
-- nietknięte w S3 — usuwamy wyłącznie metadane kroku, który już nie istnieje.
ALTER TABLE signature_requests DROP COLUMN IF EXISTS sealed_at;
ALTER TABLE signature_requests DROP COLUMN IF EXISTS seal_applied;
ALTER TABLE signature_requests DROP COLUMN IF EXISTS timestamp_applied;
