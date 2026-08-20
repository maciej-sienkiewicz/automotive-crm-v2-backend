package pl.detailing.crm.leads.intake

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.text.Normalizer

/**
 * Jedno pole formularza po spłaszczeniu: etykieta tak, jak przyszła, i wartość jako tekst.
 */
data class FormField(val label: String, val value: String)

/**
 * Zamienia dowolny ładunek formularza na płaską listę par etykieta → wartość.
 *
 * Powód, dla którego to w ogóle istnieje: nie ma jednego formatu. Elementor kluczuje
 * etykietami widocznymi na stronie („Imię i nazwisko"), Contact Form 7 nazwami tagów
 * („your-name"), Tally i Typeform wysyłają tablicę obiektów {label, value} zagnieżdżoną
 * pod `data.fields`, a HubSpot tablicę {name, value}. Gdybyśmy narzucili własny kształt,
 * każde wdrożenie zaczynałoby się od pisania pośrednika — czyli od tego, przed czym ta
 * funkcja ma chronić.
 *
 * Zasada jest więc odwrotna: przyjmujemy wszystko i sami szukamy sensu. Spłaszczamy
 * drzewo, rozpoznajemy tablice par etykieta/wartość i zwracamy listę, którą dopiero
 * [LeadFieldMapper] tłumaczy na pola leada.
 */
@Component
class FormPayloadReader(private val objectMapper: ObjectMapper) {

    /** Klucze, pod którymi wtyczki trzymają etykietę pola w tablicach par. */
    private val labelKeys = listOf("label", "name", "key", "title", "field", "question")

    /** Klucze, pod którymi trzymają wartość. */
    private val valueKeys = listOf("value", "answer", "text", "values")

    /** Opakowania, które niosą właściwy formularz w środku. */
    private val envelopeKeys = listOf("data", "form_response", "payload", "fields", "form", "answers", "result")

    /**
     * Rozpoznajemy format po TREŚCI, nie po nagłówku: `Content-Type` bywa ustawiony
     * byle jak (część wtyczek deklaruje text/plain przy JSON-ie), a nawias klamrowy
     * na początku ciała nie kłamie. Nagłówek służy tylko jako plan awaryjny.
     */
    fun read(rawBody: String, contentType: String?): List<FormField> {
        val body = rawBody.trim()
        if (body.isEmpty()) return emptyList()
        if (body.startsWith("{") || body.startsWith("[")) return readJson(body)

        val formEncoded = readFormEncoded(body)
        if (formEncoded.isNotEmpty()) return formEncoded
        return if (contentType?.contains("json", ignoreCase = true) == true) readJson(body) else emptyList()
    }

    private fun readJson(rawBody: String): List<FormField> {
        val root = runCatching { objectMapper.readTree(rawBody) }.getOrNull() ?: return emptyList()
        val fields = mutableListOf<FormField>()
        collect(root, prefix = "", into = fields, depth = 0)
        return fields
    }

    /** `your-name=Jan&your-email=jan%40example.com` — domyślny format Elementora. */
    private fun readFormEncoded(rawBody: String): List<FormField> =
        rawBody.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val index = pair.indexOf('=')
                val rawKey = if (index < 0) pair else pair.substring(0, index)
                val rawValue = if (index < 0) "" else pair.substring(index + 1)
                val key = decode(rawKey)
                val value = decode(rawValue)
                if (key.isBlank() || value.isBlank()) null else FormField(key, value)
            }

    private fun decode(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw.replace('+', ' '), Charsets.UTF_8) }
            .getOrDefault(raw)
            .trim()

    private fun collect(node: JsonNode, prefix: String, into: MutableList<FormField>, depth: Int) {
        if (depth > MAX_DEPTH) return

        when {
            node.isObject -> {
                // Tablica par {label, value} udająca obiekt — spotykane w wtyczkach CF7.
                val pair = asLabelValuePair(node)
                if (pair != null) {
                    into += pair
                    return
                }
                node.fields().forEach { (key, child) ->
                    // Opakowania nie wnoszą nazwy — inaczej etykiety wyglądałyby jak
                    // „data.fields.Imię", a słownik synonimów szukałby czegoś takiego.
                    val nextPrefix = if (key in envelopeKeys) prefix else joinLabel(prefix, key)
                    collect(child, nextPrefix, into, depth + 1)
                }
            }

            node.isArray -> {
                val scalars = node.all { it.isValueNode }
                if (scalars) {
                    // Pole wielokrotnego wyboru: „Ceramika, PPF".
                    val joined = node.mapNotNull { it.asText().trim().ifBlank { null } }.joinToString(", ")
                    if (joined.isNotBlank() && prefix.isNotBlank()) into += FormField(prefix, joined)
                    return
                }
                node.forEach { collect(it, prefix, into, depth + 1) }
            }

            node.isValueNode -> {
                val value = node.asText().trim()
                if (value.isNotBlank() && prefix.isNotBlank()) into += FormField(prefix, value)
            }
        }
    }

    /** {label|name|key: "...", value|answer: ...} → jedno pole. */
    private fun asLabelValuePair(node: JsonNode): FormField? {
        val labelKey = labelKeys.firstOrNull { node.get(it)?.isTextual == true } ?: return null
        val valueKey = valueKeys.firstOrNull { node.has(it) } ?: return null
        val label = node.get(labelKey).asText().trim()
        val valueNode = node.get(valueKey)
        val value = when {
            valueNode.isArray -> valueNode.mapNotNull { it.asText().trim().ifBlank { null } }.joinToString(", ")
            valueNode.isValueNode -> valueNode.asText().trim()
            // Wartość-obiekt (np. {label, id} przy wyborze) — bierzemy jej etykietę.
            valueNode.isObject -> labelKeys.firstOrNull { valueNode.get(it)?.isTextual == true }
                ?.let { valueNode.get(it).asText().trim() } ?: ""
            else -> ""
        }
        if (label.isBlank() || value.isBlank()) return null
        return FormField(label, value)
    }

    private fun joinLabel(prefix: String, key: String): String =
        if (prefix.isBlank()) key else "$prefix.$key"

    private companion object {
        const val MAX_DEPTH = 8
    }
}

/**
 * Porównywanie nazw pól bez potykania się o wielkość liter, polskie znaki, myślniki
 * i podkreślenia: „Imię i nazwisko", „imie_i_nazwisko" i „your-name" mają trafić
 * w ten sam klucz.
 */
fun normalizeFieldName(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("ł", "l")
        .replace("Ł", "L")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
