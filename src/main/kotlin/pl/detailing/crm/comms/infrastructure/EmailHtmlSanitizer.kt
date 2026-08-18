package pl.detailing.crm.comms.infrastructure

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Server-side defence layer for foreign e-mail HTML. The output is stored once at
 * ingest and is what the API returns; the frontend adds two more layers on top
 * (DOMPurify + sandboxed iframe), so a bug in any single layer is not exploitable.
 *
 * cid: image references are rewritten here to our authorised attachment endpoint,
 * because only the ingest knows the message id the Content-ID belongs to.
 */
@Service
class EmailHtmlSanitizer {

    /**
     * @param cidToAttachmentId Content-ID (bez nawiasów) -> comm_attachments.id
     */
    fun sanitize(rawHtml: String, messageId: UUID, cidToAttachmentId: Map<String, UUID>): String {
        val document = Jsoup.parse(rawHtml)
        document.select("script, iframe, object, embed, form, base, meta, link").remove()

        // Rewrite inline images before cleaning — Safelist would drop unknown protocols.
        document.select("img[src^=cid:]").forEach { img ->
            val cid = img.attr("src").removePrefix("cid:").trim('<', '>', ' ')
            val attachmentId = cidToAttachmentId[cid]
            if (attachmentId != null) {
                img.attr("src", "/api/v1/comms/attachments/$attachmentId/inline")
            } else {
                img.remove()
            }
        }

        val safelist = Safelist.relaxed()
            .addTags("style", "center", "font", "hr")
            .addAttributes(":all", "style", "align", "valign", "width", "height",
                "border", "cellpadding", "cellspacing", "bgcolor", "color", "face", "size", "dir")
            .addAttributes("table", "role")
            .addAttributes("a", "target")
            .addProtocols("img", "src", "http", "https", "data")
            // Relative URLs are our own rewritten /api/v1/comms/... inline images.
            .preserveRelativeLinks(true)

        val clean = Jsoup.clean(
            document.html(),
            "",
            safelist,
            Document.OutputSettings().prettyPrint(false)
        )

        // Neutralise link targets: everything opens outside the sandboxed iframe.
        val result = Jsoup.parse(clean)
        result.select("a").forEach { a ->
            a.attr("target", "_blank")
            a.attr("rel", "noopener noreferrer")
        }
        result.outputSettings(Document.OutputSettings().prettyPrint(false))
        return result.body().html()
    }

    /** Fallback when a message has no HTML part: escaped plain text with preserved line breaks. */
    fun textAsHtml(text: String): String {
        val escaped = org.jsoup.nodes.Entities.escape(text)
        return "<div style=\"white-space:pre-wrap;font-family:inherit\">$escaped</div>"
    }
}
