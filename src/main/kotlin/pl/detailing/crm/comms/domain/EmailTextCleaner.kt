package pl.detailing.crm.comms.domain

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service

/**
 * Turns a raw e-mail body into denoised plain text for snippets, insights and search.
 *
 * Quoted history is the reason threads get expensive: by the third message a client's
 * reply carries the whole conversation again. Removing it is deterministic work, so it
 * is done once at ingest and stored, rather than paid for on every render.
 */
@Service
class EmailTextCleaner {

    private val quoteMarkers = listOf(
        Regex("^W dniu .{0,160}napisa[łl](a)?\\s*(:|\\(a\\):)", RegexOption.IGNORE_CASE),
        Regex("^Dnia .{0,160}(napisa[łl](a)?|pisze)\\s*:", RegexOption.IGNORE_CASE),
        Regex("^On .{0,160}wrote:", RegexOption.IGNORE_CASE),
        Regex("^-{2,}\\s*(Original Message|Wiadomość oryginalna|Forwarded message|Wiadomość przekazana)", RegexOption.IGNORE_CASE),
        Regex("^(pon|wt|śr|sr|czw|pt|sob|niedz)\\.,? .{0,160}napisa[łl](a)?\\s*(:|\\(a\\):)", RegexOption.IGNORE_CASE)
    )

    private val signatureMarkers = listOf(
        Regex("^--\\s*$"),
        Regex(
            "^(Pozdrawiam|Z poważaniem|Z wyrazami szacunku|Serdecznie pozdrawiam|Best regards|Kind regards)[,.!]?\\s*$",
            RegexOption.IGNORE_CASE
        ),
        Regex("^(Wysłane z|Sent from) ", RegexOption.IGNORE_CASE)
    )

    fun clean(html: String?, plainText: String?): String {
        val text = when {
            !html.isNullOrBlank() -> htmlToText(html)
            !plainText.isNullOrBlank() -> plainText
            else -> return ""
        }
        return stripQuotedHistory(text).take(MAX_CLEAN_LENGTH).trim()
    }

    fun snippet(html: String?, plainText: String?, maxLength: Int = 160): String =
        clean(html, plainText).replace(Regex("\\s+"), " ").take(maxLength)

    private fun htmlToText(html: String): String {
        val document = Jsoup.parse(html)
        document.select("style, script, head").remove()
        // Quoted-history containers used by the major clients.
        document.select(
            "div.gmail_quote, blockquote, div#divRplyFwdMsg, div.moz-cite-prefix, div.yahoo_quoted"
        ).remove()
        document.select("[style~=(?i)display:\\s*none]").remove()
        document.select("br, p, div, li, tr").before(LINE_BREAK_TOKEN)

        val stripped = Jsoup.clean(
            document.html(),
            "",
            Safelist.none(),
            Document.OutputSettings().prettyPrint(false)
        )
        return Parser.unescapeEntities(stripped, false).replace(LINE_BREAK_TOKEN, "\n")
    }

    private fun stripQuotedHistory(text: String): String {
        val kept = mutableListOf<String>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val trimmed = line.trim()
            if (trimmed.startsWith(">")) continue
            if (quoteMarkers.any { it.containsMatchIn(trimmed) }) break
            if (signatureMarkers.any { it.matches(trimmed) }) break
            kept.add(line.trimEnd())
        }
        return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }

    companion object {
        const val MAX_CLEAN_LENGTH = 4000
        private const val LINE_BREAK_TOKEN = "@@CRM_BR@@"
    }
}
