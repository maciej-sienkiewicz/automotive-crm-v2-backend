-- subscription_payment_log.event_type: naprawa check constraint.
-- Constraint był wygenerowany przez Hibernate zanim SUBSCRIPTION_RENEWAL trafił do enuma.
-- Hibernate ddl-auto=update nie przebudowuje istniejących constraintów, więc INSERT
-- z event_type='SUBSCRIPTION_RENEWAL' (przedłużenie subskrypcji) rzuca 23514.
-- NOT VALID: pilnuje wyłącznie nowych INSERT/UPDATE, historycznych wierszy nie waliduje.

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
        )) NOT VALID;
