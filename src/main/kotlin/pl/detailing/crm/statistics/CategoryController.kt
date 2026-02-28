package pl.detailing.crm.statistics

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.statistics.category.assignservices.AssignServicesCommand
import pl.detailing.crm.statistics.category.assignservices.AssignServicesHandler
import pl.detailing.crm.statistics.category.assignservices.AssignSingleServiceHandler
import pl.detailing.crm.statistics.category.assignservices.UnassignSingleServiceHandler
import pl.detailing.crm.statistics.category.create.CreateCategoryCommand
import pl.detailing.crm.statistics.category.create.CreateCategoryHandler
import pl.detailing.crm.statistics.category.delete.DeleteCategoryHandler
import pl.detailing.crm.statistics.category.get.CategoryDetail
import pl.detailing.crm.statistics.category.get.GetCategoryHandler
import pl.detailing.crm.statistics.category.list.CategoryListItem
import pl.detailing.crm.statistics.category.list.ListCategoriesHandler
import pl.detailing.crm.statistics.category.update.UpdateCategoryCommand
import pl.detailing.crm.statistics.category.update.UpdateCategoryHandler
import java.time.Instant

// ─── Request bodies ──────────────────────────────────────────────────────────

data class CreateCategoryRequest(
    val name: String,
    val description: String?,
    val color: String?
)

data class UpdateCategoryRequest(
    val name: String,
    val description: String?,
    val color: String?
)

data class AssignServicesRequest(
    /** Full replacement list of service IDs to assign to this category. */
    val serviceIds: List<String>
)

// ─── Response bodies ─────────────────────────────────────────────────────────

data class CreateCategoryResponse(
    val id: String,
    val name: String,
    val createdAt: Instant
)

data class CategoryListResponse(
    val categories: List<CategoryListItem>
)

// ─── Controller ──────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/service-categories")
class CategoryController(
    private val createCategoryHandler: CreateCategoryHandler,
    private val updateCategoryHandler: UpdateCategoryHandler,
    private val deleteCategoryHandler: DeleteCategoryHandler,
    private val assignServicesHandler: AssignServicesHandler,
    private val assignSingleServiceHandler: AssignSingleServiceHandler,
    private val unassignSingleServiceHandler: UnassignSingleServiceHandler,
    private val listCategoriesHandler: ListCategoriesHandler,
    private val getCategoryHandler: GetCategoryHandler
) {

    /**
     * List all categories for the authenticated studio.
     * Response includes `serviceIds` — the list of root service IDs assigned to each category.
     * Query param `includeInactive=true` also returns deactivated categories.
     */
    @GetMapping
    fun listCategories(
        @RequestParam(required = false, defaultValue = "false") includeInactive: Boolean
    ): ResponseEntity<CategoryListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val categories = listCategoriesHandler.handle(principal.studioId, includeInactive)
        ResponseEntity.ok(CategoryListResponse(categories = categories))
    }

    /**
     * Get a single category with its full list of assigned services.
     */
    @GetMapping("/{categoryId}")
    fun getCategory(@PathVariable categoryId: String): ResponseEntity<CategoryDetail> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val detail = getCategoryHandler.handle(
            categoryId = ServiceCategoryId.fromString(categoryId),
            studioId = principal.studioId
        )
        ResponseEntity.ok(detail)
    }

    /**
     * Create a new category. OWNER and MANAGER only.
     */
    @PostMapping
    fun createCategory(
        @RequestBody request: CreateCategoryRequest
    ): ResponseEntity<CreateCategoryResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        val command = CreateCategoryCommand(
            studioId = principal.studioId,
            createdBy = principal.userId,
            name = request.name,
            description = request.description,
            color = request.color
        )

        val categoryId = createCategoryHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            CreateCategoryResponse(
                id = categoryId.toString(),
                name = request.name,
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Update category metadata (name, description, color). OWNER and MANAGER only.
     */
    @PutMapping("/{categoryId}")
    fun updateCategory(
        @PathVariable categoryId: String,
        @RequestBody request: UpdateCategoryRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        val command = UpdateCategoryCommand(
            categoryId = ServiceCategoryId.fromString(categoryId),
            studioId = principal.studioId,
            updatedBy = principal.userId,
            name = request.name,
            description = request.description,
            color = request.color
        )

        updateCategoryHandler.handle(command)
        ResponseEntity.noContent().build()
    }

    /**
     * Soft-delete (deactivate) a category. OWNER and MANAGER only.
     * Existing stats and assignments are preserved.
     */
    @DeleteMapping("/{categoryId}")
    fun deleteCategory(@PathVariable categoryId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        deleteCategoryHandler.handle(
            categoryId = ServiceCategoryId.fromString(categoryId),
            studioId = principal.studioId
        )
        ResponseEntity.noContent().build()
    }

    /**
     * Bulk-assign services to a category (full replacement).
     * The provided list becomes the complete set of services for the category.
     * An empty list removes all assignments.
     *
     * OWNER and MANAGER only.
     */
    @PutMapping("/{categoryId}/services")
    fun assignServices(
        @PathVariable categoryId: String,
        @RequestBody request: AssignServicesRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        val command = AssignServicesCommand(
            categoryId = ServiceCategoryId.fromString(categoryId),
            studioId = principal.studioId,
            requestedBy = principal.userId,
            serviceIds = request.serviceIds.map { ServiceId.fromString(it) }
        )

        assignServicesHandler.handle(command)
        ResponseEntity.noContent().build()
    }

    /**
     * Assign a single service to a category. Idempotent — 204 if already assigned.
     * Enforces the one-category-per-service rule: if the service is in another category it is moved.
     * Returns 409 if the target category is inactive.
     *
     * OWNER and MANAGER only.
     */
    @PostMapping("/{categoryId}/services/{serviceId}")
    fun assignSingleService(
        @PathVariable categoryId: String,
        @PathVariable serviceId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        assignSingleServiceHandler.handle(
            categoryId = ServiceCategoryId.fromString(categoryId),
            serviceId = ServiceId.fromString(serviceId),
            studioId = principal.studioId
        )
        ResponseEntity.noContent().build()
    }

    /**
     * Remove a single service from a category. Idempotent — 204 even if not assigned.
     *
     * OWNER and MANAGER only.
     */
    @DeleteMapping("/{categoryId}/services/{serviceId}")
    fun unassignSingleService(
        @PathVariable categoryId: String,
        @PathVariable serviceId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        unassignSingleServiceHandler.handle(
            categoryId = ServiceCategoryId.fromString(categoryId),
            serviceId = ServiceId.fromString(serviceId),
            studioId = principal.studioId
        )
        ResponseEntity.noContent().build()
    }

    private fun requireManagerOrOwner(role: UserRole) {
        if (role != UserRole.OWNER && role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can manage service categories")
        }
    }
}
