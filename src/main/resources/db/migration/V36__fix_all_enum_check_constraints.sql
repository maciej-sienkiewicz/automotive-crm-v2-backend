-- Systemowa naprawa wszystkich enum CHECK constraintów wygenerowanych przez Hibernate.
--
-- Wzorzec błędu: Hibernate ddl-auto=update generuje CHECK constraint przy pierwszym
-- tworzeniu schematu. Nowe wartości dodane do enuma w kodzie nie są automatycznie
-- dopisywane do istniejącego constraintu — prowadzi to do błędu 23514 przy INSERT.
--
-- Podejście: DROP + ADD CONSTRAINT NOT VALID (pilnuje tylko nowych wierszy).
-- Kolumny z columnDefinition w @Column są już zabezpieczone po stronie kodu (Hibernate
-- nie generuje dla nich CHECK) — tu naprawiamy stan w DB i zabezpieczamy przyszłość.

-- ── payment_orders ────────────────────────────────────────────────────────────

ALTER TABLE payment_orders
    DROP CONSTRAINT IF EXISTS payment_orders_order_type_check;
ALTER TABLE payment_orders
    ADD CONSTRAINT payment_orders_order_type_check
        CHECK (order_type IN ('INITIAL_PURCHASE', 'RENEWAL', 'PLAN_UPGRADE', 'ADD_ON_PURCHASE')) NOT VALID;

ALTER TABLE payment_orders
    DROP CONSTRAINT IF EXISTS payment_orders_status_check;
ALTER TABLE payment_orders
    ADD CONSTRAINT payment_orders_status_check
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED')) NOT VALID;

ALTER TABLE payment_orders
    DROP CONSTRAINT IF EXISTS payment_orders_plan_key_check;
ALTER TABLE payment_orders
    ADD CONSTRAINT payment_orders_plan_key_check
        CHECK (plan_key IN ('BASIC', 'FULL')) NOT VALID;

-- ── visits ────────────────────────────────────────────────────────────────────

ALTER TABLE visits
    DROP CONSTRAINT IF EXISTS visits_status_check;
ALTER TABLE visits
    ADD CONSTRAINT visits_status_check
        CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'READY_FOR_PICKUP', 'COMPLETED', 'REJECTED', 'ARCHIVED')) NOT VALID;

-- ── visit_service_items ───────────────────────────────────────────────────────

ALTER TABLE visit_service_items
    DROP CONSTRAINT IF EXISTS visit_service_items_adjustment_type_check;
ALTER TABLE visit_service_items
    ADD CONSTRAINT visit_service_items_adjustment_type_check
        CHECK (adjustment_type IN ('PERCENT', 'FIXED_NET', 'FIXED_GROSS', 'SET_NET', 'SET_GROSS')) NOT VALID;

ALTER TABLE visit_service_items
    DROP CONSTRAINT IF EXISTS visit_service_items_status_check;
ALTER TABLE visit_service_items
    ADD CONSTRAINT visit_service_items_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CONFIRMED')) NOT VALID;

ALTER TABLE visit_service_items
    DROP CONSTRAINT IF EXISTS visit_service_items_pending_operation_check;
ALTER TABLE visit_service_items
    ADD CONSTRAINT visit_service_items_pending_operation_check
        CHECK (pending_operation IN ('ADD', 'EDIT', 'DELETE')) NOT VALID;

-- ── visit_journal_entries ─────────────────────────────────────────────────────

ALTER TABLE visit_journal_entries
    DROP CONSTRAINT IF EXISTS visit_journal_entries_type_check;
ALTER TABLE visit_journal_entries
    ADD CONSTRAINT visit_journal_entries_type_check
        CHECK (type IN ('INTERNAL_NOTE', 'CUSTOMER_COMMUNICATION')) NOT VALID;

-- ── visit_documents ───────────────────────────────────────────────────────────

ALTER TABLE visit_documents
    DROP CONSTRAINT IF EXISTS visit_documents_type_check;
ALTER TABLE visit_documents
    ADD CONSTRAINT visit_documents_type_check
        CHECK (type IN ('PHOTO', 'PDF', 'PROTOCOL', 'INTAKE', 'OUTTAKE', 'DAMAGE_MAP', 'OTHER')) NOT VALID;

-- ── visit_comments ────────────────────────────────────────────────────────────

ALTER TABLE visit_comments
    DROP CONSTRAINT IF EXISTS visit_comments_type_check;
ALTER TABLE visit_comments
    ADD CONSTRAINT visit_comments_type_check
        CHECK (type IN ('INTERNAL', 'FOR_CUSTOMER')) NOT VALID;

-- ── financial_documents ───────────────────────────────────────────────────────

ALTER TABLE financial_documents
    DROP CONSTRAINT IF EXISTS financial_documents_document_type_check;
ALTER TABLE financial_documents
    ADD CONSTRAINT financial_documents_document_type_check
        CHECK (document_type IN ('RECEIPT', 'INVOICE', 'OTHER')) NOT VALID;

