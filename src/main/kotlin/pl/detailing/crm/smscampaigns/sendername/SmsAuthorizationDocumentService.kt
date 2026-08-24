package pl.detailing.crm.smscampaigns.sendername

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.QualifiedSealService
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Buduje i podpisuje upoważnienie dla operatora SMS.
 *
 * Operator wymaga oświadczenia właściciela nazwy, że zgadza się na używanie jej w polu
 * nadawcy przez firmę, która faktycznie wysyła SMS-y (klienta SMSAPI). Wcześniej trzeba
 * było pobrać wzór w Wordzie, wydrukować, podpisać, zeskanować i wgrać — cztery kroki
 * poza systemem przy dokumencie, którego całą treść system i tak zna.
 *
 * Dokument powstaje z wbudowanego szablonu AcroForm: dane właściciela nazwy wchodzą
 * z ustawień firmy studia, pole nadawcy z konfiguracji SMS, data jest bieżąca, a dane
 * odbiorcy upoważnienia są stałe (to zawsze ten sam podmiot wysyłający).
 *
 * Podpis jest stemplowany w miejsce pola „signature", całość spłaszczona i — jeśli
 * pieczęć jest skonfigurowana w środowisku — opieczętowana tak samo jak dokumenty wizyt.
 * Świadomie nie przechodzi przez SignedDocumentComposer: tam każdy podpis wisi na wizycie
 * i ma własną kartę podpisu z audytem; tutaj studio podpisuje własne oświadczenie dla
 * operatora, bez wizyty i bez klienta po drugiej stronie.
 */
@Service
class SmsAuthorizationDocumentService(
    private val pdfProcessingService: PdfProcessingService,
    private val signatureImageProcessor: SignatureImageProcessor,
    private val qualifiedSealService: QualifiedSealService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TEMPLATE_RESOURCE = "/templates/upowaznienie_nadawcy_sms_default.pdf"
        private const val SIGNATURE_FIELD_NAME = "signature"

        /** Wewnętrzny margines pola podpisu, żeby kreska nie dotykała ramki. */
        private const val SIGNATURE_BOX_PADDING = 3f

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    /**
     * Zwraca gotowy, podpisany PDF upoważnienia.
     *
     * @param settings ustawienia firmy studia — właściciela nazwy
     * @param senderName pole nadawcy, którego dotyczy zgoda
     * @param signaturePngBase64 podpis narysowany na ekranie (PNG, base64 bez prefiksu)
     * @param today data wystawienia (bieżąca data w strefie studia)
     */
    fun buildSignedAuthorization(
        settings: StudioSettingsEntity?,
        senderName: String,
        signaturePngBase64: String,
        today: LocalDate
    ): ByteArray {
        val signaturePng = decodeSignature(signaturePngBase64)

        val filled = pdfProcessingService.fillFormInMemory(
            loadTemplateBytes(),
            fieldValues(settings, senderName, today)
        )

        val signed = stampSignature(filled, signaturePng)

        // Brak skonfigurowanej pieczęci nie może blokować konfiguracji nadawcy —
        // serwis zwraca wtedy dokument bez pieczęci i sam to loguje.
        val sealResult = qualifiedSealService.seal(signed)
        logger.info(
            "SMS authorization document built: {}B, sealApplied={}",
            sealResult.pdfBytes.size, sealResult.sealApplied
        )
        return sealResult.pdfBytes
    }

    private fun decodeSignature(base64: String): ByteArray {
        val raw = try {
            java.util.Base64.getDecoder().decode(base64.substringAfter("base64,"))
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Nieprawidłowy format podpisu")
        }
        return signatureImageProcessor.normalizeToTransparentPng(raw)
    }

    private fun loadTemplateBytes(): ByteArray =
        javaClass.getResourceAsStream(TEMPLATE_RESOURCE)?.use { it.readBytes() }
            ?: throw IllegalStateException("Brak wbudowanego szablonu upoważnienia: $TEMPLATE_RESOURCE")

    /** Dane właściciela nazwy — z ustawień firmy; pusta wartość zostaje pustym polem. */
    private fun fieldValues(
        settings: StudioSettingsEntity?,
        senderName: String,
        today: LocalDate
    ): Map<String, String> {
        val companyName = settings?.name.orEmpty().trim()
        val postalCity = listOfNotNull(
            settings?.postalCode?.trim()?.takeIf { it.isNotBlank() },
            settings?.city?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" ")

        return mapOf(
            "city" to settings?.city.orEmpty().trim(),
            "date" to today.format(DATE_FORMAT),
            "ownername" to companyName,
            "owneraddress" to settings?.street.orEmpty().trim(),
            "ownerpostalcity" to postalCity,
            "ownernip" to settings?.taxId.orEmpty().trim(),
            // Ta sama nazwa firmy pada drugi raz w zdaniu oświadczenia.
            "ownernameinline" to companyName,
            "sendername" to senderName
        )
    }

    /** Wstawia podpis w prostokąt pola „signature" i spłaszcza formularz. */
    private fun stampSignature(pdfBytes: ByteArray, signaturePng: ByteArray): ByteArray =
        Loader.loadPDF(pdfBytes).use { document ->
            val acroForm = document.documentCatalog.acroForm
                ?: throw IllegalStateException("Szablon upoważnienia nie zawiera formularza")
            val field = acroForm.getField(SIGNATURE_FIELD_NAME) as? PDTerminalField
                ?: throw IllegalStateException("Szablon upoważnienia nie zawiera pola podpisu")
            val widget = field.widgets.firstOrNull()
                ?: throw IllegalStateException("Pole podpisu w szablonie nie ma widgetu")
            val rect = widget.rectangle
            val page: PDPage = widget.page ?: document.getPage(0)

            val image = PDImageXObject.createFromByteArray(document, signaturePng, SIGNATURE_FIELD_NAME)
            val availableWidth = (rect.width - 2 * SIGNATURE_BOX_PADDING).coerceAtLeast(1f)
            val availableHeight = (rect.height - 2 * SIGNATURE_BOX_PADDING).coerceAtLeast(1f)
            val scale = minOf(availableWidth / image.width, availableHeight / image.height)
            val drawWidth = image.width * scale
            val drawHeight = image.height * scale

            PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { stream ->
                stream.drawImage(
                    image,
                    rect.lowerLeftX + (rect.width - drawWidth) / 2,
                    rect.lowerLeftY + (rect.height - drawHeight) / 2,
                    drawWidth,
                    drawHeight
                )
            }

            // Podpisany dokument nie ma już nic do wypełnienia — pola znikają, treść zostaje.
            acroForm.flatten()

            ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
}
