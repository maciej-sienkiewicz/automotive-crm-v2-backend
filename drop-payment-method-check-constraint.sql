-- Removes the check constraint that limits allowed payment_method values.
-- Required after adding BLIK_NA_NUMER and BLIK_TERMINAL to the PaymentMethod enum.
ALTER TABLE financial_documents DROP CONSTRAINT IF EXISTS financial_documents_payment_method_check;
