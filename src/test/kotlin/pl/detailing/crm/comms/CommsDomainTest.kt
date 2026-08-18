package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import java.util.UUID

class EmailTextCleanerTest {

    private val cleaner = EmailTextCleaner()

    @Test
    fun `strips quoted history below polish reply marker`() {
        val text = """
            Dzień dobry, poproszę o wycenę powłoki.

            W dniu 12.08.2026 o 14:20 Studio Detailingu napisał(a):
            > Dziękujemy za wiadomość
        """.trimIndent()
        val cleaned = cleaner.clean(null, text)
        assertTrue(cleaned.contains("poproszę o wycenę"))
        assertFalse(cleaned.contains("Dziękujemy za wiadomość"))
    }

    @Test
    fun `drops gmail quote containers from html`() {
        val html = """
            <div>Nowa treść zapytania</div>
            <div class="gmail_quote">stara korespondencja</div>
        """.trimIndent()
        val cleaned = cleaner.clean(html, null)
        assertTrue(cleaned.contains("Nowa treść zapytania"))
        assertFalse(cleaned.contains("stara korespondencja"))
    }

    @Test
    fun `snippet is single line and bounded`() {
        val snippet = cleaner.snippet(null, "linia1\nlinia2\n" + "x".repeat(500))
        assertFalse(snippet.contains('\n'))
        assertTrue(snippet.length <= 160)
    }
}

class EmailHtmlSanitizerTest {

    private val sanitizer = EmailHtmlSanitizer()

    @Test
    fun `removes scripts and event handlers`() {
        val out = sanitizer.sanitize(
            """<div onclick="steal()">Hej<script>alert(1)</script><a href="javascript:x()">tu</a></div>""",
            UUID.randomUUID(),
            emptyMap()
        )
        assertFalse(out.contains("script", ignoreCase = true))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("javascript:"))
        assertTrue(out.contains("Hej"))
    }

    @Test
    fun `rewrites known cid images and drops unknown ones`() {
        val attachmentId = UUID.randomUUID()
        val out = sanitizer.sanitize(
            """<img src="cid:logo123"><img src="cid:missing999">""",
            UUID.randomUUID(),
            mapOf("logo123" to attachmentId)
        )
        assertTrue(out.contains("/api/v1/comms/attachments/$attachmentId/inline"))
        assertFalse(out.contains("missing999"))
    }

    @Test
    fun `keeps remote https images and hardens links`() {
        val out = sanitizer.sanitize(
            """<img src="https://example.com/x.png"><a href="https://example.com">link</a>""",
            UUID.randomUUID(),
            emptyMap()
        )
        assertTrue(out.contains("https://example.com/x.png"))
        assertTrue(out.contains("rel=\"noopener noreferrer\""))
        assertTrue(out.contains("target=\"_blank\""))
    }

    @Test
    fun `escapes plain text fallback`() {
        val out = sanitizer.textAsHtml("<b>nie html</b>\ndruga linia")
        assertFalse(out.contains("<b>"))
        assertTrue(out.contains("druga linia"))
    }
}
