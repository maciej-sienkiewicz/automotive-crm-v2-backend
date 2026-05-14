package pl.detailing.crm.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
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
        val redisMapper = ObjectMapper()
            .registerModule(kotlinModule())
            .activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
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

        val entitlementsConfig = defaultConfig.entryTtl(Duration.ofMinutes(5))

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withCacheConfiguration("studio-entitlements", entitlementsConfig)
            .build()
    }
}
