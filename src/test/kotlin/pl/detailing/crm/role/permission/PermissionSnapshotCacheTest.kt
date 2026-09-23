package pl.detailing.crm.role.permission

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCache
import org.springframework.data.redis.core.StringRedisTemplate
import pl.detailing.crm.config.CacheConfig
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Runs [PermissionSnapshotCache.snapshot] through a real Spring caching proxy with an
 * in-memory cache manager, so the `@Cacheable` SpEL key expression is actually evaluated.
 *
 * Regression guard: UserId/StudioId are Kotlin value classes and are inlined to raw UUIDs
 * in bytecode — a key expression referencing their `.value` property blows up at runtime
 * with SpEL EL1008E even though the code compiles. A plain unit test with MockK never
 * evaluates the expression; this test does.
 */
class PermissionSnapshotCacheTest {

    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()

    private lateinit var context: AnnotationConfigApplicationContext

    @Configuration
    @EnableCaching
    open class CachingTestConfig {
        @Bean open fun cacheManager(): CacheManager = ConcurrentMapCacheManager()
    }

    @AfterEach
    fun closeContext() {
        if (::context.isInitialized) context.close()
    }

    private fun proxiedCache(): PermissionSnapshotCache {
        context = AnnotationConfigApplicationContext()
        context.register(CachingTestConfig::class.java)
        // Jawny Supplier: goła lambda dopasowuje się do przeciążenia z BeanDefinitionCustomizer
        // i Spring próbuje autowire'ować konstruktor zamiast użyć naszej fabryki.
        context.registerBean(
            PermissionSnapshotCache::class.java,
            java.util.function.Supplier {
                PermissionSnapshotCache(
                    userRepository,
                    roleRepository,
                    context.getBean(CacheManager::class.java),
                    mockk<StringRedisTemplate>(relaxed = true)
                )
            }
        )
        context.refresh()
        return context.getBean(PermissionSnapshotCache::class.java)
    }

    @Test
    fun `key expression evaluates and the second call is served from cache`() {
        val cache = proxiedCache()
        val userId = UUID.randomUUID()
        val studioId = UUID.randomUUID()
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns null

        val first = cache.snapshot(userId, studioId)
        val second = cache.snapshot(userId, studioId)

        assertEquals(first, second)
        verify(exactly = 1) { userRepository.findByIdAndStudioId(userId, studioId) }
    }

    @Test
    fun `cache key separates users and studios`() {
        val cache = proxiedCache()
        val studioId = UUID.randomUUID()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        every { userRepository.findByIdAndStudioId(any(), any()) } returns null

        cache.snapshot(userA, studioId)
        cache.snapshot(userB, studioId)

        verify(exactly = 1) { userRepository.findByIdAndStudioId(userA, studioId) }
        verify(exactly = 1) { userRepository.findByIdAndStudioId(userB, studioId) }
    }

    /**
     * Regression guard: evictStudio matched `crm:v3:` keys while the cache manager wrote
     * `crm:v4:` ones, so editing a role's permissions evicted nothing and revoked permissions
     * stayed in force until the TTL ran out. The pattern is checked against the key the real
     * cache manager configuration produces, not against a copy of the prefix.
     */
    @Test
    fun `evictStudio pattern matches the keys the shared cache manager writes`() {
        val manager = CacheConfig().cacheManager(mockk(relaxed = true))
        manager.initializeCaches()
        val redisCache = manager.getCache(PermissionSnapshotCache.CACHE_NAME) as RedisCache
        val keyPrefix = redisCache.cacheConfiguration.getKeyPrefixFor(PermissionSnapshotCache.CACHE_NAME)
        val studio = StudioId(UUID.randomUUID())
        val otherStudio = StudioId(UUID.randomUUID())
        val writtenKey = keyPrefix + PermissionSnapshotCache.cacheKey(UserId(UUID.randomUUID()), studio)
        val otherStudioKey = keyPrefix + PermissionSnapshotCache.cacheKey(UserId(UUID.randomUUID()), otherStudio)

        val pattern = PermissionSnapshotCache.studioKeyPattern(studio)

        assertTrue(pattern.endsWith("*") && pattern.count { it == '*' } == 1, pattern)
        assertTrue(writtenKey.startsWith(pattern.removeSuffix("*")), "$pattern vs $writtenKey")
        assertFalse(otherStudioKey.startsWith(pattern.removeSuffix("*")))
    }
}
