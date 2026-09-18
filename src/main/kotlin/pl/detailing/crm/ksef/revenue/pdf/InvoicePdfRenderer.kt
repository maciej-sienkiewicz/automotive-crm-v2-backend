package pl.detailing.crm.ksef.revenue.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.pdf.DocumentSheet
import pl.detailing.crm.shared.pdf.DocumentStyle
import java.awt.Color
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Wizualizacja faktury — ten sam system wizualny co certyfikat jakości i protokoły
 * wizyty (patrz [DocumentSheet]): granatowe belki, szare pola, A4 z siatką przepisaną
 * z domyślnych szablonów.
 *
 * To jest WIZUALIZACJA, nie oryginał. Od 2026 r. fakturą jest dokument ustrukturyzowany
 * w KSeF; PDF ma odwzorowywać jego dane merytorycznie i nieść kod QR, po którym odbiorca
 * potwierdzi zgodność u Ministerstwa Finansów. Dlatego nic tu nie jest przeliczane —
 * kwoty przychodzą z faktury takie, jakie poszły do KSeF.
 *
 * Komplet danych wymaganych przez art. 106e ust. 1 ustawy o VAT:
 * data wystawienia (pkt 1), numer (2), strony z adresami (3), NIP sprzedawcy (4)
 * i nabywcy (5), data sprzedaży (6), nazwa pozycji (7), miara i ilość (8), cena
 * jednostkowa netto (9), wartość netto (11), stawka (12), sumy netto i VAT w podziale
 * na stawki (13-14) oraz kwota należności ogółem (15).
 */
@Service
class InvoicePdfRenderer {

    private companion object {
        /**
         * Szerokości kolumn tabeli pozycji. Suma (530 pt) mieści się w kolumnie treści
         * (535,44 pt), a każda kolumna jest szersza niż jej podpis w nagłówku —
         * przy ciaśniejszych „KWOTA VAT" i „WARTOŚĆ BRUTTO" nachodziły na siebie.
         */
        val COLS = listOf(20f, 176f, 34f, 26f, 56f, 62f, 34f, 58f, 64f)
        const val ROW_FONT = 7.5f
        const val ROW_LEAD = 9.5f
        const val HEAD_FONT = 6.5f
    }

    fun render(data: InvoicePdfData): ByteArray = PDDocument().use { doc ->
        with(DocumentSheet(doc)) {
            header(data.logoPng, data.providerName, data.providerAddress)
            title(data.title)
            metaRow(
                listOfNotNull(
                    "NUMER FAKTURY" to data.invoiceNumber,
                    "DATA WYSTAWIENIA" to data.issueDate,
                    // Datę sprzedaży podaje się, gdy różni się od daty wystawienia
                    // (art. 106e ust. 1 pkt 6); równą pomijamy, żeby nie dublować pola.
                    data.saleDate?.let { "DATA SPRZEDAŻY" to it },
                    data.paymentDueDate?.let { "TERMIN PŁATNOŚCI" to it }
                )
            )

            drawCorrectionNote(this, data)
            drawParties(this, data)
            drawLines(this, data)
            drawVatSummary(this, data)
            drawPayment(this, data)
            drawKsefBlock(this, data)
            finish()
        }
    }

    /** Korekta musi wskazać fakturę pierwotną i przyczynę — art. 106j ust. 2. */
    private fun drawCorrectionNote(sheet: DocumentSheet, data: InvoicePdfData) {
        val corrected = data.correctedInvoiceNumber ?: return
        sheet.y -= 12f
        val text = listOfNotNull(
            "Korekta faktury $corrected",
            data.correctionReason?.takeIf { it.isNotBlank() }?.let { "Przyczyna: $it" }
        ).joinToString(". ")
        sheet.wrap(text, sheet.bold, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W).forEach { line ->
            sheet.ensure(DocumentStyle.NOTE_LEAD)
            sheet.text(
                sheet.bold, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                sheet.y - DocumentStyle.NOTE_FONT, line, DocumentStyle.INK
            )
            sheet.y -= DocumentStyle.NOTE_LEAD
        }
    }

