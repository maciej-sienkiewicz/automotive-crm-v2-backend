package pl.detailing.crm.protocol.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Fills an HTML protocol template by injecting CRM values into elements carrying
 * `data-field="<name>"` attributes (the convention verified by
 * ProtocolTemplateVerificationService).
 *
 * The value is HTML-escaped and inserted as the element's content. Checkbox-style
 * values follow the PDFBox convention used by CrmDataResolver ("Yes"/"Off"):
 * truthy values render a ✕ mark, falsy values leave the box empty.
 */
@Service
class HtmlProtocolFillService {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val CHECKED_VALUES = setOf("yes", "on", "true", "1")
        private const val CHECKBOX_MARK = "✕"
    }

    /**
     * @param checkboxFields field names to render as checkbox marks (✕ / empty)
     *        instead of literal text — the caller derives them from the CRM data
     *        keys of the field mappings, so a mileage of "1" never turns into a mark.
     */
    fun fill(
        templateHtml: String,
        fieldValues: Map<String, String>,
        checkboxFields: Set<String> = emptySet()
    ): String {
        var html = templateHtml
        var filled = 0
        var missing = 0

        fieldValues.forEach { (fieldName, rawValue) ->
            val regex = Regex(
                """(<([a-zA-Z0-9]+)([^>]*\bdata-field\s*=\s*["']${Regex.escape(fieldName)}["'][^>]*)>)\s*(</\2>)""",
                RegexOption.IGNORE_CASE
            )
            val value = renderValue(rawValue, isCheckbox = fieldName in checkboxFields)
            var replaced = false
            html = regex.replace(html) { match ->
                replaced = true
                "${match.groupValues[1]}$value${match.groupValues[4]}"
            }
            if (replaced) filled++ else missing++
        }

        logger.info("HTML protocol fill: $filled field(s) filled, $missing not present in template")
        return html
    }

    private fun renderValue(rawValue: String, isCheckbox: Boolean): String {
        if (isCheckbox) {
            val normalized = rawValue.trim().lowercase()
            return if (normalized in CHECKED_VALUES) CHECKBOX_MARK else ""
        }
        return escapeHtml(rawValue).replace("\n", "<br>")
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
