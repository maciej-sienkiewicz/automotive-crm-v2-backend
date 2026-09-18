package pl.detailing.crm.visit.certificate

import org.apache.pdfbox.pdmodel.PDDocument
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.pdf.DocumentSheet
import pl.detailing.crm.shared.pdf.DocumentStyle
import java.awt.Color

/**
 * Certyfikat jakości — dokument dla klienta po wydaniu pojazdu.
 *
 * Dlaczego nie szablon w „Dokumentach i podpisach": certyfikat nie jest dokumentem do
 * podpisu klienta i nie przewidujemy podmiany jego wyglądu, więc poszedłby przez
 * maszynerię mapowań pól i weryfikacji szablonu, która na jednej ustalonej formie tylko
 * przeszkadza. Dlatego układ jest w kodzie, a nie w bazie.
 *
 * Siatka, barwy i prymitywy pochodzą z [DocumentSheet] — tego samego, którym rysuje się
 * wizualizacja faktury. Dokumenty CRM mają być jedną rodziną przez konstrukcję, a nie
 * przez skopiowany układ, który rozjedzie się przy pierwszej poprawce.
 *
 * Na certyfikacie NIE MA kwot — jest podziękowaniem, nie rozliczeniem. To świadome:
 * dokument, który nie niesie ceny, nie ma jak jej przekłamać (CLAUDE.md §1).
 */
@Service
class QualityCertificatePdfRenderer {

    fun render(data: QualityCertificateData): ByteArray = PDDocument().use { doc ->
        with(DocumentSheet(doc)) {
            header(data.logoPng, data.providerName, data.providerAddress)
            title("CERTYFIKAT JAKOŚCI")
            metaRow(
                listOf(
                    "NR WIZYTY" to data.visitNumber,
                    "POJAZD" to data.vehicle,
                    "DATA WYDANIA" to data.completedOn,
                    "KLIENT" to data.customerName
                )
            )

            drawLead(this, data)

            drawList(
                this,
                label = "ZAKRES WYKONANYCH PRAC",
                items = data.services.map { ListEntry(it, null) },
                emptyText = "Nie wskazano wykonanych prac."
            )
            drawList(
                this,
                // „Materiały", nie „preparaty": to drugie niesie skojarzenie z apteką
                // i chemią i obniża ton dokumentu, który ma brzmieć rzemieślniczo.
                label = "UŻYTE MATERIAŁY",
                items = data.usedProducts.map { ListEntry(it.title, it.note) },
                emptyText = "Nie wskazano użytych materiałów.",
                // Deklaracja stoi NAD wykazem: najpierw zobowiązanie, potem dowód.
                intro = data.productDeclaration
            )
            drawList(
                this,
                label = "ZALECANE DO DALSZEJ PIELĘGNACJI",
                items = data.recommendedProducts.map { ListEntry(it.title, it.note) },
                emptyText = null
            )
            drawCare(this, data)
            drawSignature(this, data)
            finish()
        }
    }

    private fun drawLead(sheet: DocumentSheet, data: QualityCertificateData) {
        sheet.y -= 18f
        data.thankYou.forEachIndexed { index, paragraph ->
            if (index > 0) sheet.y -= 5f
            sheet.wrap(paragraph, sheet.regular, DocumentStyle.LEAD_FONT, DocumentStyle.CONTENT_W).forEach { line ->
                sheet.ensure(DocumentStyle.LEAD_LEAD)
                sheet.text(
                    sheet.regular, DocumentStyle.LEAD_FONT, DocumentStyle.LEFT,
                    sheet.y - DocumentStyle.LEAD_FONT, line, DocumentStyle.INK
                )
                sheet.y -= DocumentStyle.LEAD_LEAD
            }
        }
    }

    private class ListEntry(val title: String, val note: String?)

