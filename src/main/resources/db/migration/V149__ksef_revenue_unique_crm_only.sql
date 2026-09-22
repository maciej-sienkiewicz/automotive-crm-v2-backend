-- Unikalność numeru faktury: TYLKO dla faktur wystawianych w CRM, nie dla pobranych z KSeF.
--
-- Decyzja właściciela produktu: dwa REALNE studia mogą dzielić jeden NIP sprzedawcy
-- (np. dwie lokalizacje jednej firmy). KSeF przy pobieraniu (SUBJECT1) filtruje po
-- NIP-ie, więc pull dla KAŻDEGO z tych studiów zwraca TE SAME faktury tego NIP-u.
-- Ta sama faktura ma prawo istnieć jako osobny wiersz pod każdym studiem.
--
-- Dotychczasowy indeks z V52 był globalny per NIP:
--   (seller_nip, invoice_number) WHERE ksef_status <> 'REJECTED'
-- i wywracał pull drugiego studia twardym błędem:
--   duplicate key value violates unique constraint "ux_ksef_revenue_invoices_seller_number"
--   Key (seller_nip, invoice_number)=(7773455203, FS 6/09/2026) already exists.
-- (faktura należała już do studia-rodzeństwa o tym samym NIP). A ponieważ pull jest
-- jednym @Transactional, jedna kolizja rollowała całą partię — synchronizacja stawała.
--
-- CZEGO NIE WOLNO POLUZOWAĆ: faktur WYSTAWIANYCH (source = CRM). KSeF rejestruje je
-- w kontekście NIP-u i odrzuca kodem 440 („Duplikat faktury") numer już zajęty pod tym
-- NIP-em — niezależnie od studia. RevenueInvoiceNumberGenerator liczy MAX+1 per NIP, a
-- ten indeks jest jego OSTATECZNĄ gwarancją: przy wyścigu dwóch wystawień drugi zapis
-- pada z DataIntegrityViolationException, a IssueRevenueInvoiceHandler ponawia z kolejnym
-- numerem (NUMBER_COLLISION_ATTEMPTS). To musi działać także dla dwóch studiów na jednym
-- NIP-ie — inaczej oba wystawiłyby tę samą FV i KSeF zwróciłby 440.
--
-- Dlatego zawężamy indeks predykatem `source = 'CRM'`:
--   • CRM (wystawiane u nas)  → nadal globalnie unikalne per NIP (440 + retry bez zmian),
--   • EXTERNAL (pobrane z KSeF) → bez tego ograniczenia; mogą duplikować się między
--     studiami. Powtórnemu pobraniu TEJ SAMEJ faktury do TEGO SAMEGO studia zapobiega
--     dopasowanie po (studio_id, ksef_number) w FetchRevenueInvoicesHandler (markAccepted
--     zamiast insertu), więc warstwa aplikacji trzyma idempotencję pulla.
--
-- Nazwa indeksu zostaje bez zmian — pod nią retry rozpoznaje kolizję (test regresyjny
-- IssueRevenueInvoiceHandlerTest „kolizja numeru w bazie jest ponawiana").
--
-- Migracja jest bezpieczna z definicji: nowy predykat jest WĘŻSZY od starego (podzbiór
-- wierszy: tylko CRM), więc każdy stan zgodny ze starym indeksem spełnia nowy —
-- przebudowa nie może natrafić na kolizję istniejących danych.

DROP INDEX IF EXISTS ux_ksef_revenue_invoices_seller_number;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ksef_revenue_invoices_seller_number
    ON ksef_revenue_invoices (seller_nip, invoice_number)
    WHERE ksef_status <> 'REJECTED' AND source = 'CRM';
