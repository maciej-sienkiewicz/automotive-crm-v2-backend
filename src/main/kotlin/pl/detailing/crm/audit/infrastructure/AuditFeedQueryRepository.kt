package pl.detailing.crm.audit.infrastructure

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.stereotype.Repository
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActorType
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditSeverity
import java.time.Instant
import java.util.UUID

/**
 * Position in the feed. Two components because `created_at` alone is not unique — several
 * entries written inside one transaction share a timestamp, and a cursor that ignored the
 * tie-break would drop or repeat them at the page boundary.
 */
data class AuditFeedCursor(
    val createdAt: Instant,
    val id: UUID
)

/**
 * Everything the feed can be narrowed by. All fields are optional; an absent filter means
 * "no restriction" rather than "match null".
 */
data class AuditFeedFilters(
    val studioId: UUID,
    val modules: List<AuditModule>? = null,
    val actions: List<AuditAction>? = null,
    val actorTypes: List<AuditActorType>? = null,
    val severities: List<AuditSeverity>? = null,
    val channels: List<AuditChannel>? = null,
    val actorId: UUID? = null,
    val customerId: UUID? = null,
    val vehicleId: UUID? = null,
    val visitId: UUID? = null,
    val correlationId: UUID? = null,
    val module: AuditModule? = null,
    val entityId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    /** Free-text match over the actor and the names of the objects involved. */
    val search: String? = null
)

/**
 * Every read of the audit log goes through here.
 *
 * Built on the Criteria API rather than a JPQL `@Query`, because the previous
 * `(:modules IS NULL OR a.module IN :modules)` form has to be given a null collection to
 * mean "no filter" — behaviour Hibernate has changed between versions, and which leaves
 * the planner unable to use the module index even when a filter *is* present. Composing
 * predicates means the emitted SQL only contains the filters actually asked for.
 *
 * [findPage] is keyset-paged and is what the activity feed uses: no offset (which drifts
 * as new rows arrive and degrades the deeper it goes) and no `count(*)` over the largest
 * table in the system on every request. [findOffsetPage] / [count] exist only for the
 * page-numbered legacy endpoints.
 */
