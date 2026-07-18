package pl.detailing.crm.costs

import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import java.time.Instant
import java.util.UUID

// ─── Request / Response DTOs ──────────────────────────────────────────────────

data class CreateCostCategoryRequest(
    val name: String,
    val description: String?,
    val color: String?
)

data class UpdateCostCategoryRequest(
    val name: String,
    val description: String?,
    val color: String?
)

data class AssignCostItemsRequest(
    /** Item IDs (ksef_invoice_items.id) to assign to this category. */
    val itemIds: List<String>
)

data class CostCategoryDto(
    val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class CostCategoryListResponse(val categories: List<CostCategoryDto>)

data class CreateCostCategoryResponse(val id: String, val name: String, val createdAt: String)

data class CostExpenseItemDto(
    val id: String,
    val invoiceId: String,
    val invoiceNumber: String?,
    val sellerNip: String?,
    val sellerName: String?,
    val saleDate: String?,
    val lineNumber: Int,
    val name: String?,
    val unit: String?,
    val quantity: Double?,
    val unitPriceNet: Double?,
    val netValue: Double?,
    val grossValue: Double?,
    val vatRate: String?,
    val costCategoryId: String?,
    val costCategoryName: String?
)

data class CostExpenseItemsResponse(val items: List<CostExpenseItemDto>)

data class CostDataPoint(
    /** Period label e.g. "2024-01" */
    val period: String,
    val itemCount: Long,
    val totalCostGross: Double
)

data class CostCategoryBreakdownItem(
    val categoryId: String,
    val categoryName: String,
    val color: String?,
    val itemCount: Long,
    val totalCostGross: Double
)

data class CostBreakdownResponse(
    val period: PeriodInfo,
    val overview: CostOverview,
    val categories: List<CostCategoryBreakdownItem>,
    val unassignedItemCount: Long,
    val unassignedCostGross: Double
)

data class PeriodInfo(val granularity: String, val startDate: String, val endDate: String)

data class CostOverview(
    val data: List<CostDataPoint>,
    val totals: CostTotals
)

data class CostTotals(val itemCount: Long, val totalCostGross: Double)

// ─── Auto-rule DTOs ───────────────────────────────────────────────────────────

data class CreateAutoRuleRequest(
    val sellerNip: String,
    val sellerName: String,
    val categoryId: String,
    val applyNow: Boolean = true
)

data class UpdateAutoRuleRequest(
    val sellerName: String,
    val categoryId: String
)

data class SupplierAutoRuleDto(
    val id: String,
    val sellerNip: String,
    val sellerName: String,
    val categoryId: String,
    val categoryName: String?,
    val categoryColor: String?,
    val createdAt: String
)

data class AutoRuleListResponse(val rules: List<SupplierAutoRuleDto>)
data class CreateAutoRuleResponse(val id: String, val assignedItemCount: Int)
data class ApplyAutoRulesResponse(val assignedItemCount: Int)

// ─── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/cost-categories")
class CostCategoryController(
    private val categoryRepository: CostCategoryRepository,
    private val assignmentRepository: CostItemAssignmentRepository,
    private val autoRuleRepository: SupplierAutoRuleRepository,
    private val invoiceRepository: KsefInvoiceRepository,
    private val invoiceItemRepository: KsefInvoiceItemRepository
) {

    // ── Categories CRUD ───────────────────────────────────────────────────────

    @GetMapping
    fun listCategories(): ResponseEntity<CostCategoryListResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val categories = categoryRepository.findActiveByStudioId(studioId).map { it.toDto() }
        return ResponseEntity.ok(CostCategoryListResponse(categories))
    }

    @PostMapping
    @Transactional
    fun createCategory(@RequestBody req: CreateCostCategoryRequest): ResponseEntity<CreateCostCategoryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = CostCategoryEntity(
            studioId    = principal.studioId.value,
            name        = req.name.trim(),
            description = req.description?.trim(),
            color       = req.color,
            createdBy   = principal.userId.value
        )
        val saved = categoryRepository.save(entity)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            CreateCostCategoryResponse(
                id        = saved.id.toString(),
                name      = saved.name,
                createdAt = saved.createdAt.toString()
            )
        )
    }

    @PutMapping("/{categoryId}")
    @Transactional
    fun updateCategory(
        @PathVariable categoryId: String,
        @RequestBody req: UpdateCostCategoryRequest
    ): ResponseEntity<Void> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val entity = categoryRepository.findByIdAndStudioId(UUID.fromString(categoryId), studioId)
            ?: return ResponseEntity.notFound().build()
        entity.name        = req.name.trim()
        entity.description = req.description?.trim()
        entity.color       = req.color
        entity.updatedAt   = Instant.now()
        categoryRepository.save(entity)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{categoryId}")
    @Transactional
    fun deleteCategory(@PathVariable categoryId: String): ResponseEntity<Void> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val entity = categoryRepository.findByIdAndStudioId(UUID.fromString(categoryId), studioId)
            ?: return ResponseEntity.notFound().build()
        entity.isActive  = false
        entity.updatedAt = Instant.now()
        categoryRepository.save(entity)
        return ResponseEntity.noContent().build()
    }

    // ── Item assignments ───────────────────────────────────────────────────────

    /**
     * Assign a list of KSeF item IDs to a category.
     * If an item is already in another category it is moved.
     */
    @PostMapping("/{categoryId}/items")
    @Transactional
    fun assignItems(
        @PathVariable categoryId: String,
        @RequestBody req: AssignCostItemsRequest
    ): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId  = principal.studioId.value
        val catId     = UUID.fromString(categoryId)

        categoryRepository.findByIdAndStudioId(catId, studioId)
            ?: return ResponseEntity.notFound().build()

        req.itemIds.forEach { rawItemId ->
            val itemId = UUID.fromString(rawItemId)
            val item   = invoiceItemRepository.findById(itemId).orElse(null) ?: return@forEach

            // Remove existing assignment if present (move semantics)
            assignmentRepository.deleteByKsefItemIdAndStudioId(itemId, studioId)

            assignmentRepository.save(
                CostItemAssignmentEntity(
                    categoryId  = catId,
                    ksefItemId  = itemId,
                    invoiceId   = item.invoiceId,
                    studioId    = studioId
                )
            )
        }
        return ResponseEntity.noContent().build()
    }

    /** Remove a single item from whichever category it belongs to. */
    @DeleteMapping("/{categoryId}/items/{itemId}")
    @Transactional
    fun unassignItem(
        @PathVariable categoryId: String,
        @PathVariable itemId: String
    ): ResponseEntity<Void> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        assignmentRepository.deleteByKsefItemIdAndStudioId(UUID.fromString(itemId), studioId)
        return ResponseEntity.noContent().build()
    }

    // ── Expense items list ─────────────────────────────────────────────────────

    /**
     * All KSeF expense items for this studio in the requested date window,
     * enriched with invoice metadata and current cost-category assignment.
     *
     * Params: dateFrom (YYYY-MM-DD), dateTo (YYYY-MM-DD), pageSize (default 2000)
     */
    @GetMapping("/expense-items")
    fun listExpenseItems(
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo:   String?,
        @RequestParam(defaultValue = "2000") pageSize: Int
    ): ResponseEntity<CostExpenseItemsResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value

        // Load invoices in range (paginated by pageSize to avoid memory blowouts)
        val dateFromOdt = dateFrom?.let { java.time.OffsetDateTime.parse("${it}T00:00:00Z") }
        val dateToOdt   = dateTo?.let   { java.time.OffsetDateTime.parse("${it}T23:59:59Z") }

        val invoicesPage = invoiceRepository.findWithFilters(
            studioId        = studioId,
            source          = null,
            paymentStatus   = null,
            includeExcluded = false,
            dateFrom        = dateFromOdt,
            dateTo          = dateToOdt,
            pageable        = PageRequest.of(0, pageSize)
        )

        val invoiceMap = invoicesPage.content.associateBy { it.id }
        val invoiceIds = invoiceMap.keys.toList()
        if (invoiceIds.isEmpty()) return ResponseEntity.ok(CostExpenseItemsResponse(emptyList()))

        // Load all items for those invoices
        val allItems = invoiceIds.flatMap { invoiceItemRepository.findByInvoiceIdOrderByLineNumberAsc(it) }

        // Build assignment lookup: itemId → (categoryId, categoryName)
        val assignments = assignmentRepository.findByStudioId(studioId).associateBy { it.ksefItemId }
        val categoryMap = categoryRepository.findActiveByStudioId(studioId).associateBy { it.id }

        val dtos = allItems.map { item ->
            val invoice    = invoiceMap[item.invoiceId]
            val assignment = assignments[item.id]
            val category   = assignment?.let { categoryMap[it.categoryId] }
            CostExpenseItemDto(
                id                = item.id.toString(),
                invoiceId         = item.invoiceId.toString(),
                invoiceNumber     = invoice?.invoiceNumber,
                sellerNip         = invoice?.sellerNip,
                sellerName        = invoice?.sellerName,
                saleDate          = invoice?.issueDate?.toString() ?: invoice?.invoicingDate?.toLocalDate()?.toString(),
                lineNumber        = item.lineNumber,
                name              = item.name,
                unit              = item.unit,
                quantity          = item.quantity,
                unitPriceNet      = item.unitPriceNet,
                netValue          = item.netValue,
                grossValue        = item.grossValue,
                vatRate           = item.vatRate,
                costCategoryId    = assignment?.categoryId?.toString(),
                costCategoryName  = category?.name
            )
        }

        return ResponseEntity.ok(CostExpenseItemsResponse(dtos))
    }

    // ── Breakdown (chart + category totals) ───────────────────────────────────

    @GetMapping("/breakdown")
    fun getBreakdown(
        @RequestParam(defaultValue = "MONTHLY") granularity: String,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate:   String?
    ): ResponseEntity<CostBreakdownResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value

        val periodFormat = periodFormatFor(granularity)

        val timeSeries = assignmentRepository.findTimeSeriesAllItems(
            studioId     = studioId,
            dateFrom     = startDate,
            dateTo       = endDate,
            periodFormat = periodFormat
        ).map { row ->
            CostDataPoint(
                period        = row[0]?.toString() ?: "",
                totalCostGross = toDouble(row[1]),
                itemCount     = toLong(row[2])
            )
        }

        val totalCost  = timeSeries.sumOf { it.totalCostGross }
        val totalItems = timeSeries.sumOf { it.itemCount }

        val categoryTotals = assignmentRepository.sumByCategory(
            studioId  = studioId,
            dateFrom  = startDate,
            dateTo    = endDate
        )

        val categoryMap = categoryRepository.findActiveByStudioId(studioId).associateBy { it.id }

        val categoriesBreakdown = categoryTotals.mapNotNull { row ->
            val catId  = UUID.fromString(row[0]?.toString() ?: return@mapNotNull null)
            val cat    = categoryMap[catId] ?: return@mapNotNull null
            CostCategoryBreakdownItem(
                categoryId     = catId.toString(),
                categoryName   = cat.name,
                color          = cat.color,
                totalCostGross = toDouble(row[1]),
                itemCount      = toLong(row[3])
            )
        }.sortedByDescending { it.totalCostGross }

        val assignedCost  = categoriesBreakdown.sumOf { it.totalCostGross }
        val assignedItems = categoriesBreakdown.sumOf { it.itemCount }
        val unassignedCost  = (totalCost  - assignedCost).coerceAtLeast(0.0)
        val unassignedItems = (totalItems - assignedItems).coerceAtLeast(0L)

        return ResponseEntity.ok(
            CostBreakdownResponse(
                period = PeriodInfo(
                    granularity = granularity,
                    startDate   = startDate ?: "",
                    endDate     = endDate   ?: ""
                ),
                overview = CostOverview(
                    data   = timeSeries,
                    totals = CostTotals(totalItems, totalCost)
                ),
                categories         = categoriesBreakdown,
                unassignedItemCount = unassignedItems,
                unassignedCostGross = unassignedCost
            )
        )
    }

    // ── Auto-rules ────────────────────────────────────────────────────────

    @GetMapping("/auto-rules")
    fun listAutoRules(): ResponseEntity<AutoRuleListResponse> {
        val studioId    = SecurityContextHelper.getCurrentUser().studioId.value
        val categoryMap = categoryRepository.findActiveByStudioId(studioId).associateBy { it.id }
        val rules = autoRuleRepository.findByStudioId(studioId).map { it.toDto(categoryMap) }
        return ResponseEntity.ok(AutoRuleListResponse(rules))
    }

    @PostMapping("/auto-rules")
    @Transactional
    fun createAutoRule(@RequestBody req: CreateAutoRuleRequest): ResponseEntity<CreateAutoRuleResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId  = principal.studioId.value
        val nip       = req.sellerNip.replace(Regex("[^0-9]"), "")

        categoryRepository.findByIdAndStudioId(UUID.fromString(req.categoryId), studioId)
            ?: return ResponseEntity.notFound().build()

        val existing = autoRuleRepository.findByStudioIdAndSellerNip(studioId, nip)
        val rule = if (existing != null) {
            existing.sellerName  = req.sellerName.trim()
            existing.categoryId  = UUID.fromString(req.categoryId)
            existing.updatedAt   = Instant.now()
            autoRuleRepository.save(existing)
        } else {
            autoRuleRepository.save(
                SupplierAutoRuleEntity(
                    studioId   = studioId,
                    sellerNip  = nip,
                    sellerName = req.sellerName.trim(),
                    categoryId = UUID.fromString(req.categoryId)
                )
            )
        }

        val assigned = if (req.applyNow) applyRule(rule, studioId) else 0
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CreateAutoRuleResponse(id = rule.id.toString(), assignedItemCount = assigned))
    }

    @PutMapping("/auto-rules/{ruleId}")
    @Transactional
    fun updateAutoRule(
        @PathVariable ruleId: String,
        @RequestBody req: UpdateAutoRuleRequest
    ): ResponseEntity<Void> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val rule = autoRuleRepository.findByIdAndStudioId(UUID.fromString(ruleId), studioId)
            ?: return ResponseEntity.notFound().build()
        categoryRepository.findByIdAndStudioId(UUID.fromString(req.categoryId), studioId)
            ?: return ResponseEntity.notFound().build()
        rule.sellerName = req.sellerName.trim()
        rule.categoryId = UUID.fromString(req.categoryId)
        rule.updatedAt  = Instant.now()
        autoRuleRepository.save(rule)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/auto-rules/{ruleId}")
    @Transactional
    fun deleteAutoRule(@PathVariable ruleId: String): ResponseEntity<Void> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        autoRuleRepository.deleteByIdAndStudioId(UUID.fromString(ruleId), studioId)
        return ResponseEntity.noContent().build()
    }

    /** Apply ALL active rules to currently unassigned items. */
    @PostMapping("/auto-rules/apply")
    @Transactional
    fun applyAllRules(): ResponseEntity<ApplyAutoRulesResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val rules    = autoRuleRepository.findByStudioId(studioId)
        val total    = rules.sumOf { applyRule(it, studioId) }
        return ResponseEntity.ok(ApplyAutoRulesResponse(total))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun CostCategoryEntity.toDto() = CostCategoryDto(
        id          = id.toString(),
        name        = name,
        description = description,
        color       = color,
        isActive    = isActive,
        createdAt   = createdAt.toString(),
        updatedAt   = updatedAt.toString()
    )

    /**
     * Assigns all unassigned items from invoices matching [rule.sellerNip] to [rule.categoryId].
     * Already-assigned items are skipped — manual overrides are preserved.
     */
    private fun applyRule(rule: SupplierAutoRuleEntity, studioId: UUID): Int {
        val invoices = invoiceRepository.findByStudioIdAndSellerNipAndStatusNotIn(
            studioId, rule.sellerNip, listOf("CANCELLED", "EXCLUDED")
        )
        if (invoices.isEmpty()) return 0

        val items    = invoiceItemRepository.findByInvoiceIdIn(invoices.map { it.id })
        if (items.isEmpty()) return 0

        val assigned = assignmentRepository.findByStudioId(studioId).map { it.ksefItemId }.toSet()
        var count    = 0
        items.forEach { item ->
            if (item.id !in assigned) {
                assignmentRepository.save(
                    CostItemAssignmentEntity(
                        categoryId = rule.categoryId,
                        ksefItemId = item.id,
                        invoiceId  = item.invoiceId,
                        studioId   = studioId
                    )
                )
                count++
            }
        }
        return count
    }

    private fun SupplierAutoRuleEntity.toDto(categoryMap: Map<UUID, CostCategoryEntity>) = SupplierAutoRuleDto(
        id            = id.toString(),
        sellerNip     = sellerNip,
        sellerName    = sellerName,
        categoryId    = categoryId.toString(),
        categoryName  = categoryMap[categoryId]?.name,
        categoryColor = categoryMap[categoryId]?.color,
        createdAt     = createdAt.toString()
    )

    private fun periodFormatFor(granularity: String): String = when (granularity.uppercase()) {
        "DAILY"     -> "YYYY-MM-DD"
        "WEEKLY"    -> "IYYY-IW"
        "QUARTERLY" -> "YYYY-Q\"Q\""
        "YEARLY"    -> "YYYY"
        else        -> "YYYY-MM" // MONTHLY (default)
    }

    private fun toDouble(v: Any?): Double = when (v) {
        null      -> 0.0
        is Double -> v
        is Number -> v.toDouble()
        else      -> v.toString().toDoubleOrNull() ?: 0.0
    }

    private fun toLong(v: Any?): Long = when (v) {
        null     -> 0L
        is Long  -> v
        is Number -> v.toLong()
        else     -> v.toString().toLongOrNull() ?: 0L
    }
}
