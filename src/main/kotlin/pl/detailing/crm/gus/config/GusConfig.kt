package pl.detailing.crm.gus.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.web.client.RestTemplate
import pl.detailing.crm.gus.adapter.bir.GusCompanyDataProviderAdapter
import pl.detailing.crm.gus.adapter.bir.GusSessionManager
import pl.detailing.crm.gus.adapter.bir.soap.GusRawSoapClient
import pl.detailing.crm.gus.application.GusCompanyService
import pl.detailing.crm.gus.exception.CompanyNotFoundException
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.exception.InvalidNipException
import pl.detailing.crm.gus.port.CompanyDataProvider
import java.time.Duration

@Configuration
@EnableCaching
@EnableConfigurationProperties(GusProperties::class)
class GusConfig {

    // ─── HTTP client ──────────────────────────────────────────────────────────

    @Bean("gusRestTemplate")
    fun gusRestTemplate(props: GusProperties): RestTemplate {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.connectTimeoutMs)
            setReadTimeout(props.readTimeoutMs)
        }
        return RestTemplate(factory).apply {
            // GUS returns MTOM (multipart/related) with no charset in the outer Content-Type.
            // StringHttpMessageConverter defaults to ISO-8859-1 in that case, which corrupts
            // Polish characters. Override to UTF-8 so all GUS responses are decoded correctly.
            messageConverters.filterIsInstance<StringHttpMessageConverter>()
                .forEach { it.defaultCharset = Charsets.UTF_8 }
        }
    }

    // ─── Adapter layer ────────────────────────────────────────────────────────

    @Bean
    fun gusRawSoapClient(
        @Qualifier("gusRestTemplate") restTemplate: RestTemplate,
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
                CompanyNotFoundException::class.java,
                InvalidNipException::class.java
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
        // GenericJackson2JsonRedisSerializer's no-arg constructor creates a bare ObjectMapper
        // without JavaTimeModule, which can't handle LocalDate. We must supply our own
        // ObjectMapper with JavaTimeModule + default typing (needed for Spring Redis to
        // reconstruct the correct runtime type on cache read).
        val redisMapper = ObjectMapper().apply {
            registerKotlinModule()   // Kotlin data class deserialization (no no-arg constructor)
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
            )
        }

        val cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofHours(props.cacheTtlHours))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJackson2JsonRedisSerializer(redisMapper)
                )
            )
            .disableCachingNullValues()

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(cacheConfig)
            .withCacheConfiguration("gus-company-v2", cacheConfig)
            .build()
    }
}
