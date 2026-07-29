-- subscription_payment_log.plan_key: naprawa check constraint.
-- Constraint był wygenerowany przez Hibernate przed wprowadzeniem PlanKey.FULL.
-- Zawierał tylko starą nazwę planu i blokował INSERT dla plan_key='FULL'.
-- NOT VALID: pilnuje wyłącznie nowych INSERT/UPDATE, historycznych wierszy nie waliduje.

ALTER TABLE subscription_payment_log
    DROP CONSTRAINT IF EXISTS subscription_payment_log_plan_key_check;

ALTER TABLE subscription_payment_log
    ADD CONSTRAINT subscription_payment_log_plan_key_check
        CHECK (plan_key IN ('BASIC', 'FULL')) NOT VALID;
