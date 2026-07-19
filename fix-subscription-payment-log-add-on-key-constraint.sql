-- Fixes check constraint on subscription_payment_log.add_on_key
-- The original constraint was created before STATISTICS_MODULE was added to the AddOnKey enum
-- (introduced in commit 74d0184 "Refactor packages/modules catalog and integrate Przelewy24 payments").
-- Hibernate ddl-auto=update does not rebuild existing check constraints when enum values change.
--
-- Apply on production DB:
--   psql -U postgres -d detailing_crm -f fix-subscription-payment-log-add-on-key-constraint.sql

ALTER TABLE subscription_payment_log
    DROP CONSTRAINT IF EXISTS subscription_payment_log_add_on_key_check;

ALTER TABLE subscription_payment_log
    ADD CONSTRAINT subscription_payment_log_add_on_key_check
        CHECK (add_on_key IN (
            'AI_LEAD_ASSISTANT',
            'INSTAGRAM_MONITORING',
            'CLIENT_COMMUNICATION',
            'MARKETING_CAMPAIGNS',
            'E_SIGNATURES',
            'FINANCE_MODULE',
            'STATISTICS_MODULE'
        ));

-- Verify
SELECT
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint
WHERE conname = 'subscription_payment_log_add_on_key_check';
