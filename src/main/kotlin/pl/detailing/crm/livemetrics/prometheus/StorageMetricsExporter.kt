package pl.detailing.crm.livemetrics.prometheus

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Zajętość bucketu S3 — łącznie i per tenant — dla dashboardu „Miejsce w S3".
 *
 *  - `crm_storage_tenant_bytes{tenant_id, tenant, area}`   → bajty studia w danym obszarze,
 *  - `crm_storage_tenant_objects{tenant_id, tenant, area}` → liczba obiektów,
 *  - `crm_storage_bucket_bytes{bucket}` / `crm_storage_bucket_objects{bucket}` → cały bucket,
 *  - `crm_storage_refreshed_seconds` → epoch ostatniego UDANEGO skanu,
 *  - `crm_storage_scan_duration_seconds` → czas ostatniego udanego skanu.
 *
 * ### Skąd liczby
 *
 * Z pełnego listingu bucketu (ListObjectsV2, 1000 obiektów na stronę), nie z bazy: baza nie
 * zna rozmiarów wszystkich plików (miniatury, protokoły, pliki tymczasowe), a listing jest
 * tym, za co płacimy. Właściciela obiektu wyznacza klucz — patrz [StorageKeyOwner]. Obiekty
 * bez studia (wspólne zasoby) trafiają do `tenant_id="_shared"`, a katalog studia, którego
 * nie ma już w bazie, do tenanta `(usunięte) xxxxxxxx` — to osierocone pliki, za które
 * nadal płacimy, więc mają być widoczne, a nie połknięte.
 *
 * Koszt: jedno żądanie LIST na 1000 obiektów (~0,005 USD za 1000 żądań). Milion plików
 * skanowany co godzinę to ~0,12 USD dziennie.
 *
 * ### Czego tu NIE ma
 *
 * Listing widzi bieżące wersje obiektów. Nie widzi wersji niebieżących (gdyby bucket miał
 * włączone wersjonowanie) ani porzuconych multipart uploadów — oba są w rachunku AWS,
 * więc `crm_storage_bucket_bytes` może być niższe niż `BucketSizeBytes` z CloudWatch.
 *
 * ### Osobny wątek
 *
 * Wszystkie `@Scheduled` w aplikacji dzielą jeden wątek schedulera, a skan dużego bucketu
 * trwa minuty. `@Scheduled` tylko zleca skan na własny wątek; jeśli poprzedni jeszcze
 * trwa, nowy nie startuje.
 *
 * ### Awaria jest widoczna, nie cicha
 *
 * Wyniki publikujemy dopiero po przejściu CAŁEGO bucketu — skan przerwany w połowie nie
 * pokaże połowy miejsca. Nieudany skan zostawia poprzednie wartości, a o jego wieku mówi
 * `crm_storage_refreshed_seconds` (alert `StorageGaugesStale`).
 */
