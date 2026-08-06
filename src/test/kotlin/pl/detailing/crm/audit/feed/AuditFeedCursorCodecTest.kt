package pl.detailing.crm.audit.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.infrastructure.AuditFeedCursor
import java.time.Instant
import java.util.UUID

class AuditFeedCursorCodecTest {

    @Test
    fun `a cursor survives a round trip`() {
        val cursor = AuditFeedCursor(Instant.parse("2026-02-17T09:00:00Z"), UUID.randomUUID())

        assertEquals(cursor, AuditFeedCursorCodec.decode(AuditFeedCursorCodec.encode(cursor)))
    }

    @Test
    fun `sub-millisecond precision is preserved`() {
        // created_at is a microsecond-precision column, and entries written in one
        // transaction share a timestamp down to the millisecond. Rounding here would make
        // the page boundary skip or repeat exactly those bursts.
        val cursor = AuditFeedCursor(Instant.parse("2026-02-17T09:00:00.123456Z"), UUID.randomUUID())

        val decoded = AuditFeedCursorCodec.decode(AuditFeedCursorCodec.encode(cursor))

        assertEquals(cursor.createdAt, decoded!!.createdAt)
        assertEquals(123456000, decoded.createdAt.nano)
    }

    @Test
    fun `the encoded form is URL-safe so it can be passed back as a query parameter`() {
        val encoded = AuditFeedCursorCodec.encode(
            AuditFeedCursor(Instant.parse("2026-02-17T09:00:00Z"), UUID.randomUUID())
        )

        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
    }

    @Test
    fun `garbage restarts the feed instead of failing the request`() {
        assertNull(AuditFeedCursorCodec.decode(null))
        assertNull(AuditFeedCursorCodec.decode(""))
        assertNull(AuditFeedCursorCodec.decode("   "))
        assertNull(AuditFeedCursorCodec.decode("not-base64-!!!"))
        assertNull(AuditFeedCursorCodec.decode(java.util.Base64.getUrlEncoder().encodeToString("nonsense".toByteArray())))
        assertNull(AuditFeedCursorCodec.decode(java.util.Base64.getUrlEncoder().encodeToString("123.0:not-a-uuid".toByteArray())))
    }
}
