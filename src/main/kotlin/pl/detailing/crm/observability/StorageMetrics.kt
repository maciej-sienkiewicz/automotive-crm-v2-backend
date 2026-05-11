package pl.detailing.crm.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.Dimension
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest
import software.amazon.awssdk.services.cloudwatch.model.Statistic
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Registers storage observability metrics for two signal sources:
 *
 * 1. Server disk (root filesystem)
 *    Polled on every Prometheus scrape via Micrometer Gauges backed by
 *    [java.nio.file.FileStore] — zero AWS calls, always up-to-date.
 *
 *    crm_storage_disk_total_bytes   – total capacity of the root filesystem
 *    crm_storage_disk_free_bytes    – usable (free) bytes remaining
 *
 * 2. AWS S3 bucket size (via CloudWatch)
 *    AWS publishes S3 storage metrics to CloudWatch once per day with a
 *    ~24 h lag.  Querying CloudWatch on every Prometheus scrape would be
 *    wasteful and expensive.  Instead, a background job refreshes the
 *    cached values every 6 hours; Prometheus reads from AtomicLongs.
 *
 *    crm_storage_s3_size_bytes      – bucket size in bytes (StandardStorage)
 *    crm_storage_s3_objects_total   – number of objects (AllStorageTypes)
 *
 *    Both carry a [bucket] tag so that the config can be extended to multiple
 *    buckets in the future without breaking existing dashboards.
 *
 * AWS IAM permissions required
 * ────────────────────────────
 * The IAM user / role associated with [aws.s3.access-key] must have:
 *   cloudwatch:GetMetricStatistics  on resource  *
 * (The same credentials used for S3 are reused here.)
 *
 * AWS CloudWatch availability
 * ────────────────────────────
 * If AWS credentials are not configured (empty access-key), the S3 gauges
 * are registered but always return -1, and a WARN is logged.
 * This allows the application to start without AWS in local/dev environments.
 */
@Component
class StorageMetrics(
    private val registry: MeterRegistry,
    @Value("\${aws.s3.bucket-name:}") private val bucketName: String,
    @Value("\${aws.s3.region:us-east-1}") private val region: String,
    @Value("\${aws.s3.access-key:}") private val accessKey: String,
    @Value("\${aws.s3.secret-key:}") private val secretKey: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Cached S3 values updated by the scheduler; -1 means "not yet fetched"
    private val s3SizeBytes = AtomicLong(-1L)
    private val s3ObjectCount = AtomicLong(-1L)

    private val cloudWatchClient: CloudWatchClient? by lazy { buildCloudWatchClient() }

    // ── Metric registration ──────────────────────────────────────────────────

    @PostConstruct
    fun registerGauges() {
        registerDiskGauges()
        registerS3Gauges()
    }

    private fun registerDiskGauges() {
        Gauge.builder(MetricsTags.STORAGE_DISK_TOTAL) { diskTotalBytes() }
            .description("Total capacity of the server root filesystem in bytes")
            .baseUnit("bytes")
            .register(registry)

        Gauge.builder(MetricsTags.STORAGE_DISK_FREE) { diskFreeBytes() }
            .description("Usable (free) bytes on the server root filesystem")
            .baseUnit("bytes")
            .register(registry)
    }

    private fun registerS3Gauges() {
        Gauge.builder(MetricsTags.STORAGE_S3_SIZE) { s3SizeBytes.get().toDouble() }
            .description("S3 bucket size in bytes (StandardStorage, refreshed from CloudWatch every 6 h)")
            .baseUnit("bytes")
            .tag(MetricsTags.TAG_BUCKET, bucketName.ifBlank { "unknown" })
            .register(registry)

        Gauge.builder(MetricsTags.STORAGE_S3_OBJECTS) { s3ObjectCount.get().toDouble() }
            .description("Number of objects in the S3 bucket (refreshed from CloudWatch every 6 h)")
            .tag(MetricsTags.TAG_BUCKET, bucketName.ifBlank { "unknown" })
            .register(registry)
    }

    // ── Disk helpers ─────────────────────────────────────────────────────────

    private fun diskTotalBytes(): Double = runCatching {
        Files.getFileStore(Paths.get("/")).totalSpace.toDouble()
    }.getOrElse {
        log.warn("Could not read disk total space: ${it.message}")
        -1.0
    }

    private fun diskFreeBytes(): Double = runCatching {
        Files.getFileStore(Paths.get("/")).usableSpace.toDouble()
    }.getOrElse {
        log.warn("Could not read disk free space: ${it.message}")
        -1.0
    }

    // ── S3 / CloudWatch refresh ───────────────────────────────────────────────

    /**
     * Refreshes S3 storage metrics from CloudWatch.
     * Runs at startup (after a 30-second warm-up) and every 6 hours.
     * CloudWatch S3 metrics have a ~24 h lag; querying more often is
     * harmless and ensures the value is picked up as soon as it's published.
     */
    @Scheduled(initialDelayString = "30000", fixedDelayString = "21600000")
    fun refreshS3Metrics() {
        if (bucketName.isBlank()) {
            log.debug("S3 bucket name not configured – skipping CloudWatch refresh")
            return
        }
        val client = cloudWatchClient ?: run {
            log.warn("CloudWatch client unavailable (missing AWS credentials) – S3 storage metrics will show -1")
            return
        }

        runCatching {
            s3SizeBytes.set(queryCloudWatch(client, "BucketSizeBytes", "StandardStorage"))
            s3ObjectCount.set(queryCloudWatch(client, "NumberOfObjects", "AllStorageTypes"))
            log.debug(
                "S3 storage metrics refreshed: bucket={} size={} bytes objects={}",
                bucketName, s3SizeBytes.get(), s3ObjectCount.get()
            )
        }.onFailure {
            log.warn("Failed to refresh S3 storage metrics from CloudWatch: ${it.message}")
            // Intentionally do NOT reset AtomicLong – keep the last known value in Prometheus
        }
    }

    /**
     * Queries CloudWatch for a single S3 storage metric.
     * AWS publishes S3 metrics daily; we request the last 2 days and take
     * the most recent data point.
     */
    private fun queryCloudWatch(client: CloudWatchClient, metricName: String, storageType: String): Long {
        val response = client.getMetricStatistics(
            GetMetricStatisticsRequest.builder()
                .namespace("AWS/S3")
                .metricName(metricName)
                .dimensions(
                    Dimension.builder().name("BucketName").value(bucketName).build(),
                    Dimension.builder().name("StorageType").value(storageType).build()
                )
                .startTime(Instant.now().minus(2, ChronoUnit.DAYS))
                .endTime(Instant.now())
                .period(86400)
                .statistics(Statistic.AVERAGE)
                .build()
        )

        return response.datapoints()
            .maxByOrNull { it.timestamp() }
            ?.average()
            ?.toLong()
            ?: -1L
    }

    // ── CloudWatch client factory ─────────────────────────────────────────────

    private fun buildCloudWatchClient(): CloudWatchClient? {
        if (accessKey.isBlank() || secretKey.isBlank()) return null
        val credentials = AwsBasicCredentials.create(accessKey, secretKey)
        return CloudWatchClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .build()
    }
}
