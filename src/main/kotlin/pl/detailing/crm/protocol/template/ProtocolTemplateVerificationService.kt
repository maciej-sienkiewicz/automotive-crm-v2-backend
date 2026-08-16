package pl.detailing.crm.protocol.template

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat

/**
 * Verifies that an uploaded protocol template file contains every field the visit
 * pipeline needs before the template is allowed into circulation.
 *
 * - PDF: the file must be a parseable PDF with an AcroForm whose field names cover
 *   [requiredFieldNames]; the customer signature field must additionally have a
 *   widget with a usable rectangle (SignedDocumentComposer scales the tablet
 *   signature into that rectangle).
 * - HTML: the file must contain a `data-field="<name>"` placeholder for every
 *   required field (this is the convention used by the bundled HTML template).
 *
 * Field-name matching is case-insensitive so templates authored in Acrobat/
 * LibreOffice with different casing still verify.
 */
@Service
class ProtocolTemplateVerificationService {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CUSTOMER_SIGNATURE_FIELD = "signature"
        const val COMPANY_SIGNATURE_FIELD = "company_signature"

        /**
         * Minimum usable signature widget size (PDF points). Anything smaller and the
         * scaled tablet signature becomes an unreadable smudge.
         */
        const val MIN_SIGNATURE_BOX_WIDTH_PT = 60f
        const val MIN_SIGNATURE_BOX_HEIGHT_PT = 20f

        private val DATA_FIELD_REGEX = Regex("""data-field\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /** Fields the auto-fill pipeline maps by default — all must exist in the file. */
        fun requiredFieldNames(): List<String> =
            (DefaultProtocolFieldMappings.getDefaultMappings().keys -
                DefaultProtocolFieldMappings.getSupplementaryMappings().keys).toList() +
                CUSTOMER_SIGNATURE_FIELD

        /** Fields that improve the document but do not block verification. */
        fun optionalFieldNames(): List<String> =
            DefaultProtocolFieldMappings.getSupplementaryMappings().keys.toList() + COMPANY_SIGNATURE_FIELD
    }

    data class VerificationResult(
        val requiredFields: List<String>,
        val foundFields: List<String>,
        val missingFields: List<String>,
        val optionalFieldsFound: List<String>,
        val problems: List<String>
    ) {
        val isValid: Boolean get() = missingFields.isEmpty() && problems.isEmpty()
    }

    fun verify(fileBytes: ByteArray, format: ProtocolTemplateFormat): VerificationResult =
        when (format) {
            ProtocolTemplateFormat.PDF -> verifyPdf(fileBytes)
            ProtocolTemplateFormat.HTML -> verifyHtml(fileBytes)
        }

    // ─── PDF ──────────────────────────────────────────────────────────────────

    private fun verifyPdf(fileBytes: ByteArray): VerificationResult {
        val required = requiredFieldNames()
        val problems = mutableListOf<String>()

        val document = try {
            Loader.loadPDF(fileBytes)
        } catch (e: Exception) {
            logger.warn("Template verification: PDF unparseable: ${e.message}")
            return VerificationResult(
                requiredFields = required,
                foundFields = emptyList(),
                missingFields = required,
                optionalFieldsFound = emptyList(),
                problems = listOf("Plik nie jest poprawnym dokumentem PDF (${e.message})")
            )
        }

        document.use { doc ->
            val acroForm = doc.documentCatalog.getAcroForm(null)
                ?: return VerificationResult(
                    requiredFields = required,
                    foundFields = emptyList(),
                    missingFields = required,
                    optionalFieldsFound = emptyList(),
                    problems = listOf(
                        "PDF nie zawiera formularza (AcroForm). Dodaj pola formularza " +
                            "w programie typu Adobe Acrobat lub LibreOffice Writer."
                    )
                )

            val presentNames = acroForm.fieldTree.mapNotNull { it.fullyQualifiedName }.toList()
            val presentLower = presentNames.map { it.lowercase() }.toSet()

            val found = required.filter { it.lowercase() in presentLower }
            val missing = required.filterNot { it.lowercase() in presentLower }
            val optionalFound = optionalFieldNames().filter { it.lowercase() in presentLower }

            // The customer signature field must carry a widget rectangle big enough
            // to stamp a legible, proportionally scaled signature into.
            if (CUSTOMER_SIGNATURE_FIELD.lowercase() in presentLower) {
                val signatureField = acroForm.fieldTree
                    .filterIsInstance<PDTerminalField>()
                    .firstOrNull { it.fullyQualifiedName.equals(CUSTOMER_SIGNATURE_FIELD, ignoreCase = true) }
                val rect = signatureField?.widgets?.firstOrNull()?.rectangle
                when {
                    rect == null ->
                        problems += "Pole '$CUSTOMER_SIGNATURE_FIELD' nie ma widocznego obszaru na stronie " +
                            "(brak widgetu) — podpis nie miałby gdzie zostać naniesiony."
                    rect.width < MIN_SIGNATURE_BOX_WIDTH_PT || rect.height < MIN_SIGNATURE_BOX_HEIGHT_PT ->
                        problems += "Obszar pola '$CUSTOMER_SIGNATURE_FIELD' jest za mały " +
                            "(${"%.0f".format(rect.width)}x${"%.0f".format(rect.height)}pt; " +
                            "minimum ${MIN_SIGNATURE_BOX_WIDTH_PT.toInt()}x${MIN_SIGNATURE_BOX_HEIGHT_PT.toInt()}pt) — " +
                            "podpis po przeskalowaniu byłby nieczytelny."
                }
            }

            return VerificationResult(
                requiredFields = required,
                foundFields = found,
                missingFields = missing,
                optionalFieldsFound = optionalFound,
                problems = problems
            )
        }
    }

    // ─── HTML ─────────────────────────────────────────────────────────────────

    private fun verifyHtml(fileBytes: ByteArray): VerificationResult {
        val required = requiredFieldNames()
        val html = try {
            String(fileBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return VerificationResult(
                requiredFields = required,
                foundFields = emptyList(),
                missingFields = required,
                optionalFieldsFound = emptyList(),
                problems = listOf("Nie można odczytać pliku jako tekstu UTF-8")
            )
        }

        if (!html.contains("<html", ignoreCase = true) && !html.contains("<!doctype", ignoreCase = true)) {
            return VerificationResult(
                requiredFields = required,
                foundFields = emptyList(),
                missingFields = required,
                optionalFieldsFound = emptyList(),
                problems = listOf("Plik nie wygląda na dokument HTML (brak znacznika <html> / <!doctype>)")
            )
        }

        val presentLower = DATA_FIELD_REGEX.findAll(html)
            .map { it.groupValues[1].trim().lowercase() }
            .toSet()

        val found = required.filter { it.lowercase() in presentLower }
        val missing = required.filterNot { it.lowercase() in presentLower }
        val optionalFound = optionalFieldNames().filter { it.lowercase() in presentLower }

        return VerificationResult(
            requiredFields = required,
            foundFields = found,
            missingFields = missing,
            optionalFieldsFound = optionalFound,
            problems = emptyList()
        )
    }
}