    /** Sprzedawca i nabywca obok siebie: nazwa, adres, NIP. */
    private fun drawParties(sheet: DocumentSheet, data: InvoicePdfData) {
        sheet.y -= 15.85f
        val top = sheet.y
        val gap = 16f
        val colW = (DocumentStyle.CONTENT_W - gap) / 2f

        listOf(
            "SPRZEDAWCA" to data.seller,
            "NABYWCA" to data.buyer
        ).forEachIndexed { index, (label, party) ->
            val x = DocumentStyle.LEFT + index * (colW + gap)
            sheet.tab(x, top, label, colW)
            var ty = top - DocumentStyle.TAB_H - 12f

            sheet.wrap(party.name, sheet.bold, DocumentStyle.BODY, colW).take(2).forEach { line ->
                sheet.text(sheet.bold, DocumentStyle.BODY, x, ty, line, DocumentStyle.INK)
                ty -= DocumentStyle.BODY_LEAD - 1f
            }
            listOfNotNull(party.addressLine1, party.addressLine2)
                .filter { it.isNotBlank() }
                .flatMap { sheet.wrap(it, sheet.regular, DocumentStyle.NOTE_FONT, colW) }
                .take(3)
                .forEach { line ->
                    sheet.text(sheet.regular, DocumentStyle.NOTE_FONT, x, ty, line, DocumentStyle.INK)
                    ty -= DocumentStyle.NOTE_LEAD
                }
            party.nip?.takeIf { it.isNotBlank() }?.let { nip ->
                sheet.text(sheet.bold, DocumentStyle.NOTE_FONT, x, ty, "NIP: $nip", DocumentStyle.INK)
                ty -= DocumentStyle.NOTE_LEAD
            }
            sheet.y = minOf(sheet.y, ty)
        }
    }

    /**
     * Tabela pozycji. Nagłówek powtarza się po łamaniu strony — wiersze kwot bez
     * podpisów kolumn na drugiej kartce są nie do odczytania.
     */
    private fun drawLines(sheet: DocumentSheet, data: InvoicePdfData) {
        sheet.y -= 18f
        drawLinesHeader(sheet)

        if (data.lines.isEmpty()) {
            // Faktura pobrana z KSeF bez treści XML: sumy znamy z metadanych, pozycji nie.
            // Pusta tabela czytałaby się jak faktura bez pozycji, więc piszemy wprost, czego brakuje.
            sheet.y -= 6f
            sheet.text(
                sheet.regular, ROW_FONT, DocumentStyle.LEFT + 3f, sheet.y - ROW_FONT,
                "Pozycje nie są dostępne w systemie — fakturę wystawiono poza CRM, jej treść pozostaje w KSeF.",
                DocumentStyle.MUTED
            )
            sheet.y -= ROW_LEAD + 4f
            sheet.rect(DocumentStyle.LEFT, sheet.y, DocumentStyle.CONTENT_W, 0.4f, Color(0xE2, 0xE8, 0xF0))
            return
        }

        data.lines.forEach { line ->
            val nameLines = sheet.wrap(line.name, sheet.regular, ROW_FONT, COLS[1] - 6f)
            val rowH = maxOf(1, nameLines.size) * ROW_LEAD + 4f
            if (sheet.y - rowH < DocumentStyle.BOTTOM) {
                sheet.newPage()
                drawLinesHeader(sheet)
            }

            val top = sheet.y
            val baseline = top - ROW_FONT - 2f
            var x = DocumentStyle.LEFT

            cell(sheet, x, baseline, COLS[0], line.lineNumber.toString(), right = false)
            x += COLS[0]
            nameLines.forEachIndexed { index, text ->
                sheet.text(sheet.regular, ROW_FONT, x + 2f, baseline - index * ROW_LEAD, text, DocumentStyle.INK)
            }
            x += COLS[1]
            cell(sheet, x, baseline, COLS[2], quantity(line.quantity), right = true)
            x += COLS[2]
            cell(sheet, x, baseline, COLS[3], line.unit.orEmpty(), right = false, pad = 4f)
            x += COLS[3]
            cell(sheet, x, baseline, COLS[4], money(line.unitPriceNet), right = true)
            x += COLS[4]
            cell(sheet, x, baseline, COLS[5], money(line.netValue), right = true)
            x += COLS[5]
            cell(sheet, x, baseline, COLS[6], vatLabel(line.vatRate), right = true)
            x += COLS[6]
            cell(sheet, x, baseline, COLS[7], money(line.vatValue), right = true)
            x += COLS[7]
            cell(sheet, x, baseline, COLS[8], money(line.grossValue), right = true, bold = true)

            sheet.y = top - rowH
            sheet.rect(DocumentStyle.LEFT, sheet.y, DocumentStyle.CONTENT_W, 0.4f, Color(0xE2, 0xE8, 0xF0))
        }
    }

