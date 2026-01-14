package pl.detailing.crm.appointmentcolor

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.appointmentcolor.create.CreateAppointmentColorCommand
import pl.detailing.crm.appointmentcolor.create.CreateAppointmentColorHandler
import pl.detailing.crm.appointmentcolor.create.CreateAppointmentColorRequest
import pl.detailing.crm.appointmentcolor.delete.DeleteAppointmentColorCommand
import pl.detailing.crm.appointmentcolor.delete.DeleteAppointmentColorHandler
import pl.detailing.crm.appointmentcolor.getbyid.AppointmentColorResponse
import pl.detailing.crm.appointmentcolor.getbyid.GetAppointmentColorByIdHandler
import pl.detailing.crm.appointmentcolor.list.AppointmentColorListItem
import pl.detailing.crm.appointmentcolor.list.ListAppointmentColorsHandler
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorCommand
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorHandler
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorRequest
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole

@RestController
@RequestMapping("/api/v1/appointment-colors")
class AppointmentColorController(
    private val createAppointmentColorHandler: CreateAppointmentColorHandler,
    private val listAppointmentColorsHandler: ListAppointmentColorsHandler,
    private val getAppointmentColorByIdHandler: GetAppointmentColorByIdHandler,
    private val updateAppointmentColorHandler: UpdateAppointmentColorHandler,
    private val deleteAppointmentColorHandler: DeleteAppointmentColorHandler
) {

    @GetMapping
    fun getColors(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean
    ): ResponseEntity<AppointmentColorListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        var colors = listAppointmentColorsHandler.handle(principal.studioId, showInactive)

        if (search.isNotBlank()) {
            colors = colors.filter { it.name.contains(search, ignoreCase = true) }
        }

        val totalItems = colors.size
        val start = (page - 1) * limit
        val end = minOf(start + limit, totalItems)
        val paginatedColors = if (start < totalItems) {
            colors.subList(start, end)
        } else {
            emptyList()
        }

        ResponseEntity.ok(
            AppointmentColorListResponse(
                colors = paginatedColors,
                pagination = PaginationInfo(
                    currentPage = page,
                    totalPages = if (totalItems > 0) ((totalItems + limit - 1) / limit) else 0,
                    totalItems = totalItems,
                    itemsPerPage = limit
                )
            )
        )
    }

    @GetMapping("/{id}")
    fun getColorById(@PathVariable id: String): ResponseEntity<AppointmentColorResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val colorId = AppointmentColorId.fromString(id)

        val color = getAppointmentColorByIdHandler.handle(colorId, principal.studioId)

        ResponseEntity.ok(color)
    }

    @PostMapping
    fun createColor(@RequestBody request: CreateAppointmentColorRequest): ResponseEntity<AppointmentColorResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create appointment colors")
        }

        val command = CreateAppointmentColorCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            hexColor = request.hexColor
        )

        val result = createAppointmentColorHandler.handle(command)

        val response = getAppointmentColorByIdHandler.handle(result.colorId, principal.studioId)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response)
    }

    @PutMapping("/{id}")
    fun updateColor(
        @PathVariable id: String,
        @RequestBody request: UpdateAppointmentColorRequest
    ): ResponseEntity<AppointmentColorResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update appointment colors")
        }

        val colorId = AppointmentColorId.fromString(id)

        val command = UpdateAppointmentColorCommand(
            colorId = colorId,
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            hexColor = request.hexColor
        )

        updateAppointmentColorHandler.handle(command)

        val response = getAppointmentColorByIdHandler.handle(colorId, principal.studioId)

        ResponseEntity.ok(response)
    }

    @DeleteMapping("/{id}")
    fun deleteColor(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can delete appointment colors")
        }

        val colorId = AppointmentColorId.fromString(id)

        val command = DeleteAppointmentColorCommand(
            colorId = colorId,
            studioId = principal.studioId
        )

        deleteAppointmentColorHandler.handle(command)

        ResponseEntity.noContent().build()
    }
}

data class AppointmentColorListResponse(
    val colors: List<AppointmentColorListItem>,
    val pagination: PaginationInfo
)

data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
