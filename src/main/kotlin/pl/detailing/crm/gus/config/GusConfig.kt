package pl.detailing.crm.gus.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import pl.detailing.crm.gus.adapter.bir.GusCompanyDataProviderAdapter
import pl.detailing.crm.gus.adapter.bir.GusSessionManager
import pl.detailing.crm.gus.adapter.bir.soap.GusRawSoapClient
import pl.detailing.crm.gus.application.GusCompanyService
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.port.CompanyDataProvider
import java.time.Duration

@Configuration
@EnableCaching
@EnableConfigurationProperties(GusProperties::class)
class GusConfig {

    // ─── HTTP client ──────────────────────────────────────────────────────────

    @Bean("gusRestTemplate")
    fun gusRestTemplate(
        builder: RestTemplateBuilder,
        props: GusProperties
    ) = builder
        .connectTimeout(Duration.ofMillis(props.connectTimeoutMs.toLong()))
        .readTimeout(Duration.ofMillis(props.readTimeoutMs.toLong()))
        .build()

    // ─── Adapter layer ────────────────────────────────────────────────────────

    @Bean
    fun gusRawSoapClient(
        @org.springframework.beans.factory.annotation.Qualifier("gusRestTemplate")
        restTemplate: org.springframework.web.client.RestTemplate,
        props: GusProperties
    ) = GusRawSoapClient(restTemplate, props.endpointUrl)

    @Bean
    fun gusSessionManager(soapClient: GusRawSoapClient, props: GusProperties) =
        GusSessionManager(soapClient, props.apiKey, props.sessionTtlMinutes)

    @Bean
    fun gusCircuitBreaker(props: GusProperties): CircuitBreaker {
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(props.circuitBreakerFailureRateThreshold)
            .waitDurationInOpenState(Duration.ofSeconds(props.circuitBreakerWaitDurationSeconds))
            .slidingWindowSize(props.circuitBreakerSlidingWindowSize)
            .permittedNumberOfCallsInHalfOpenState(3)
            .recordExceptions(GusServiceUnavailableException::class.java)
            .build()
        return CircuitBreaker.of("gus-bir", config)
    }

    @Bean
    fun gusRetry(props: GusProperties): Retry {
        val config = RetryConfig.custom<Any>()
            .maxAttempts(props.retryMaxAttempts)
            .waitDuration(Duration.ofMillis(props.retryInitialDelayMs))
            .retryExceptions(GusServiceUnavailableException::class.java)
            .ignoreExceptions(
                pl.detailing.crm.gus.exception.CompanyNotFoundException::class.java,
                pl.detailing.crm.gus.exception.InvalidNipException::class.java
            )
            .build()
        return Retry.of("gus-bir", config)
    }

    @Bean
    fun gusCompanyDataProvider(
        soapClient: GusRawSoapClient,
        sessionManager: GusSessionManager,
        circuitBreaker: CircuitBreaker,
        retry: Retry
    ): CompanyDataProvider = GusCompanyDataProviderAdapter(
        soapClient, sessionManager, circuitBreaker, retry
    )

    @Bean
    fun gusCompanyService(provider: CompanyDataProvider) = GusCompanyService(provider)

    // ─── Cache (Redis) ────────────────────────────────────────────────────────

    @Bean("gusCacheManager")
    fun gusCacheManager(
        connectionFactory: RedisConnectionFactory,
        props: GusProperties
    ): CacheManager {
        val cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofHours(props.cacheTtlHours))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJackson2JsonRedisSerializer()
                )
            )
            .disableCachingNullValues()

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(cacheConfig)
            .withCacheConfiguration("gus-company", cacheConfig)
            .build()
    }
}