    private fun drawLinesHeader(sheet: DocumentSheet) {
        val labels = listOf("LP", "NAZWA TOWARU LUB USŁUGI", "ILOŚĆ", "J.M.", "CENA NETTO", "WARTOŚĆ NETTO", "VAT", "KWOTA VAT", "WARTOŚĆ BRUTTO")
        val top = sheet.y
        sheet.rect(DocumentStyle.LEFT, top - DocumentStyle.TAB_H, DocumentStyle.CONTENT_W, DocumentStyle.TAB_H, DocumentStyle.NAVY)
        var x = DocumentStyle.LEFT
        labels.forEachIndexed { index, label ->
            val w = COLS[index]
            val baseline = top - DocumentStyle.TAB_H + 4.5f
            if (index == 0 || index == 1 || index == 3) {
                sheet.text(sheet.regular, HEAD_FONT, x + 2f, baseline, label, Color.WHITE)
            } else {
                sheet.textRight(sheet.regular, HEAD_FONT, x + w - 3f, baseline, label, Color.WHITE)
            }
            x += w
        }
        sheet.y = top - DocumentStyle.TAB_H - 3f
    }

    private fun cell(
        sheet: DocumentSheet,
        x: Float,
        baseline: Float,
        width: Float,
        value: String,
        right: Boolean,
        bold: Boolean = false,
        pad: Float = 3f
    ) {
        val font: PDFont = if (bold) sheet.bold else sheet.regular
        if (right) sheet.textRight(font, ROW_FONT, x + width - pad, baseline, value, DocumentStyle.INK)
        else sheet.text(font, ROW_FONT, x + pad, baseline, value, DocumentStyle.INK)
    }