@Component
class StorageMetricsExporter(
    private val registry: MeterRegistry,
    private val s3Client: S3Client,
    private val jdbc: JdbcTemplate,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    @Value("\${crm.storage-metrics.enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(StorageMetricsExporter::class.java)

    companion object {
        const val TENANT_BYTES = "crm.storage.tenant.bytes"
        const val TENANT_OBJECTS = "crm.storage.tenant.objects"
        const val BUCKET_BYTES = "crm.storage.bucket.bytes"
        const val BUCKET_OBJECTS = "crm.storage.bucket.objects"
        const val REFRESHED = "crm.storage.refreshed.seconds"
        const val SCAN_DURATION = "crm.storage.scan.duration.seconds"

        const val SHARED_TENANT_ID = "_shared"
        const val SHARED_TENANT = "(wspólne)"
    }

    private lateinit var tenantBytes: MultiGauge
    private lateinit var tenantObjects: MultiGauge
    private val bucketBytes = AtomicLong()
    private val bucketObjects = AtomicLong()
    private val refreshedAt = AtomicLong()
    private val scanDurationSeconds = AtomicLong()

    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "storage-metrics").apply { isDaemon = true }
    }

    private class Usage {
        var bytes = 0L
        var objects = 0L
    }

    @PostConstruct
    fun register() {
        tenantBytes = MultiGauge.builder(TENANT_BYTES)
            .description("Bajty w buckecie S3 per studio i obszar (visits, protocols, thumbs…)").register(registry)
        tenantObjects = MultiGauge.builder(TENANT_OBJECTS)
            .description("Liczba obiektów w buckecie S3 per studio i obszar").register(registry)
        val bucket = Tags.of("bucket", bucketName)
        Gauge.builder(BUCKET_BYTES) { bucketBytes.get() }.tags(bucket)
            .description("Bajty w całym buckecie S3 (bieżące wersje obiektów)").register(registry)
        Gauge.builder(BUCKET_OBJECTS) { bucketObjects.get() }.tags(bucket)
            .description("Liczba obiektów w całym buckecie S3").register(registry)
        Gauge.builder(REFRESHED) { refreshedAt.get() }
            .description("Epoch (s) ostatniego udanego skanu bucketu — starszy niż ~2 cykle znaczy awarię")
            .register(registry)
        Gauge.builder(SCAN_DURATION) { scanDurationSeconds.get() }
            .description("Czas trwania ostatniego udanego skanu bucketu (s)").register(registry)
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    @Scheduled(
        fixedDelayString = "\${crm.storage-metrics.refresh-minutes:60}",
        initialDelayString = "\${crm.storage-metrics.initial-delay-minutes:3}",
        timeUnit = TimeUnit.MINUTES
    )
    fun trigger() {
        if (!enabled || !running.compareAndSet(false, true)) return
        executor.execute {
            try {
                refresh()
            } finally {
                running.set(false)
            }
        }
    }

    fun refresh() {
        val started = System.nanoTime()
        try {
            val usage = scan()
            publish(usage)
            scanDurationSeconds.set(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
            refreshedAt.set(System.currentTimeMillis() / 1000)
            log.info(
                "[STORAGE-METRICS] scanned bucket {}: {} objects, {} bytes in {} s",
                bucketName, bucketObjects.get(), bucketBytes.get(), scanDurationSeconds.get()
            )
        } catch (e: Exception) {
            log.warn("[STORAGE-METRICS] bucket scan failed: {}", e.toString())
        }
    }

    /** Pełny listing bucketu zagregowany do (studio, obszar). Paginator sam prowadzi continuation token. */
    private fun scan(): Map<StorageKeyOwner, Usage> {
        val usage = HashMap<StorageKeyOwner, Usage>()
        val request = ListObjectsV2Request.builder().bucket(bucketName).build()
        for (obj in s3Client.listObjectsV2Paginator(request).contents()) {
            val u = usage.getOrPut(StorageKeyOwner.of(obj.key())) { Usage() }
            u.bytes += obj.size() ?: 0L
            u.objects++
        }
        return usage
    }

    private fun publish(usage: Map<StorageKeyOwner, Usage>) {
        val names = loadStudioNames()
        val byteRows = ArrayList<MultiGauge.Row<Number>>(usage.size)
        val objectRows = ArrayList<MultiGauge.Row<Number>>(usage.size)
        for ((owner, u) in usage) {
            val tags = tenantTags(owner.studioId, names).and("area", owner.area)
            byteRows += MultiGauge.Row.of(tags, u.bytes)
            objectRows += MultiGauge.Row.of(tags, u.objects)
        }
        tenantBytes.register(byteRows, true)
        tenantObjects.register(objectRows, true)
        bucketBytes.set(usage.values.sumOf { it.bytes })
        bucketObjects.set(usage.values.sumOf { it.objects })
    }

    /** `names == null` znaczy „nie udało się odczytać studiów" — wtedy nie wolno nazwać nikogo usuniętym. */
    private fun tenantTags(studioId: UUID?, names: Map<UUID, String>?): Tags {
        if (studioId == null) return Tags.of("tenant_id", SHARED_TENANT_ID, "tenant", SHARED_TENANT)
        val shortId = studioId.toString().take(8)
        val name = when {
            names == null -> shortId
            else -> names[studioId] ?: "(usunięte) $shortId"
        }
        return Tags.of("tenant_id", studioId.toString(), "tenant", name)
    }

    /**
     * Wszystkie studia, łącznie z piaskownicami podglądu roli: tutaj liczy się każdy bajt
     * w buckecie, a nie tylko klienci. Nieudany odczyt nazw nie może zablokować liczb —
     * wtedy (`null`) etykietą `tenant` jest skrót identyfikatora.
     */
    private fun loadStudioNames(): Map<UUID, String>? = try {
        val out = HashMap<UUID, String>()
        jdbc.query("SELECT id, name FROM studios") { rs ->
            rs.getObject("id", UUID::class.java)?.let { id -> out[id] = rs.getString("name") ?: id.toString().take(8) }
        }
        out
    } catch (e: Exception) {
        log.warn("[STORAGE-METRICS] studio names query failed: {}", e.toString())
        null
    }
}
