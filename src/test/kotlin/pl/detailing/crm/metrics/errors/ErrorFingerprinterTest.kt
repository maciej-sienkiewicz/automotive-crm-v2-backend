package pl.detailing.crm.metrics.errors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ErrorOrigin

/**
 * The fingerprint is what turns an error firehose into a triage list. These tests pin the
 * two failure modes that make such a list useless: grouping everything together, and
 * grouping nothing together.
 */
class ErrorFingerprinterTest {

    private val fingerprinter = ErrorFingerprinter(MetricsProperties())

    private fun trace(line: Int) = """
        java.lang.IllegalStateException: boom
            at pl.detailing.crm.visit.create.CreateVisitHandler.handle(CreateVisitHandler.kt:$line)
            at org.springframework.aop.framework.CglibAopProxy.intercept(CglibAopProxy.java:702)
            at java.base/java.lang.Thread.run(Thread.java:840)
    """.trimIndent()

    @Test
    fun `the same defect with different ids produces one group`() {
        val a = fingerprinter.fingerprint(
            ErrorOrigin.BACKEND, "pl.detailing.crm.shared.NotFoundException",
            "Nie znaleziono wizyty 8f3c1a2b-1111-2222-3333-444455556666", trace(88)
        )
        val b = fingerprinter.fingerprint(
            ErrorOrigin.BACKEND, "pl.detailing.crm.shared.NotFoundException",
            "Nie znaleziono wizyty aaaa1111-9999-8888-7777-666655554444", trace(88)
        )

        // Without message normalisation this is the bug that makes the console unusable
        // within a day: one group per entity id.
        assertEquals(a, b)
    }

    @Test
    fun `numbers and dates in messages do not fragment a group`() {
        val a = fingerprinter.fingerprint(
            ErrorOrigin.BACKEND, "java.lang.IllegalStateException",
            "Wizyta 4211 zaplanowana na 2026-08-19 nie ma pojazdu", trace(88)
        )
        val b = fingerprinter.fingerprint(
            ErrorOrigin.BACKEND, "java.lang.IllegalStateException",
            "Wizyta 9987 zaplanowana na 2026-09-02 nie ma pojazdu", trace(88)
        )

        assertEquals(a, b)
    }

    @Test
    fun `two different bugs in the same method stay separate`() {
        val a = fingerprinter.fingerprint(
            ErrorOrigin.BACKEND, "java.lang.IllegalStateException", "boom", trace(88)
        )
        val b = fingerprinter.fingerprint(
            ErrorOrigin.BACKEND, "java.lang.IllegalStateException", "boom", trace(140)
        )

        // Merging them would hide the second defect behind the first one's "resolved" flag.
        assertNotEquals(a, b)
    }

    @Test
    fun `frontend and backend errors never share a group`() {
        val backend = fingerprinter.fingerprint(ErrorOrigin.BACKEND, "TypeError", "x is null", null)
        val frontend = fingerprinter.fingerprint(ErrorOrigin.FRONTEND, "TypeError", "x is null", null)

        assertNotEquals(backend, frontend)
    }

    @Test
    fun `only our own frames are significant`() {
        val frames = fingerprinter.significantFrames(trace(88))

        assertEquals(1, frames.size, "framework frames must be dropped")
        assertTrue(frames.first().startsWith("pl.detailing.crm.visit.create.CreateVisitHandler.handle"))
        assertTrue(frames.first().endsWith(":88"))
    }

    @Test
    fun `messages are normalised, not just truncated`() {
        val normalized = fingerprinter.normalizeMessage(
            "Klient jan.kowalski@example.com (id 8f3c1a2b-1111-2222-3333-444455556666) ma 3 pojazdy"
        )

        assertEquals("Klient {email} (id {uuid}) ma {n} pojazdy", normalized)
    }

    @Test
    fun `a missing stack trace still yields a stable fingerprint`() {
        val a = fingerprinter.fingerprint(ErrorOrigin.FRONTEND, "ChunkLoadError", "Loading chunk 42 failed", null)
        val b = fingerprinter.fingerprint(ErrorOrigin.FRONTEND, "ChunkLoadError", "Loading chunk 77 failed", null)

        assertEquals(a, b)
        assertEquals(32, a.value.length)
    }

    @Test
    fun `the group title names the code that failed`() {
        val title = fingerprinter.titleFor(
            "java.lang.IllegalStateException", "boom", trace(88)
        )

        assertTrue(title.contains("CreateVisitHandler.handle"), title)
        assertTrue(title.contains("IllegalStateException"), title)
    }
}
