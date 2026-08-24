-- ═══════════════════════════════════════════════════════════════════════════════
-- Web Push devices for Click-to-Call.
--
-- A user works at a desktop but calls from their own phone. Clicking a phone
-- number in the CRM on the desktop must ring the customer FROM the phone —
-- without a native app, without Handoff/Phone Link, on any OS. The only
-- browser primitive that reaches a phone whose browser is closed is Web Push,
-- so the phone's PWA registers a push subscription here and the desktop's
-- "call" click turns into an encrypted push to that subscription.
--
-- One row = one browser profile on one device that agreed to receive call
-- notifications. The row belongs to a USER (the person whose pocket the phone
-- is in), and carries studio_id like every other table so tenant isolation
-- holds in every query.
--
-- The endpoint URL is a capability: whoever holds it (plus the p256dh/auth
-- keys) can push to that phone. It must live in Postgres, not Redis — this
-- deployment runs Redis WITHOUT persistence (see V70), and a subscription
-- silently lost on deploy means "click-to-call stopped working" a week later
-- with nobody knowing why. Uniqueness is enforced on a SHA-256 of the
-- endpoint, because push endpoints are long URLs and make poor index keys.
--
-- Revocation is a soft delete: the row stays so the devices list can show
-- "revoked" history, and a re-subscribe from the same browser simply
-- reactivates the row in place.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS push_devices (
    id            UUID PRIMARY KEY,
    studio_id     UUID NOT NULL,
    user_id       UUID NOT NULL,
    -- Human label shown in the devices list, e.g. "Pixel 8 – Chrome".
    device_name   VARCHAR(200) NOT NULL,
    user_agent    VARCHAR(400),
    -- Full push endpoint URL issued by the browser's push service (FCM,
    -- Mozilla autopush, Apple). Unique per browser profile + PWA install.
    endpoint      TEXT NOT NULL,
    -- SHA-256 of the endpoint, hex-encoded; the deduplication key.
    endpoint_hash CHAR(64) NOT NULL,
    -- Client public key (P-256, base64url) and auth secret (base64url) from
    -- PushSubscription.getKey(); both are required to encrypt payloads
    -- per RFC 8291. Useless without the endpoint, but treated as secrets.
    p256dh        TEXT NOT NULL,
    auth          TEXT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- Last successfully delivered push; drives "last used" in the UI.
    last_used_at  TIMESTAMP WITH TIME ZONE,
    revoked_at    TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_push_devices_endpoint
    ON push_devices(endpoint_hash);

CREATE INDEX IF NOT EXISTS idx_push_devices_user
    ON push_devices(studio_id, user_id)
    WHERE revoked_at IS NULL;
