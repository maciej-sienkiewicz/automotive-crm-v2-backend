package pl.detailing.crm.role.permission

import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Raw role-resolution result cached per (studio, user) — see [PermissionSnapshotCache].
 *
 * [permissionCodes] is the role's permission set already closed over the dependency
 * graph, stored as canonical enum names. Feature-entitlement filtering is deliberately
 * NOT part of the snapshot: entitlements have their own cache and eviction lifecycle
 * ("studio-entitlements"), so subscription changes take effect independently of this cache.
 */
data class PermissionsSnapshot(
    val owner: Boolean,
    val permissionCodes: List<String>
)

/**
 * Redis-cached resolution of "user → owner flag + closed permission set".
 *
 * Rationale: [pl.detailing.crm.shared.pii.PiiAccessFilter] and every `@RequiresPermission`
 * endpoint resolve permissions on each request; uncached this costs two queries per hit
 * (users + studio_roles with an EAGER permission collection). The cache is short-lived
 * (60 s, see CacheConfig) **and** explicitly evicted on every mutation that can change
 * the answer, so a revoked permission is enforced immediately:
 * - role re-assignment → [evictUser] (AssignRoleHandler),
 * - role permission edit / role deletion → [evictStudio] (Update/DeleteRoleHandler —
 *   affected users are unknown, so the whole studio's entries are dropped).
 *
 * The value is never null (owner is a flag, not a null sentinel) so it is compatible
 * with `disableCachingNullValues()` on the shared cache manager.
 */
@Service
class PermissionSnapshotCache(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val cacheManager: CacheManager,
    private val stringRedisTemplate: StringRedisTemplate
) {
    companion object {
        const val CACHE_NAME = "user-permissions"

        /** Must match CacheConfig's `prefixCacheNameWith` + Spring's `cacheName::key` scheme. */
        private const val REDIS_KEY_PREFIX = "crm:v3:$CACHE_NAME::"

        fun cacheKey(userId: UserId, studioId: StudioId) = "${studioId.value}:${userId.value}"
    }

    /**
     * Parameters are raw [UUID]s, not the [UserId]/[StudioId] value classes, on purpose:
     * Kotlin inlines value-class parameters to their underlying type in bytecode, so a SpEL
     * key like `#studioId.value` would be evaluated against a plain UUID and fail at runtime.
     * With UUIDs the key expression is a plain string concatenation (UUID → toString),
     * consistent with [cacheKey] and the [evictStudio] key pattern.
     */
    @Cacheable(CACHE_NAME, key = "#studioId + ':' + #userId")
    fun snapshot(userId: UUID, studioId: UUID): PermissionsSnapshot {
        val userEntity = userRepository.findByIdAndStudioId(userId, studioId)
            ?: return PermissionsSnapshot(owner = false, permissionCodes = emptyList())

        if (userEntity.isOwner) return PermissionsSnapshot(owner = true, permissionCodes = emptyList())

        val customRoleId = userEntity.customRoleId
            ?: return PermissionsSnapshot(owner = false, permissionCodes = emptyList())

        val roleEntity = roleRepository.findByIdAndStudioId(customRoleId, studioId)
            ?: return PermissionsSnapshot(owner = false, permissionCodes = emptyList())

        // toDomain closes over the dependency graph (ancestors + implications).
        return PermissionsSnapshot(
            owner = false,
            permissionCodes = roleEntity.toDomain().permissions.map { it.name }.sorted()
        )
    }

    /** Drops the cached snapshot for a single user (role re-assignment, account changes). */
    fun evictUser(userId: UserId, studioId: StudioId) {
        cacheManager.getCache(CACHE_NAME)?.evict(cacheKey(userId, studioId))
    }

    /**
     * Drops every cached snapshot in the studio. Used when a role's permission set changes —
     * any number of users may hold that role, so all entries are invalidated.
     */
    fun evictStudio(studioId: StudioId) {
        val keys = stringRedisTemplate.keys("$REDIS_KEY_PREFIX${studioId.value}:*")
        if (keys.isNotEmpty()) {
            stringRedisTemplate.delete(keys)
        }
    }
}
