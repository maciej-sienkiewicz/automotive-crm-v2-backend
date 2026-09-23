package pl.detailing.crm.worktime.attendance

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Wtapia podpis w gotowy arkusz obecności.
 *
 * Dwie drogi podpisu:
 * - na TYM urządzeniu, w oknie zatwierdzania ([sign]) - podpisuje osoba zalogowana
 *   w CRM-ie, w tej samej sesji, więc dowód „kto i kiedy" niesie już wiersz w bazie,
 *   a rysunek jest tylko widocznym potwierdzeniem na wydruku;
 * - na tablecie studia albo własnym telefonie ([signNormalized]) - wtedy podpis
 *   przechodzi przez tor żądań podpisu (jednorazowy token, skrót wyświetlonego
 *   dokumentu, ślad audytowy) i arkusz dostaje na końcu kartę podpisu z tym śladem.
 *
 * Sam bitmap podpisu przechodzi przez [SignatureImageProcessor]: kanał alfa jest
 * wymuszany po stronie serwera (klientowi się nie ufa), obraz jest przycinany do
 * samych pociągnięć i nigdzie nie jest zapisywany poza gotowym PDF-em.
 */
@Service
class AttendanceSheetSigner(
    private val signatureImageProcessor: SignatureImageProcessor
) {
    companion object {
        private val TIMESTAMP = DateTimeFormatter
            .ofPattern("dd.MM.yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
            .withZone(ZoneId.of("Europe/Warsaw"))
    }

    /**
     * Zwraca nowy plik PDF z podpisem na OSTATNIEJ stronie.
     *
     * Ostatnia strona, bo to na niej kończy się zestawienie — podpis pod tabelą, która
     * dopiero się zaczyna, niczego nie potwierdza.
     */
    fun sign(pdfBytes: ByteArray, signaturePng: ByteArray, signerName: String, signedAt: Instant): ByteArray =
        signNormalized(pdfBytes, signatureImageProcessor.normalizeToTransparentPng(signaturePng), signerName, signedAt)

    /**
     * Jak [sign], dla podpisu już znormalizowanego (tor tabletu i linku z SMS-a normalizuje
     * go sam, przed weryfikacją). [appendPages] dokłada strony na końcu dokumentu - tam
     * trafia karta podpisu.
     */
    fun signNormalized(
        pdfBytes: ByteArray,
        normalizedSignaturePng: ByteArray,
        signerName: String,
        signedAt: Instant,
        appendPages: (PDDocument) -> Unit = {}
    ): ByteArray =
        Loader.loadPDF(pdfBytes).use { document ->
            if (document.numberOfPages == 0) throw ValidationException("Arkusz nie ma żadnej strony")
            val page = document.getPage(document.numberOfPages - 1)

            val font = PDType0Font.load(
                document,
                AttendanceSheetSigner::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!,
                true
            )
            val image = PDImageXObject.createFromByteArray(document, normalizedSignaturePng, "signature")

            PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                // Podpis skalowany „contain" w wyznaczone pole: rozjechany albo rozciągnięty
                // podpis wygląda na przerobiony, a to dokument, który ma budzić zaufanie.
                val boxW = GenerateAttendanceSheetHandler.SIGNATURE_BOX_WIDTH
                val boxH = GenerateAttendanceSheetHandler.SIGNATURE_BOX_HEIGHT
                val scale = minOf(boxW / image.width, boxH / image.height)
                val drawW = image.width * scale
                val drawH = image.height * scale
                cs.drawImage(
                    image,
                    GenerateAttendanceSheetHandler.SIGNATURE_BOX_X + (boxW - drawW) / 2f,
                    GenerateAttendanceSheetHandler.SIGNATURE_BOX_Y,
                    drawW,
                    drawH
                )

                // Kto i kiedy — pod linią podpisu, drobnym drukiem, żeby sam rysunek
                // nie musiał być czytelny jako nazwisko.
                cs.setNonStrokingColor(0.35f, 0.35f, 0.35f)
                cs.beginText()
                cs.setFont(font, 7f)
                cs.newLineAtOffset(
                    GenerateAttendanceSheetHandler.SIGNATURE_BOX_X,
                    GenerateAttendanceSheetHandler.FOOTER_LINE_Y - 12f
                )
                cs.showText("$signerName · ${TIMESTAMP.format(signedAt)}")
                cs.endText()
            }

            appendPages(document)

            val output = ByteArrayOutputStream()
            document.save(output)
            output.toByteArray()
        }
}
