package pl.detailing.crm.vehicle.comments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.visit.infrastructure.VisitCommentRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

@Service
class GetVehicleCommentsHandler(
    private val visitCommentRepository: VisitCommentRepository,
    private val visitRepository: VisitRepository
) {
    suspend fun handle(command: GetVehicleCommentsCommand): GetVehicleCommentsResult =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(command.page - 1, command.limit)
            val commentsPage = visitCommentRepository.findByVehicleIdAndStudioId(
                vehicleId = command.vehicleId.value,
                studioId = command.studioId.value,
                pageable = pageable
            )

            val visitIds = commentsPage.content.map { it.visitId }.distinct()
            val visitsById = if (visitIds.isNotEmpty()) {
                visitIds.mapNotNull { visitId ->
                    visitRepository.findByIdAndStudioId(visitId, command.studioId.value)
                }.associateBy { it.id }
            } else emptyMap()

            val comments = commentsPage.content.map { comment ->
                val visit = visitsById[comment.visitId]
                VehicleCommentInfo(
                    id = comment.id.toString(),
                    content = comment.content,
                    type = comment.type.name,
                    createdAt = comment.createdAt,
                    createdBy = comment.createdBy.toString(),
                    createdByName = comment.createdByName,
                    visitId = comment.visitId.toString(),
                    visitTitle = visit?.title ?: "",
                    visitDate = visit?.scheduledDate ?: comment.createdAt
                )
            }

            GetVehicleCommentsResult(
                comments = comments,
                pagination = VehicleCommentsPaginationInfo(
                    currentPage = command.page,
                    totalPages = commentsPage.totalPages,
                    totalItems = commentsPage.totalElements.toInt(),
                    itemsPerPage = command.limit
                )
            )
        }
}

data class GetVehicleCommentsCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val page: Int = 1,
    val limit: Int = 10
)

data class GetVehicleCommentsResult(
    val comments: List<VehicleCommentInfo>,
    val pagination: VehicleCommentsPaginationInfo
)

data class VehicleCommentInfo(
    val id: String,
    val content: String,
    val type: String,
    val createdAt: Instant,
    val createdBy: String,
    val createdByName: String,
    val visitId: String,
    val visitTitle: String,
    val visitDate: Instant
)

data class VehicleCommentsPaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
