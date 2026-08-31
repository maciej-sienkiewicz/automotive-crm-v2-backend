package pl.detailing.crm.audit.infrastructure

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.AbstractQuery
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.stereotype.Repository
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActorType
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditSeverity
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.infrastructure.VisitEntity
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
    /**
     * Akcje wycięte z wyniku. Odsiew idzie zapytaniem, nie filtrem na liście wyników —
     * strona keysetowa musi zwrócić tyle wierszy, ile obiecuje kursorowi, a wycinanie
     * po stronie aplikacji zmniejszałoby stronę i zaburzało „czy jest więcej".
     */
    val excludedActions: List<AuditAction>? = null,
    /**
     * Wizyty wycięte z wyniku — patrz [pl.detailing.crm.audit.feed.FeedVisitVisibility].
     * Jak [excludedActions]: odsiew idzie zapytaniem, nie filtrem na liście wyników.
     */
    val hiddenVisitIds: List<UUID>? = null,
    /**
     * Odsiewa zdarzenia wizyt, których dla użytkownika nie ma: szkiców przyjęcia
     * (DRAFT) oraz szkiców anulowanych, po których został sam wpis w dzienniku.
     * Warunek działa na kolumnie kontekstu — [hiddenVisitIds] domyka to samo po
     * `entity_id` dla wpisów pisanych bez kontekstu.
     */
    val requireLiveVisitContext: Boolean = false,
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

        val predicates = buildPredicates(builder, query, root, filters).toMutableList()

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

        query.where(*buildPredicates(builder, query, root, filters).toTypedArray())
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
        query.where(*buildPredicates(builder, query, root, filters).toTypedArray())

        return entityManager.createQuery(query).singleResult ?: 0L
    }

    private fun buildPredicates(
        builder: CriteriaBuilder,
        query: AbstractQuery<*>,
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
        filters.excludedActions?.takeIf { it.isNotEmpty() }?.let {
            predicates += builder.not(root.get<AuditAction>("action").`in`(it))
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

        /*
         * Odsiew wizyt niewidocznych w Aktywności. Dwa warunki, bo zdarzenie wiąże się
         * z wizytą na dwa sposoby: kolumną kontekstu (visit_id, wypełnianą tam, gdzie
         * piszący podał AuditContext) albo własnym entity_id wpisu z modułu VISIT.
         *
         * Każdy warunek przepuszcza NULL jawnie. `NOT (visit_id IN (...))` dla wiersza
         * bez kontekstu daje w SQL-u NULL, a nie TRUE — bez `IS NULL` obok tego
         * wykluczenia zniknęłaby z feedu każda pozycja spoza modułu wizyt.
         */
        filters.hiddenVisitIds?.takeIf { it.isNotEmpty() }?.let { hidden ->
            val hiddenAsText = hidden.map { it.toString() }
            predicates += builder.or(
                builder.isNull(root.get<UUID>("visitId")),
                builder.not(root.get<UUID>("visitId").`in`(hidden))
            )
            predicates += builder.or(
                builder.notEqual(root.get<AuditModule>("module"), AuditModule.VISIT),
                builder.isNull(root.get<String>("entityId")),
                builder.not(root.get<String>("entityId").`in`(hiddenAsText))
            )
        }

        /*
         * Wizyta, której nie ma — ani jako wiersz feedu, ani jako link.
         *
         * Dwa przypadki, jeden warunek: szkic przyjęcia (DRAFT), który jeszcze nie stał
         * się wizytą, i szkic anulowany, którego rekord zniknął (anulowanie szkicu jest
         * twardym usunięciem — jedynym w całym module wizyt; „usunięcie" wizyty
         * potwierdzonej to `deleted_at`, więc jej historia zostaje). Bez tego drugiego
         * przypadku wpis „Rozpoczęto wizytę" wracałby do Aktywności dokładnie w chwili,
         * w której wizyta przestawała istnieć — z linkiem prowadzącym donikąd.
         *
         * Wiersze bez kontekstu wizyty przechodzą nietknięte; to m.in. samo
         * „Anulowano wizytę", które ma pozostać śladem po przerwanym przyjęciu.
         */
        if (filters.requireLiveVisitContext) {
            val liveVisit = query.subquery(UUID::class.java)
            val visit = liveVisit.from(VisitEntity::class.java)
            liveVisit.select(visit.get<UUID>("id"))
            liveVisit.where(
                builder.equal(visit.get<UUID>("id"), root.get<UUID>("visitId")),
                builder.notEqual(visit.get<VisitStatus>("status"), VisitStatus.DRAFT)
            )
            predicates += builder.or(
                builder.isNull(root.get<UUID>("visitId")),
                builder.exists(liveVisit)
            )
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
