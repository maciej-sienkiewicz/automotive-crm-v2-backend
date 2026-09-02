package pl.detailing.crm.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.springframework.cache.annotation.EnableCaching
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
 * Uses a Redis-specific ObjectMapper with:
 *   - KotlinModule  — so Kotlin data classes deserialize via their primary constructor
 *   - DefaultTyping — embeds "@class" in stored JSON so the correct type is restored on read
 *     (without this, Jackson returns LinkedHashMap instead of the target class)
 */
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    @Primary
    fun cacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
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

        val redisMapper = ObjectMapper()
            .registerModule(kotlinModule())
            .activateDefaultTyping(
                allowedTypes,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY
            )

        val jsonSerializer = RedisSerializationContext.SerializationPair
            .fromSerializer(GenericJackson2JsonRedisSerializer(redisMapper))

        val defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            .serializeValuesWith(jsonSerializer)
            .disableCachingNullValues()
            .prefixCacheNameWith("crm:v4:")

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
