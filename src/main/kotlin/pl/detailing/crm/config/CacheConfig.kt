package pl.detailing.crm.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Redis cache configuration.
 *
 * Cache regions:
 * - "studio-entitlements" — 5-minute TTL, evicted immediately on any subscription mutation.
 *   Key: studioId (UUID as String). Value: serialized [StudioEntitlements].
 * - "user-permissions" — 60-second TTL, evicted on role assignment/edit/deletion.
 *   Key: "{studioId}:{userId}". Value: serialized PermissionsSnapshot. The short TTL is a
 *   safety net for eviction paths that don't know the affected users; explicit eviction
 *   keeps permission revocation effectively immediate.
 *
 * Uses a Redis-specific ObjectMapper ([redisObjectMapper]) with:
 *   - KotlinModule  — so Kotlin data classes deserialize via their primary constructor
 *   - JavaTimeModule — `Instant` w stanie rozliczeniowym uprawnień
 *   - DefaultTyping — embeds "@class" in stored JSON so the correct type is restored on read
 *     (without this, Jackson returns LinkedHashMap instead of the target class)
 */
@Configuration
@EnableCaching
class CacheConfig : CachingConfigurer {

    companion object {
        /**
         * Prefix of every cache key in Redis. Code that reaches cache keys directly (e.g.
         * dropping all entries of a studio by pattern) must take it from here — bumping the
         * version only in the cache manager left such a pattern pointing at dead keys.
         *
         * v5: [pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements] dostał stan
         * rozliczeniowy i daty wyłączeń modułów — wpisy v4 nie mają tych pól.
         */
        const val CACHE_KEY_PREFIX = "crm:v5:"

        /**
         * Mapper wartości w Redisie — wydzielony, żeby test mógł przepuścić przez niego to,
         * co naprawdę trafia do cache'u. Testy integracyjne używają cache'u w pamięci, który
         * niczego nie serializuje: tak przeszedł niezauważony `StudioEntitlements` z datami
         * (`Instant`) bez [JavaTimeModule] — każdy zapis do cache'u rzucał wyjątek, a z nim
         * każde sprawdzenie uprawnień (przegląd planu naprawczego).
         */
        fun redisObjectMapper(): ObjectMapper {
            // Allow-list instead of LaissezFaire: `@class` in a cached JSON blob names the type
            // Jackson instantiates on read. Whoever can write to Redis must not get to pick a
            // gadget class — only our own types and the JDK value/collection types we cache.
            val allowedTypes = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("pl.detailing.crm.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.math.")
                .allowIfSubType("kotlin.")
                .build()

            return ObjectMapper()
                .registerModule(kotlinModule())
                .registerModule(JavaTimeModule())
                .activateDefaultTyping(
                    allowedTypes,
                    ObjectMapper.DefaultTyping.EVERYTHING,
                    JsonTypeInfo.As.PROPERTY
                )
        }
    }

    /**
     * Awaria cache'u nie może położyć API: błąd odczytu = brak wpisu (idzie do bazy), błąd
     * zapisu = brak zapisu. Oba logowane — w logu mają być widoczne, a nie zamienione na 500
     * na każdym żądaniu. Błędy unieważniania przechodzą dalej: przemilczane zostawiłyby w cache'u
     * odebrane uprawnienia.
     */
    override fun errorHandler(): CacheErrorHandler = object : SimpleCacheErrorHandler() {
        override fun handleCacheGetError(exception: RuntimeException, cache: Cache, key: Any) {
            logger.error("Odczyt z cache'u {} (klucz {}) nie powiódł się — czytam z bazy", cache.name, key, exception)
        }

        override fun handleCachePutError(exception: RuntimeException, cache: Cache, key: Any, value: Any?) {
            logger.error("Zapis do cache'u {} (klucz {}) nie powiódł się — wartość nie trafi do cache'u", cache.name, key, exception)
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    @Primary
    fun cacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
        val jsonSerializer = RedisSerializationContext.SerializationPair
            .fromSerializer(GenericJackson2JsonRedisSerializer(redisObjectMapper()))

        val defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            .serializeValuesWith(jsonSerializer)
            .disableCachingNullValues()
            .prefixCacheNameWith(CACHE_KEY_PREFIX)

        val entitlementsConfig = defaultConfig.entryTtl(Duration.ofMinutes(5))
        val userPermissionsConfig = defaultConfig.entryTtl(Duration.ofSeconds(60))
        // Distinct photo tag names per studio; evicted explicitly on tag updates,
        // TTL is a safety net.
        val galleryTagsConfig = defaultConfig.entryTtl(Duration.ofMinutes(10))

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withCacheConfiguration("studio-entitlements", entitlementsConfig)
            .withCacheConfiguration("user-permissions", userPermissionsConfig)
            .withCacheConfiguration("gallery-available-tags", galleryTagsConfig)
            .build()
    }
}
