package pl.detailing.crm.audit.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActorType
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.AuditSeverity
import pl.detailing.crm.audit.infrastructure.AuditFeedCursor
import pl.detailing.crm.audit.infrastructure.AuditFeedFilters
import pl.detailing.crm.audit.infrastructure.AuditFeedQueryRepository
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class GetAuditFeedCommand(
    val studioId: StudioId,
    val limit: Int = DEFAULT_LIMIT,
    val cursor: String? = null,
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
    val search: String? = null
) {
    companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}

/**
 * Serves the owner-facing activity feed.
 *
 * Fetches one row more than asked for: if it comes back, there is another page and the
 * extra row is dropped. That answers "is there more?" without a `count(*)` over the
 * largest table in the system.
 */
@Service
class GetAuditFeedHandler(
    private val auditFeedQueryRepository: AuditFeedQueryRepository,
    private val auditService: AuditService,
    private val renderer: AuditFeedRenderer
) {
    private val logger = LoggerFactory.getLogger(GetAuditFeedHandler::class.java)

    suspend fun handle(command: GetAuditFeedCommand): AuditFeedResponse = withContext(Dispatchers.IO) {
        val limit = command.limit.coerceIn(1, GetAuditFeedCommand.MAX_LIMIT)

        val rows = auditFeedQueryRepository.findPage(
            filters = AuditFeedFilters(
                studioId = command.studioId.value,
                modules = command.modules,
                actions = command.actions,
                actorTypes = command.actorTypes,
                severities = command.severities,
                channels = command.channels,
                actorId = command.actorId,
                customerId = command.customerId,
                vehicleId = command.vehicleId,
                visitId = command.visitId,
                correlationId = command.correlationId,
                module = command.module,
                entityId = command.entityId,
                from = command.from,
                to = command.to,
                search = command.search
            ),
            cursor = AuditFeedCursorCodec.decode(command.cursor),
            limit = limit + 1
        )

        val hasMore = rows.size > limit
        val page = if (hasMore) rows.take(limit) else rows

        AuditFeedResponse(
            items = page.map { entity ->
                renderer.render(
                    entity.toDomain(
                        changesParser = auditService::deserializeChanges,
                        metadataParser = auditService::deserializeMetadata
                    )
                )
            },
            nextCursor = page.lastOrNull()
                ?.takeIf { hasMore }
                ?.let { AuditFeedCursorCodec.encode(AuditFeedCursor(it.createdAt, it.id)) },
            hasMore = hasMore
        )
    }
}
