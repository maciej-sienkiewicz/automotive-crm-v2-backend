package pl.detailing.crm.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * AWS S3 configuration for document storage.
 * Provides S3Client and S3Presigner beans for file operations and presigned URL generation.
 */
@Configuration
class AwsS3Config {

    @Value("\${aws.s3.region:eu-central-1}")
    private lateinit var region: String

    @Value("\${aws.s3.access-key:}")
    private lateinit var accessKey: String

    @Value("\${aws.s3.secret-key:}")
    private lateinit var secretKey: String

    /**
     * Własny adres usługi zgodnej z S3 (MinIO, LocalStack, atrapa w testach
     * end-to-end). Pusty = AWS, czyli produkcja bez zmian. Z adresem włączamy
     * adresowanie ścieżką (`host/bucket/klucz`), bo lokalne serwery nie mają DNS-u
     * na `bucket.host`.
     */
    @Value("\${aws.s3.endpoint:}")
    private lateinit var endpoint: String

    private val s3Configuration: S3Configuration
        get() = S3Configuration.builder().pathStyleAccessEnabled(endpoint.isNotBlank()).build()

    @Bean
    fun s3Client(): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(region))
            .serviceConfiguration(s3Configuration)
        if (endpoint.isNotBlank()) builder.endpointOverride(URI.create(endpoint))

        // Use static credentials if provided, otherwise use default credential chain
        if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
            val credentials = AwsBasicCredentials.create(accessKey, secretKey)
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
        }

        return builder.build()
    }

    @Bean
    fun s3Presigner(): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(region))
            .serviceConfiguration(s3Configuration)
        if (endpoint.isNotBlank()) builder.endpointOverride(URI.create(endpoint))

        // Use static credentials if provided, otherwise use default credential chain
        if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
            val credentials = AwsBasicCredentials.create(accessKey, secretKey)
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
        }

        return builder.build()
    }
}
