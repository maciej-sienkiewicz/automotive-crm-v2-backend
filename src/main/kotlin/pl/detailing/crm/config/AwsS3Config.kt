package pl.detailing.crm.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

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

    @Bean
    fun s3Client(): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(region))

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

        // Use static credentials if provided, otherwise use default credential chain
        if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
            val credentials = AwsBasicCredentials.create(accessKey, secretKey)
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
        }

        return builder.build()
    }
}
