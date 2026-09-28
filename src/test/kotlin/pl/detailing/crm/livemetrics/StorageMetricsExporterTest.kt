package pl.detailing.crm.livemetrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import pl.detailing.crm.livemetrics.prometheus.StorageKeyOwner
import pl.detailing.crm.livemetrics.prometheus.StorageMetricsExporter
import pl.detailing.crm.studio.reset.S3StudioPurger
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable
import java.sql.ResultSet
import java.util.UUID

class StorageMetricsExporterTest {

    private val studio = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val gone = UUID.fromString("99999999-8888-7777-6666-555555555555")

    @Test
    fun `every prefix the studio purger clears is attributed to that studio`() {
        for (prefix in S3StudioPurger.prefixesOf(studio)) {
            assertEquals(studio, StorageKeyOwner.of("${prefix}x/y/file.jpg").studioId, prefix)
        }
    }

    @Test
    fun `key layout maps to studio and a closed set of areas`() {
        assertEquals(StorageKeyOwner(studio, "visits"), StorageKeyOwner.of("$studio/visits/v1/photos/p.jpg"))
        assertEquals(StorageKeyOwner(studio, "mail-signature"), StorageKeyOwner.of("$studio/mail-signature/h.png"))
        assertEquals(StorageKeyOwner(studio, "thumbs"), StorageKeyOwner.of("thumbs/$studio/visits/p.jpg"))
        assertEquals(StorageKeyOwner(studio, "temp"), StorageKeyOwner.of("temp/$studio/sessions/s/p.jpg"))
        assertEquals(StorageKeyOwner(studio, "temp"), StorageKeyOwner.of("temp/uploads/$studio/c/p.jpg"))
        // Plik wprost pod katalogiem studia albo dziwny segment nie tworzy nowej serii.
        assertEquals(StorageKeyOwner(studio, "other"), StorageKeyOwner.of("$studio/loose.pdf"))
        assertEquals(StorageKeyOwner(studio, "other"), StorageKeyOwner.of("$studio/Some Folder/x.pdf"))
        // Bez studia — pula wspólna, nie zgubione.
        assertEquals(StorageKeyOwner(null, "public"), StorageKeyOwner.of("public/icons/v1/phone.png"))
        assertEquals(StorageKeyOwner(null, "other"), StorageKeyOwner.of("stray.txt"))
        assertNull(StorageKeyOwner.of("thumbs/not-a-uuid/p.jpg").studioId)
    }

    @Test
    fun `whole bucket is summed across pages and split per tenant, shared pool and deleted studios`() {
        val s3 = mockk<S3Client>()
        every { s3.listObjectsV2Paginator(any<ListObjectsV2Request>()) } answers {
            ListObjectsV2Iterable(s3, firstArg())
        }
        val request = slot<ListObjectsV2Request>()
        every { s3.listObjectsV2(capture(request)) } answers {
            if (request.captured.continuationToken() == null) {
                page(truncated = true, "$studio/visits/a.jpg" to 100L, "thumbs/$studio/a.jpg" to 10L)
            } else {
                page(truncated = false, "$studio/visits/b.jpg" to 50L, "$gone/protocols/x/p.pdf" to 7L,
                    "public/icons/i.png" to 3L)
            }
        }
        val jdbc = mockk<JdbcTemplate>()
        every { jdbc.query(any<String>(), any<RowCallbackHandler>()) } answers {
            val rs = mockk<ResultSet>()
            every { rs.getObject("id", UUID::class.java) } returns studio
            every { rs.getString("name") } returns "Studio Blask"
            secondArg<RowCallbackHandler>().processRow(rs)
        }

        val registry = SimpleMeterRegistry()
        val exporter = StorageMetricsExporter(registry, s3, jdbc, "bucket-x", enabled = true)
        exporter.register()
        exporter.refresh()

        fun bytes(vararg tags: String) =
            registry.get(StorageMetricsExporter.TENANT_BYTES).tags(*tags).gauge().value()

        assertEquals(150.0, bytes("tenant_id", studio.toString(), "tenant", "Studio Blask", "area", "visits"))
        assertEquals(10.0, bytes("tenant_id", studio.toString(), "area", "thumbs"))
        assertEquals(7.0, bytes("tenant_id", gone.toString(), "tenant", "(usunięte) 99999999"))
        assertEquals(3.0, bytes("tenant_id", StorageMetricsExporter.SHARED_TENANT_ID, "area", "public"))
        assertEquals(2.0, registry.get(StorageMetricsExporter.TENANT_OBJECTS)
            .tags("tenant_id", studio.toString(), "area", "visits").gauge().value())

        assertEquals(170.0, registry.get(StorageMetricsExporter.BUCKET_BYTES).tags("bucket", "bucket-x").gauge().value())
        assertEquals(5.0, registry.get(StorageMetricsExporter.BUCKET_OBJECTS).gauge().value())
        assert(registry.get(StorageMetricsExporter.REFRESHED).gauge().value() > 0)
    }

    @Test
    fun `failed scan keeps previous values and does not bump the refresh time`() {
        val s3 = mockk<S3Client>()
        every { s3.listObjectsV2Paginator(any<ListObjectsV2Request>()) } throws RuntimeException("AccessDenied")
        val registry = SimpleMeterRegistry()
        val exporter = StorageMetricsExporter(registry, s3, mockk(relaxed = true), "bucket-x", enabled = true)
        exporter.register()
        exporter.refresh()

        assertEquals(0.0, registry.get(StorageMetricsExporter.REFRESHED).gauge().value())
        assertEquals(0, registry.find(StorageMetricsExporter.TENANT_BYTES).gauges().size)
    }

    private fun page(truncated: Boolean, vararg objects: Pair<String, Long>): ListObjectsV2Response =
        ListObjectsV2Response.builder()
            .contents(objects.map { (key, size) -> S3Object.builder().key(key).size(size).build() })
            .isTruncated(truncated)
            .nextContinuationToken(if (truncated) "next" else null)
            .build()
}
