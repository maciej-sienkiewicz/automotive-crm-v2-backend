-- Dosynchronizowanie CHECK-ów z enumami, które od V36 urosły.
--
-- ## Co się zepsuło
--
-- V36 wypisała dozwolone wartości ręcznie. Od tego czasu do enumów w Kotlinie doszły
-- nowe stałe, a lista w bazie została ta sama. Każdy INSERT z nową wartością kończy się
-- naruszeniem CHECK-a — i w zależności od tego, jak zapisujący radzi sobie z błędem,
-- albo coś cicho ginie, albo wywraca całą operację.
--
-- ### communication_log.message_type — brakowało czterech wartości
--
-- MANUAL_SMS, SIGNATURE_LINK_SMS, CAMPAIGN_SMS, CAMPAIGN_EMAIL.
--
-- Najgorszy przypadek to SMS z linkiem do podpisu. Kolejność w RequestSignatureHandler
-- jest taka: zapis żądania podpisu → WYSYŁKA SMS-a → wpis do dziennika komunikacji,
-- wszystko w jednej transakcji. INSERT do communication_log wykonuje się dopiero przy
-- commicie (Hibernate odkłada zapis), więc naruszenie CHECK-a wywraca transakcję JUŻ PO
-- tym, jak SMS fizycznie poszedł do klienta. Efekt: klient dostaje SMS-a, a żądanie
-- podpisu razem z tokenem linku znika wraz z rollbackiem — i link odpowiada „Link jest
-- nieprawidłowy lub wygasł". Pracownik widzi błąd integralności, klient dostaje
-- wiadomość prowadzącą donikąd.
--
-- ### audit_logs.module — brakowało czterech wartości
--
-- CAMPAIGN, COMMUNICATION, WORK_TIME, VISIT_CARD.
--
-- Tu nic się nie wywracało, bo audyt pisze się we własnej transakcji i łapie wyjątki
-- (AuditLogWriter, AuditService.record) — więc wpisy po prostu ginęły po cichu. Skutek:
-- w Aktywności NIE MA ani jednego zdarzenia komunikacji (każdy SMS i e-mail idzie przez
-- moduł COMMUNICATION), otwarć Karty Wizyty, kampanii ani czasu pracy. Dziennik wygląda
-- na kompletny, a brakuje w nim czterech modułów.
--
-- ## Dlaczego to się powtórzy, jeśli nie pilnować
--
-- Lista wartości jest kopią enuma utrzymywaną ręcznie w innym języku i innym repozytorium
-- zmian. Nic nie łączy jednego z drugim, więc rozjazd jest kwestią czasu, a objawia się
-- dopiero na produkcji. Pilnuje tego teraz test EnumCheckConstraintSyncTest, który czyta
-- te migracje i porównuje je z enumami — rozjazd wywala się w CI, nie u klienta.

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
            'MANUAL_SMS',
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
            'VISIT_CARD_UPSELL_SMS',
            'SIGNATURE_LINK_SMS',
            'CAMPAIGN_SMS',
            'CAMPAIGN_EMAIL'
        )) NOT VALID;

ALTER TABLE audit_logs
    DROP CONSTRAINT IF EXISTS audit_logs_module_check;
ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_module_check
        CHECK (module IN (
            'CUSTOMER', 'VEHICLE', 'VISIT', 'APPOINTMENT', 'SERVICE', 'LEAD',
            'PROTOCOL', 'CONSENT', 'INBOUND_CALL', 'APPOINTMENT_COLOR',
            'STUDIO', 'USER', 'FINANCE', 'CASH_REGISTER', 'EMPLOYEE',
            'TASK', 'SECURITY', 'DOOR_TO_DOOR',
            'CAMPAIGN', 'COMMUNICATION', 'WORK_TIME', 'VISIT_CARD'
        )) NOT VALID;
