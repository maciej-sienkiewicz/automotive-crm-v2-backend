-- ═══════════════════════════════════════════════════════════════════════════════
-- Paired signing tablets, moved from Redis to durable storage.
--
-- Pairing was kept only in Redis, which this deployment runs WITHOUT persistence.
-- Every restart of the cache — that is, every deploy — silently unpaired every
-- tablet in every studio, and staff had to walk to the device and type a fresh
-- six-digit code. A paired device is not a cache entry: it is a fact about the
-- studio's hardware and it should outlive infrastructure restarts. The only thing
-- that may end a pairing is someone revoking it.
--
-- The pairing CODE stays in Redis on purpose: it lives five minutes and its loss
-- costs nothing but generating another one.
--
-- The token is stored as a SHA-256 hash, never in clear text. It is a bearer
-- credential — whoever reads it can sign documents as that studio's tablet — so a
-- leaked database dump must not hand over working devices. Hashing without a salt
-- is sufficient here: the token is 32 bytes from a CSPRNG, so there is no keyspace
-- to search and no password reuse to protect against.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS signing_tablets (
    -- Equals the tabletId used by the API and by signature_requests.tablet_id.
    id           UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    device_name  VARCHAR(200) NOT NULL,
    -- SHA-256 of the device token, hex-encoded.
    token_hash   CHAR(64) NOT NULL,
    paired_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- Last authenticated call from the device; drives the "last seen" column in the UI.
    last_seen_at TIMESTAMP WITH TIME ZONE,
    -- Revocation is a soft delete: the row stays, so an audit trail of which device
    -- signed what does not lose the device's name.
    revoked_at   TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_signing_tablets_token
    ON signing_tablets(token_hash);

CREATE INDEX IF NOT EXISTS idx_signing_tablets_studio
    ON signing_tablets(studio_id)
    WHERE revoked_at IS NULL;
