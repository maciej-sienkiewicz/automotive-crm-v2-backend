-- ═══════════════════════════════════════════════════════════════════════════════
-- Bez listy „Do zafakturowania".
--
-- Decyzja biznesu: tryb „Faktury wystawia księgowość" zostaje (V167: flaga studia
-- i dokumenty invoiced_externally poza przychodem), ale CRM nie prowadzi dla
-- księgowości listy sprzedaży do zafakturowania ani odhaczania „Faktura wystawiona".
-- Zgłoszenia nie mają już właściciela w kodzie — tabela znika.
-- ═══════════════════════════════════════════════════════════════════════════════

DROP TABLE IF EXISTS external_invoice_requests;