@Repository
class AuditFeedQueryRepository(
    @PersistenceContext private val entityManager: EntityManager
) {

    /** Keyset page, newest first. Pass `limit + 1` to learn whether another page exists. */
    fun findPage(
        filters: AuditFeedFilters,
        cursor: AuditFeedCursor?,
        limit: Int
    ): List<AuditLogEntity> {
        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(AuditLogEntity::class.java)
        val root = query.from(AuditLogEntity::class.java)

        val predicates = buildPredicates(builder, root, filters).toMutableList()

        cursor?.let {
            // Strictly "older than the cursor", with id as the tie-break inside one timestamp.
            predicates += builder.or(
                builder.lessThan(root.get<Instant>("createdAt"), it.createdAt),
                builder.and(
                    builder.equal(root.get<Instant>("createdAt"), it.createdAt),
                    builder.lessThan(root.get<UUID>("id"), it.id)
                )
            )
        }

        query.where(*predicates.toTypedArray())
        query.orderBy(
            builder.desc(root.get<Instant>("createdAt")),
            builder.desc(root.get<UUID>("id"))
        )

        return entityManager.createQuery(query)
            .setMaxResults(limit)
            .resultList
    }

    /** Offset page, newest first. Only for the page-numbered legacy endpoints. */
    fun findOffsetPage(filters: AuditFeedFilters, offset: Int, limit: Int): List<AuditLogEntity> {
        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(AuditLogEntity::class.java)
        val root = query.from(AuditLogEntity::class.java)

        query.where(*buildPredicates(builder, root, filters).toTypedArray())
        query.orderBy(
            builder.desc(root.get<Instant>("createdAt")),
            builder.desc(root.get<UUID>("id"))
        )

        return entityManager.createQuery(query)
            .setFirstResult(offset)
            .setMaxResults(limit)
            .resultList
    }

    fun count(filters: AuditFeedFilters): Long {
        val builder = entityManager.criteriaBuilder
        // javaObjectType, not `Long::class.java` — the latter is the `long` primitive,
        // which the Criteria API cannot use as a query result type.
        val query = builder.createQuery(Long::class.javaObjectType)
        val root = query.from(AuditLogEntity::class.java)

        query.select(builder.count(root))
        query.where(*buildPredicates(builder, root, filters).toTypedArray())

        return entityManager.createQuery(query).singleResult ?: 0L
    }

    private fun buildPredicates(
        builder: CriteriaBuilder,
        root: Root<AuditLogEntity>,
        filters: AuditFeedFilters
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()
        predicates += builder.equal(root.get<UUID>("studioId"), filters.studioId)

        filters.modules?.takeIf { it.isNotEmpty() }?.let {
            predicates += root.get<AuditModule>("module").`in`(it)
        }
        filters.actions?.takeIf { it.isNotEmpty() }?.let {
            predicates += root.get<AuditAction>("action").`in`(it)
        }
        filters.actorTypes?.takeIf { it.isNotEmpty() }?.let {
            predicates += root.get<AuditActorType>("actorType").`in`(it)
        }
        filters.severities?.takeIf { it.isNotEmpty() }?.let {
            predicates += root.get<AuditSeverity>("severity").`in`(it)
        }
        filters.channels?.takeIf { it.isNotEmpty() }?.let {
            predicates += root.get<AuditChannel>("channel").`in`(it)
        }

        filters.actorId?.let { predicates += builder.equal(root.get<UUID>("userId"), it) }

        /*
         * Zawężenie „wszystko wokół jednego obiektu" ma DWA źródła prawdy i musi
         * czytać oba. Kolumna kontekstu (customer_id itd.) jest wypełniana tylko
         * tam, gdzie piszący zdarzenie podał AuditContext — a wpisy o samym
         * obiekcie (moduł CUSTOMER, edycja danych klienta) niosą jego id w
         * entity_id, nie w kolumnie kontekstu. Sam warunek po kolumnie zwracał
         * więc historię BEZ zdarzeń własnych obiektu, a karta klienta świeciła
         * pustką mimo pełnego dziennika.
         */
        filters.customerId?.let {
            predicates += builder.or(
                builder.equal(root.get<UUID>("customerId"), it),
                builder.and(
                    builder.equal(root.get<AuditModule>("module"), AuditModule.CUSTOMER),
                    builder.equal(root.get<String>("entityId"), it.toString())
                )
            )
        }
        filters.vehicleId?.let {
            predicates += builder.or(
                builder.equal(root.get<UUID>("vehicleId"), it),
                builder.and(
                    builder.equal(root.get<AuditModule>("module"), AuditModule.VEHICLE),
                    builder.equal(root.get<String>("entityId"), it.toString())
                )
            )
        }
        filters.visitId?.let {
            predicates += builder.or(
                builder.equal(root.get<UUID>("visitId"), it),
                builder.and(
                    builder.equal(root.get<AuditModule>("module"), AuditModule.VISIT),
                    builder.equal(root.get<String>("entityId"), it.toString())
                )
            )
        }
        filters.correlationId?.let { predicates += builder.equal(root.get<UUID>("correlationId"), it) }
        filters.module?.let { predicates += builder.equal(root.get<AuditModule>("module"), it) }
        filters.entityId?.let { predicates += builder.equal(root.get<String>("entityId"), it) }

        filters.from?.let { predicates += builder.greaterThanOrEqualTo(root.get<Instant>("createdAt"), it) }
        filters.to?.let { predicates += builder.lessThanOrEqualTo(root.get<Instant>("createdAt"), it) }

        filters.search?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
            val pattern = "%${term.lowercase()}%"
            predicates += builder.or(
                builder.like(builder.lower(root.get<String>("userDisplayName")), pattern),
                builder.like(builder.lower(root.get<String>("entityDisplayName")), pattern),
                builder.like(builder.lower(root.get<String>("customerName")), pattern),
                builder.like(builder.lower(root.get<String>("vehicleName")), pattern),
                builder.like(builder.lower(root.get<String>("visitName")), pattern)
            )
        }

        return predicates
    }
}