ALTER TABLE financial_documents
    DROP CONSTRAINT IF EXISTS financial_documents_direction_check;
ALTER TABLE financial_documents
    ADD CONSTRAINT financial_documents_direction_check
        CHECK (direction IN ('INCOME', 'EXPENSE')) NOT VALID;

ALTER TABLE financial_documents
    DROP CONSTRAINT IF EXISTS financial_documents_status_check;
ALTER TABLE financial_documents
    ADD CONSTRAINT financial_documents_status_check
        CHECK (status IN ('PAID', 'PENDING', 'OVERDUE')) NOT VALID;

ALTER TABLE financial_documents
    DROP CONSTRAINT IF EXISTS financial_documents_payment_method_check;
ALTER TABLE financial_documents
    ADD CONSTRAINT financial_documents_payment_method_check
        CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER', 'BLIK_NA_NUMER', 'BLIK_TERMINAL', 'OTHER')) NOT VALID;

-- ── cash_operations ───────────────────────────────────────────────────────────

ALTER TABLE cash_operations
    DROP CONSTRAINT IF EXISTS cash_operations_operation_type_check;
ALTER TABLE cash_operations
    ADD CONSTRAINT cash_operations_operation_type_check
        CHECK (operation_type IN ('PAYMENT_IN', 'PAYMENT_OUT', 'MANUAL_ADJUSTMENT')) NOT VALID;

-- ── communication_log ─────────────────────────────────────────────────────────

ALTER TABLE communication_log
    DROP CONSTRAINT IF EXISTS communication_log_channel_check;
ALTER TABLE communication_log
    ADD CONSTRAINT communication_log_channel_check
        CHECK (channel IN ('EMAIL', 'SMS')) NOT VALID;

ALTER TABLE communication_log
    DROP CONSTRAINT IF EXISTS communication_log_message_type_check;
ALTER TABLE communication_log
    ADD CONSTRAINT communication_log_message_type_check
        CHECK (message_type IN (
            'VISIT_WELCOME_EMAIL',
            'VISIT_CONFIRMED_EMAIL',
            'VISIT_READY_FOR_PICKUP_EMAIL',
            'VISIT_CONFIRMED_SMS',
            'VISIT_READY_FOR_PICKUP_SMS',
            'SMS_AUTOMATION_PRE_VISIT',
            'SMS_AUTOMATION_POST_VISIT',
            'SMS_AUTOMATION_DELAYED_REMINDER',
            'SMS_CONSENT_REQUEST',
            'SMS_INBOUND_REPLY',
            'SMS_SERVICE_CHANGE_NOTIFICATION',
            'SMS_APPOINTMENT_RESCHEDULE_CONFIRMATION',
            'SMS_BOOKING_CONFIRMATION',
            'VISIT_CARD_EMAIL',
            'VISIT_CARD_SMS',
            'VISIT_CARD_UPSELL_SMS'
        )) NOT VALID;

ALTER TABLE communication_log
    DROP CONSTRAINT IF EXISTS communication_log_status_check;
ALTER TABLE communication_log
    ADD CONSTRAINT communication_log_status_check
        CHECK (status IN ('SENT', 'RECEIVED', 'FAILED')) NOT VALID;

-- ── audit_logs ────────────────────────────────────────────────────────────────
-- Uwaga: audit_logs.action ma już columnDefinition = "varchar(50)" w encji,
-- więc Hibernate nie generuje dla niej CHECK. Naprawiamy tylko module.

ALTER TABLE audit_logs
    DROP CONSTRAINT IF EXISTS audit_logs_module_check;
ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_module_check
        CHECK (module IN (
            'CUSTOMER', 'VEHICLE', 'VISIT', 'APPOINTMENT', 'SERVICE', 'LEAD',
            'PROTOCOL', 'CONSENT', 'INBOUND_CALL', 'APPOINTMENT_COLOR',
            'STUDIO', 'USER', 'FINANCE', 'CASH_REGISTER', 'EMPLOYEE',
            'TASK', 'SECURITY', 'DOOR_TO_DOOR'
        )) NOT VALID;

-- ── appointments ──────────────────────────────────────────────────────────────

ALTER TABLE appointments
    DROP CONSTRAINT IF EXISTS appointments_status_check;
ALTER TABLE appointments
    ADD CONSTRAINT appointments_status_check
        CHECK (status IN ('CREATED', 'ABANDONED', 'CANCELLED', 'CONVERTED')) NOT VALID;

-- ── appointment_line_items ────────────────────────────────────────────────────

ALTER TABLE appointment_line_items
    DROP CONSTRAINT IF EXISTS appointment_line_items_adjustment_type_check;
ALTER TABLE appointment_line_items
    ADD CONSTRAINT appointment_line_items_adjustment_type_check
        CHECK (adjustment_type IN ('PERCENT', 'FIXED_NET', 'FIXED_GROSS', 'SET_NET', 'SET_GROSS')) NOT VALID;