    /**
     * Sekcja listowa: granatowa belka, pod nią pozycje z kropką i opcjonalną notatką.
     *
     * `emptyText == null` oznacza sekcję, która przy pustej liście w ogóle się nie rysuje —
     * tak jest z zaleceniami, bo pusta rubryka „polecamy" wygląda jak niedokończony dokument.
     */
    private fun drawList(
        sheet: DocumentSheet,
        label: String,
        items: List<ListEntry>,
        emptyText: String?,
        /** Blok wprowadzający między belką a listą — dziś tylko deklaracja autentyczności. */
        intro: String? = null
    ) {
        if (items.isEmpty() && emptyText == null) return

        sheet.ensure(DocumentStyle.TAB_H + 24f)
        sheet.y -= 15.85f
        sheet.tab(DocumentStyle.LEFT, sheet.y, label)
        sheet.y -= DocumentStyle.TAB_H + 8f

        if (intro != null && items.isNotEmpty()) drawDeclaration(sheet, intro)

        if (items.isEmpty()) {
            sheet.ensure(DocumentStyle.BODY_LEAD)
            sheet.text(
                sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                sheet.y - DocumentStyle.NOTE_FONT, emptyText!!, DocumentStyle.MUTED
            )
            sheet.y -= DocumentStyle.NOTE_LEAD
            return
        }

        val bulletIndent = 12f
        items.forEach { item ->
            sheet.wrap(item.title, sheet.bold, DocumentStyle.BODY, DocumentStyle.CONTENT_W - bulletIndent)
                .forEachIndexed { index, line ->
                    sheet.ensure(DocumentStyle.BODY_LEAD)
                    if (index == 0) {
                        // Kropka listy jako mały kwadrat: znak „•" bywa nieobecny
                        // w subsecie fontu, kwadrat nigdy nie zawiedzie.
                        sheet.rect(
                            DocumentStyle.LEFT + 1.5f, sheet.y - DocumentStyle.BODY + 1.5f,
                            3f, 3f, DocumentStyle.NAVY
                        )
                    }
                    sheet.text(
                        sheet.bold, DocumentStyle.BODY, DocumentStyle.LEFT + bulletIndent,
                        sheet.y - DocumentStyle.BODY, line, DocumentStyle.INK
                    )
                    sheet.y -= DocumentStyle.BODY_LEAD
                }
            item.note?.takeIf { it.isNotBlank() }?.let { note ->
                sheet.wrap(note, sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W - bulletIndent)
                    .forEach { line ->
                        sheet.ensure(DocumentStyle.NOTE_LEAD)
                        sheet.text(
                            sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT + bulletIndent,
                            sheet.y - DocumentStyle.NOTE_FONT, line, DocumentStyle.MUTED
                        )
                        sheet.y -= DocumentStyle.NOTE_LEAD
                    }
            }
            sheet.y -= 3f
        }
    }

    /**
     * Deklaracja autentyczności — jedyny blok na certyfikacie leżący na własnej powierzchni.
     *
     * Szare pole jest w tym systemie wizualnym nośnikiem treści WPISANEJ (pola formularza
     * w protokołach), więc deklaracja czyta się jak zobowiązanie, a nie jak kolejny akapit.
     * Drugiego takiego bloku na certyfikacie nie ma i nie powinno być: dwie wyróżnione
     * powierzchnie znaczą tyle samo co żadna.
     */
    private fun drawDeclaration(sheet: DocumentSheet, text: String) {
        val pad = 8f
        val lines = sheet.wrap(text, sheet.bold, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W - 2 * pad)
        val boxH = lines.size * DocumentStyle.NOTE_LEAD + 2 * pad - (DocumentStyle.NOTE_LEAD - DocumentStyle.NOTE_FONT)

        sheet.ensure(boxH + 14f)
        sheet.rect(DocumentStyle.LEFT, sheet.y - boxH, DocumentStyle.CONTENT_W, boxH, DocumentStyle.GRAY)

        var ty = sheet.y - pad - DocumentStyle.NOTE_FONT
        lines.forEach { line ->
            sheet.text(sheet.bold, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT + pad, ty, line, Color.BLACK)
            ty -= DocumentStyle.NOTE_LEAD
        }
        sheet.y -= boxH + 10f
    }