    /**
     * Podsumowanie w podziale na stawki (art. 106e ust. 1 pkt 13-14) i kwota należności
     * ogółem (pkt 15). Stoi po prawej, bo tam wzrok szuka kwot w tabeli wyżej.
     */
    private fun drawVatSummary(sheet: DocumentSheet, data: InvoicePdfData) {
        val blockW = 300f
        val x = DocumentStyle.PAGE_W - DocumentStyle.RIGHT_MARGIN - blockW
        val rows = data.vatBuckets.size + 1
        sheet.ensure(DocumentStyle.TAB_H + rows * ROW_LEAD + 34f)
        sheet.y -= 14f

        val top = sheet.y
        sheet.tab(x, top, "PODSUMOWANIE WEDŁUG STAWEK", blockW)
        var ty = top - DocumentStyle.TAB_H - 11f

        val colRate = x + 60f
        val colNet = x + 145f
        val colVat = x + 220f
        val colGross = x + blockW - 3f

        sheet.text(sheet.regular, HEAD_FONT, x + 3f, ty, "STAWKA", DocumentStyle.MUTED)
        sheet.textRight(sheet.regular, HEAD_FONT, colNet, ty, "NETTO", DocumentStyle.MUTED)
        sheet.textRight(sheet.regular, HEAD_FONT, colVat, ty, "VAT", DocumentStyle.MUTED)
        sheet.textRight(sheet.regular, HEAD_FONT, colGross, ty, "BRUTTO", DocumentStyle.MUTED)
        ty -= ROW_LEAD

        data.vatBuckets.forEach { bucket ->
            sheet.text(sheet.regular, ROW_FONT, x + 3f, ty, vatLabel(bucket.vatRate), DocumentStyle.INK)
            sheet.textRight(sheet.regular, ROW_FONT, colNet, ty, money(bucket.net), DocumentStyle.INK)
            sheet.textRight(sheet.regular, ROW_FONT, colVat, ty, money(bucket.vat), DocumentStyle.INK)
            sheet.textRight(sheet.regular, ROW_FONT, colGross, ty, money(bucket.gross), DocumentStyle.INK)
            ty -= ROW_LEAD
        }

        sheet.rect(x, ty + ROW_LEAD - 3f, blockW, 0.6f, DocumentStyle.NAVY)
        ty -= 2f
        sheet.text(sheet.bold, ROW_FONT, x + 3f, ty, "RAZEM", DocumentStyle.INK)
        sheet.textRight(sheet.bold, ROW_FONT, colNet, ty, money(data.totalNet), DocumentStyle.INK)
        sheet.textRight(sheet.bold, ROW_FONT, colVat, ty, money(data.totalVat), DocumentStyle.INK)
        sheet.textRight(sheet.bold, ROW_FONT, colGross, ty, money(data.totalGross), DocumentStyle.INK)
        ty -= 16f

        // Kwota należności ogółem: jedyna liczba na dokumencie, po którą wraca się bez
        // czytania reszty, więc jako jedyna dostaje własne szare pole i większy stopień.
        val boxH = 24f
        sheet.rect(x, ty - boxH + 10f, blockW, boxH, DocumentStyle.GRAY)
        sheet.text(sheet.bold, DocumentStyle.NOTE_FONT, x + 6f, ty - 2f, "DO ZAPŁATY", DocumentStyle.INK)
        sheet.textRight(
            sheet.bold, 13f, colGross, ty - 3f,
            "${money(data.totalGross)} ${data.currency}", DocumentStyle.NAVY
        )
        sheet.y = ty - boxH + 6f

        sheet.ensure(DocumentStyle.NOTE_LEAD * 2)
        sheet.y -= 4f
        sheet.wrap(
            "Słownie: ${data.totalInWords}", sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W
        ).forEach { line ->
            sheet.text(
                sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                sheet.y - DocumentStyle.NOTE_FONT, line, DocumentStyle.INK
            )
            sheet.y -= DocumentStyle.NOTE_LEAD
        }
    }

    private fun drawPayment(sheet: DocumentSheet, data: InvoicePdfData) {
        val rows = listOfNotNull(
            data.paymentFormLabel?.let { "Forma płatności: $it" },
            data.paymentDueDate?.let { "Termin płatności: $it" },
            data.paymentStatusLabel?.let { "Status: $it" },
            data.bankAccount?.takeIf { it.isNotBlank() }?.let { "Rachunek do wpłaty: $it" }
        )
        if (rows.isEmpty()) return

        sheet.ensure(DocumentStyle.TAB_H + rows.size * DocumentStyle.NOTE_LEAD + 16f)
        sheet.y -= 15.85f
        sheet.tab(DocumentStyle.LEFT, sheet.y, "PŁATNOŚĆ")
        sheet.y -= DocumentStyle.TAB_H + 9f
        rows.forEach { row ->
            sheet.text(
                sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                sheet.y - DocumentStyle.NOTE_FONT, row, DocumentStyle.INK
            )
            sheet.y -= DocumentStyle.NOTE_LEAD
        }
    }

