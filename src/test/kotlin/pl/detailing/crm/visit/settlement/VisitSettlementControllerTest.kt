package pl.detailing.crm.visit.settlement

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.VatRate

/**
 * Warstwa HTTP poprawki rozliczenia: co przyjmuje, co odrzuca 400 i co pokazuje GET.
 * Logika planu jest w SettlementPlanningTest/SettlementExecutionTest — tu tylko granica API.
 */
class VisitSettlementControllerTest {

    private val h = SettlementHarness()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = VisitSettlementController(h.service, h.documentRepository, h.invoiceRepository, h.correctionRepository)
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler(mockk(relaxed = true)))
            .build()
        SecurityContextHolder.setContext(SecurityContextImpl(
            UserPrincipal(userId = h.userId, studioId = h.studioId, isOwner = true, email = "o@studio.pl",
                fullName = "Anna Kowalska", phoneNumber = "+48600000000")
        ))
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    private fun body(services: String = "[]", type: String = "RECEIPT", method: String = "CASH", extra: String = "") =
        """{"services":$services,"documentType":"$type","paymentMethod":"$method"$extra}"""

    private fun postJson(path: String, json: String) =
        mvc.perform(post("/api/visits/${h.visit.id.value}/settlement/$path").contentType(MediaType.APPLICATION_JSON).content(json))

    @Test
    fun `endpointy wymagaja uprawnienia do poprawiania rozliczen`() {
        val annotation = VisitSettlementController::class.java.getAnnotation(RequiresPermission::class.java)
        assertEquals(listOf(Permission.FINANCE_CORRECT_SETTLEMENT), annotation.value.toList())
    }

    @Test
    fun `GET - obecne dokumenty, faktury, pozycje ze strona wpisana i historia`() {
        h.withItems(h.serviceItem(154_472, 190_000, grossTyped = true))
        h.cashReceipt()
        h.invoice(KsefRevenueStatus.ACCEPTED, toReceipt = true)
        mvc.perform(get("/api/visits/${h.visit.id.value}/settlement"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documentType").value("RECEIPT"))
            .andExpect(jsonPath("$.paymentMethod").value("CASH"))
            .andExpect(jsonPath("$.totalGross").value(190_000))
            .andExpect(jsonPath("$.services[0].grossTyped").value(true))
            .andExpect(jsonPath("$.services[0].grossCents").value(190_000))
            .andExpect(jsonPath("$.documents[0].active").value(true))
            .andExpect(jsonPath("$.invoices[0].invoiceToReceipt").value(true))
            .andExpect(jsonPath("$.history.length()").value(0))
    }

    @Test
    fun `GET po poprawce - stary dokument zastapiony, korekta widoczna, wpis w historii`() {
        h.cashReceipt()
        h.execute(h.command(method = PaymentMethod.CARD))
        mvc.perform(get("/api/visits/${h.visit.id.value}/settlement"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paymentMethod").value("CARD"))
            .andExpect(jsonPath("$.documents[0].superseded").value(true))
            .andExpect(jsonPath("$.documents[1].type").value("CORRECTION"))
            .andExpect(jsonPath("$.documents[2].active").value(true))
            .andExpect(jsonPath("$.history[0].paymentMethodBefore").value("Gotówka"))
            .andExpect(jsonPath("$.history[0].paymentMethodAfter").value("Karta"))
    }

    @Test
    fun `preview - stawka zw jako -1, male litery w rodzaju i platnosci`() {
        h.cashReceipt()
        val item = h.item.id.value
        postJson("preview", body("""[{"serviceLineItemId":"$item","netCents":50000,"grossCents":50000,"vatRate":-1}]""", "receipt", "card"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.blockReason").doesNotExist())
            .andExpect(jsonPath("$.totalGrossAfter").value(50_000))
    }

    @Test
    fun `preview - blokada wraca jako 200 z powodem, nie jako blad`() {
        h.cashReceipt()
        postJson("preview", body())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.blockReason").value(org.hamcrest.Matchers.startsWith("Nic się nie zmienia")))
    }

    @Test
    fun `nieznana stawka VAT, rodzaj dokumentu albo forma platnosci - 400`() {
        h.cashReceipt()
        val item = h.item.id.value
        postJson("preview", body("""[{"serviceLineItemId":"$item","netCents":1000,"vatRate":7}]""")).andExpect(status().isBadRequest)
        postJson("preview", body(type = "FAKTURA_ZALICZKOWA")).andExpect(status().isBadRequest)
        postJson("preview", body(method = "BITCOIN")).andExpect(status().isBadRequest)
        postJson("preview", body(type = "CORRECTION")).andExpect(status().isOk)
            .andExpect(jsonPath("$.blockReason").value("Nieprawidłowy rodzaj dokumentu."))
    }

    @Test
    fun `corrections - zablokowana poprawka to 400 i brak zapisu`() {
        h.cashReceipt()
        postJson("corrections", body()).andExpect(status().isBadRequest)
        assertTrue(h.created.isEmpty())
    }

    @Test
    fun `corrections - zapis zwraca identyfikator poprawki`() {
        h.cashReceipt()
        val item = h.item.id.value
        postJson("corrections", body("""[{"serviceLineItemId":"$item","netCents":40650,"grossCents":50000,"vatRate":23}]""",
            extra = ""","reason":"Rabat"""" ))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.correctionId").value(h.savedCorrections.single().id.toString()))
            .andExpect(jsonPath("$.ksefError").doesNotExist())
        assertEquals(50_000, h.created.last().totalGross)
        assertEquals(VatRate.VAT_23, h.visit.serviceItems.first().vatRate)
        assertEquals(DocumentType.RECEIPT, h.created.last().documentType)
    }
}
