package pl.detailing.crm.costs

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.Optional
import java.util.UUID

/**
 * Cross-Tenant Data Access — koszty (KSeF).
 *
 * Luka: `POST /api/v1/cost-categories/{id}/items` przypinało pozycje faktur po samym
 * `ksef_invoice_items.id`, bez sprawdzenia, do którego studia należy faktura. Studio A
 * mogło wpiąć pozycje studia B do własnej kategorii, a `/breakdown` sumował ich kwoty.
 *
 * Testy dowodzą, że: cudza pozycja kończy się 404 (identycznie jak nieistniejąca —
 * brak wycieku informacji o istnieniu ID) i NIC nie zostaje zapisane; własna pozycja
 * przechodzi; walidacja JSR-380 odrzuca złośliwe ładunki kodem 400, nie 500.
 */
class CostCategoryCrossTenantSecurityTest {

    private val categoryRepository = mockk<CostCategoryRepository>()
    private val assignmentRepository = mockk<CostItemAssignmentRepository>(relaxed = true)
    private val autoRuleRepository = mockk<SupplierAutoRuleRepository>(relaxed = true)
    private val invoiceRepository = mockk<KsefInvoiceRepository>()
    private val invoiceItemRepository = mockk<KsefInvoiceItemRepository>()

    private val studioA = StudioId.random()
    private val studioB = StudioId.random()
    private val attacker = UserId.random()

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                CostCategoryController(
                    categoryRepository, assignmentRepository, autoRuleRepository,
                    invoiceRepository, invoiceItemRepository
                )
            )
            .setControllerAdvice(GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true)))
            .build()
        authenticateAs(studioA)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        clearAllMocks()
    }

    // ── 1. Cross-tenant ─────────────────────────────────────────────────────

    @Test
    fun `studio A cannot assign an invoice item that belongs to studio B - 404 and nothing saved`() {
        val category = ownCategory()
        val foreignInvoiceId = UUID.randomUUID()
        val foreignItem = item(invoiceId = foreignInvoiceId)

        every { invoiceItemRepository.findById(foreignItem.id) } returns Optional.of(foreignItem)
        // Studio-scoped lookup: the invoice exists, but not in studio A.
        every { invoiceRepository.findByIdAndStudioId(foreignInvoiceId, studioA.value) } returns null

        mockMvc.perform(
            post("/api/v1/cost-categories/${category.id}/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":["${foreignItem.id}"]}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("Nie znaleziono"))

        verify(exactly = 0) { assignmentRepository.save(any()) }
        verify(exactly = 0) { assignmentRepository.deleteByKsefItemIdAndStudioId(any(), any()) }
        // The invoice's tenant must have been checked with the CALLER's studio, never studio B's.
        verify(exactly = 0) { invoiceRepository.findByIdAndStudioId(any(), studioB.value) }
    }

    @Test
    fun `a nonexistent item yields the same 404 as a foreign one - no existence oracle`() {
        val category = ownCategory()
        val ghostId = UUID.randomUUID()
        every { invoiceItemRepository.findById(ghostId) } returns Optional.empty()

        mockMvc.perform(
            post("/api/v1/cost-categories/${category.id}/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":["$ghostId"]}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("Nie znaleziono"))

        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    @Test
    fun `studio A cannot target a category of studio B - 404`() {
        val foreignCategoryId = UUID.randomUUID()
        every { categoryRepository.findByIdAndStudioId(foreignCategoryId, studioA.value) } returns null

        mockMvc.perform(
            post("/api/v1/cost-categories/$foreignCategoryId/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":["${UUID.randomUUID()}"]}""")
        )
            .andExpect(status().isNotFound)

        verify(exactly = 0) { invoiceItemRepository.findById(any<UUID>()) }
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    // ── 2. Positive control ─────────────────────────────────────────────────

    @Test
    fun `own item is assigned with the caller's studioId stamped on the assignment`() {
        val category = ownCategory()
        val ownInvoiceId = UUID.randomUUID()
        val ownItem = item(invoiceId = ownInvoiceId)

        every { invoiceItemRepository.findById(ownItem.id) } returns Optional.of(ownItem)
        every { invoiceRepository.findByIdAndStudioId(ownInvoiceId, studioA.value) } returns mockk<KsefInvoiceEntity>()

        val saved = slot<CostItemAssignmentEntity>()
        every { assignmentRepository.save(capture(saved)) } answers { saved.captured }

        mockMvc.perform(
            post("/api/v1/cost-categories/${category.id}/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":["${ownItem.id}"]}""")
        )
            .andExpect(status().isNoContent)

        assert(saved.captured.studioId == studioA.value) { "assignment must carry the caller's studio" }
        assert(saved.captured.ksefItemId == ownItem.id)
    }

    // ── 3. Validation ───────────────────────────────────────────────────────

    @Test
    fun `empty itemIds is rejected with 400`() {
        val category = ownCategory()
        mockMvc.perform(
            post("/api/v1/cost-categories/${category.id}/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":[]}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("itemIds"))
    }

    @Test
    fun `malformed item id is a client error - 400, not 500`() {
        val category = ownCategory()
        mockMvc.perform(
            post("/api/v1/cost-categories/${category.id}/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":["' OR 1=1 --"]}""")
        )
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    @Test
    fun `oversized batch is rejected with 400`() {
        val category = ownCategory()
        val ids = (1..AssignCostItemsRequest.MAX_ITEMS_PER_ASSIGNMENT + 1).joinToString(",") { "\"${UUID.randomUUID()}\"" }
        mockMvc.perform(
            post("/api/v1/cost-categories/${category.id}/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":[$ids]}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `blank category name and invalid colour are rejected with 400`() {
        mockMvc.perform(
            post("/api/v1/cost-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   ","description":null,"color":"javascript:alert(1)"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.length()").value(2))

        verify(exactly = 0) { categoryRepository.save(any()) }
    }

    @Test
    fun `malformed JSON body is 400 - not a server error`() {
        val category = ownCategory()
        mockMvc.perform(
            put("/api/v1/cost-categories/${category.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "x", "color": """)
        )
            .andExpect(status().isBadRequest)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ownCategory(): CostCategoryEntity {
        val category = CostCategoryEntity(
            studioId = studioA.value, name = "Chemia", description = null, color = "#112233", createdBy = attacker.value
        )
        every { categoryRepository.findByIdAndStudioId(category.id, studioA.value) } returns category
        return category
    }

    private fun item(invoiceId: UUID) = KsefInvoiceItemEntity(
        invoiceId = invoiceId, lineNumber = 1, name = "Wosk", unit = "szt.",
        quantity = null, unitPriceNet = 10_000, netValue = 10_000, grossValue = 12_300, vatRate = "23"
    )

    private fun authenticateAs(studioId: StudioId) {
        val principal = UserPrincipal(
            userId = attacker, studioId = studioId, isOwner = false,
            email = "attacker@studio-a.pl", fullName = "Attacker", phoneNumber = "+48000000000"
        )
        SecurityContextHolder.setContext(SecurityContextImpl(principal))
    }
}
