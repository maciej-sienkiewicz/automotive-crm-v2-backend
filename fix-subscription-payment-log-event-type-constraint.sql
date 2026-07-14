-- Fixes check constraint on subscription_payment_log.event_type
-- The original constraint was created before SUBSCRIPTION_RENEWAL was added to the enum.

ALTER TABLE subscription_payment_log
    DROP CONSTRAINT IF EXISTS subscription_payment_log_event_type_check;

ALTER TABLE subscription_payment_log
    ADD CONSTRAINT subscription_payment_log_event_type_check
        CHECK (event_type IN (
            'SUBSCRIPTION_PURCHASE',
            'SUBSCRIPTION_RENEWAL',
            'PLAN_UPGRADE',
            'PLAN_DOWNGRADE',
            'ADD_ON_ACTIVATION',
            'ADD_ON_DEACTIVATION'
        ));