    /**
     * Pielęgnacja po wizycie.
     *
     * Najczęstsze pytanie klienta po odbiorze auta, więc ma własną sekcję zamiast zdania
     * w podziękowaniu. Reguły pochodzą ze słownika studia; to, co zależy od konkretnej
     * pracy (terminy utwardzania powłoki), dopisuje pracownik przy generowaniu.
     */
    private fun drawCare(sheet: DocumentSheet, data: QualityCertificateData) {
        if (data.careRules.isEmpty() && data.careNote.isNullOrBlank()) return

        sheet.ensure(DocumentStyle.TAB_H + 24f)
        sheet.y -= 15.85f
        sheet.tab(DocumentStyle.LEFT, sheet.y, "JAK UTRZYMAĆ EFEKT")
        sheet.y -= DocumentStyle.TAB_H + 8f

        val indent = 12f
        data.careRules.forEach { rule ->
            sheet.wrap(rule, sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W - indent)
                .forEachIndexed { index, line ->
                    sheet.ensure(DocumentStyle.NOTE_LEAD)
                    if (index == 0) {
                        sheet.rect(
                            DocumentStyle.LEFT + 1.5f, sheet.y - DocumentStyle.NOTE_FONT + 1.5f,
                            3f, 3f, DocumentStyle.NAVY
                        )
                    }
                    sheet.text(
                        sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT + indent,
                        sheet.y - DocumentStyle.NOTE_FONT, line, DocumentStyle.INK
                    )
                    sheet.y -= DocumentStyle.NOTE_LEAD
                }
            sheet.y -= 3f
        }

        data.careNote?.takeIf { it.isNotBlank() }?.let { note ->
            sheet.y -= 4f
            sheet.ensure(DocumentStyle.BODY_LEAD)
            sheet.text(
                sheet.bold, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                sheet.y - DocumentStyle.NOTE_FONT, "Zalecenia dla tej realizacji", DocumentStyle.INK
            )
            sheet.y -= DocumentStyle.NOTE_LEAD + 1f
            sheet.wrap(note, sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W).forEach { line ->
                sheet.ensure(DocumentStyle.NOTE_LEAD)
                sheet.text(
                    sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT,
                    sheet.y - DocumentStyle.NOTE_FONT, line, DocumentStyle.INK
                )
                sheet.y -= DocumentStyle.NOTE_LEAD
            }
        }
    }

    /**
     * Podpis wykonawcy: obraz podpisu zalogowanego użytkownika w szarym polu, pod nim
     * imię i nazwisko oraz data wystawienia.
     *
     * Blok jest niepodzielny — gdy nie mieści się na stronie, idzie w całości na następną.
     * Podpis oderwany od nazwiska na osobnej kartce nie jest podpisem.
     */
    private fun drawSignature(sheet: DocumentSheet, data: QualityCertificateData) {
        val boxW = 200f
        val boxH = 48f
        val blockH = DocumentStyle.TAB_H + 2.30f + boxH + 20f
        sheet.ensure(blockH + 22f)
        // Podpis jest stopką dokumentu, nie kolejnym akapitem: gdy na stronie zostało
        // miejsce, blok zjeżdża na dół kartki — tak samo jak w protokołach.
        sheet.y = minOf(sheet.y - 22f, DocumentStyle.BOTTOM + blockH)

        val x = DocumentStyle.PAGE_W - DocumentStyle.RIGHT_MARGIN - boxW
        // „Za jakość odpowiada osobiście" zamiast „podpis wykonawcy": nazwisko związane
        // z pracą jest sygnałem, którego nie da się podrobić ani zastąpić przymiotnikiem.
        sheet.tab(x, sheet.y, "ZA JAKOŚĆ ODPOWIADA OSOBIŚCIE", boxW)
        val boxTop = sheet.y - DocumentStyle.TAB_H - 2.30f
        sheet.rect(x, boxTop - boxH, boxW, boxH, DocumentStyle.GRAY)

        data.signaturePng?.let { png ->
            val pad = 6f
            sheet.imageFitted(
                png, "user-signature", x + pad, boxTop - boxH + pad,
                boxW - 2 * pad, boxH - 2 * pad, alignLeft = false
            )
        }

        val captionY = boxTop - boxH - 10f
        val caption = listOf(data.issuedByName, data.issuedOn).filter { it.isNotBlank() }.joinToString(", ")
        sheet.text(
            sheet.regular, DocumentStyle.NOTE_FONT, x, captionY,
            sheet.ellipsize(caption, sheet.regular, DocumentStyle.NOTE_FONT, boxW), DocumentStyle.MUTED
        )

        // Kontakt naprzeciw podpisu: certyfikat zostaje u klienta i bywa jedyną kartką,
        // na której ma numer do studia.
        data.contactLine?.let { contact ->
            sheet.text(sheet.bold, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT, boxTop - 2f, data.providerName, DocumentStyle.INK)
            sheet.text(
                sheet.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT, boxTop - 2f - DocumentStyle.NOTE_LEAD,
                sheet.ellipsize(contact, sheet.regular, DocumentStyle.NOTE_FONT, x - DocumentStyle.LEFT - 16f),
                DocumentStyle.MUTED
            )
        }
        sheet.y = captionY - 6f
    }
}
