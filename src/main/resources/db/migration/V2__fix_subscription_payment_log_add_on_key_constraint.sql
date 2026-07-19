-- subscription_payment_log.add_on_key: dodanie STATISTICS_MODULE do check constraint.
-- Constraint był wygenerowany przez Hibernate przed dodaniem tej wartości do enum AddOnKey
-- (commit 74d0184). Hibernate ddl-auto=update nie przebudowuje istniejących constraintów.

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