    /**
     * Blok KSeF: numer nadany fakturze i kod QR prowadzący do weryfikacji u MF.
     *
     * Od 2026 r. wizualizacja udostępniana poza systemem musi nieść ten kod — bez niego
     * odbiorca nie ma jak sprawdzić, czy PDF odpowiada fakturze w KSeF. Gdy faktura nie
     * ma jeszcze numeru, drukujemy to wprost: dokument bez numeru KSeF nie jest jeszcze
     * fakturą w rozumieniu przepisów i milczenie o tym wprowadzałoby w błąd.
     */
    private fun drawKsefBlock(sheet: DocumentSheet, data: InvoicePdfData) {
        val qrSide = 64f
        val blockH = maxOf(qrSide, DocumentStyle.TAB_H + 30f)
        sheet.ensure(blockH + 26f)
        sheet.y -= 18f

        val top = sheet.y
        val textX = if (data.verificationQrPng != null) DocumentStyle.LEFT + qrSide + 12f else DocumentStyle.LEFT

        data.verificationQrPng?.let { png ->
            sheet.imageFitted(
                png, "ksef-qr", DocumentStyle.LEFT, top - qrSide, qrSide, qrSide, alignLeft = true
            )
        }

        var ty = top - 9f
        sheet.text(sheet.bold, DocumentStyle.NOTE_FONT, textX, ty, "KRAJOWY SYSTEM E-FAKTUR", DocumentStyle.NAVY)
        ty -= DocumentStyle.NOTE_LEAD + 1f

        data.ksefNumber?.takeIf { it.isNotBlank() }?.let { number ->
            sheet.wrap("Numer KSeF: $number", sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W - (textX - DocumentStyle.LEFT))
                .forEach { line ->
                    sheet.text(sheet.regular, DocumentStyle.NOTE_FONT, textX, ty, line, DocumentStyle.INK)
                    ty -= DocumentStyle.NOTE_LEAD
                }
        }
        data.draftNotice?.let { notice ->
            sheet.wrap(notice, sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W - (textX - DocumentStyle.LEFT))
                .forEach { line ->
                    sheet.text(sheet.regular, DocumentStyle.NOTE_FONT, textX, ty, line, DocumentStyle.INK)
                    ty -= DocumentStyle.NOTE_LEAD
                }
        }
        if (data.verificationQrPng != null) {
            sheet.text(
                sheet.regular, DocumentStyle.NOTE_FONT, textX, ty,
                "Zeskanuj kod, aby zweryfikować dokument w KSeF.", DocumentStyle.MUTED
            )
            ty -= DocumentStyle.NOTE_LEAD
        }

        sheet.y = minOf(top - blockH, ty) - 10f

        data.contactLine?.let { contact ->
            sheet.ensure(DocumentStyle.NOTE_LEAD * 2)
            sheet.text(
                sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                sheet.y - DocumentStyle.NOTE_FONT,
                sheet.ellipsize(contact, sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W),
                DocumentStyle.MUTED
            )
            sheet.y -= DocumentStyle.NOTE_LEAD
        }
    }

    // ── Formatowanie ──────────────────────────────────────────────────────────

    private fun money(grosze: Long): String {
        val sign = if (grosze < 0) "-" else ""
        val absolute = Math.abs(grosze)
        val zlote = absolute / 100
        val reszta = absolute % 100
        val grouped = zlote.toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$sign$grouped,${"%02d".format(reszta)}"
    }

    private fun quantity(value: BigDecimal): String =
        value.stripTrailingZeros().let { stripped ->
            if (stripped.scale() <= 0) stripped.toBigInteger().toString()
            else stripped.setScale(minOf(3, stripped.scale()), RoundingMode.HALF_UP).toPlainString().replace('.', ',')
        }

    private fun vatLabel(code: String): String {
        val normalized = code.trim()
        return when {
            normalized.equals("zw", ignoreCase = true) -> "zw."
            normalized.startsWith("0") -> "0%"
            else -> "$normalized%"
        }
    }
}
