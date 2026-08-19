package pl.detailing.crm.audit.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditLog
import pl.detailing.crm.audit.domain.AuditLogId
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditResource
import pl.detailing.crm.audit.domain.AuditSeverity
import pl.detailing.crm.audit.domain.AuditValueType
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VisitId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AuditFeedRendererTest {

    private val renderer = AuditFeedRenderer()

    /** The catalog separates thousands and the currency with a no-break space. */
    private val nbsp = "\u00A0"

    private val customerId = CustomerId(UUID.randomUUID())
    private val vehicleId = VehicleId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())

    private fun log(
        module: AuditModule = AuditModule.VISIT,
        action: AuditAction = AuditAction.VISIT_CREATED,
        actor: AuditActor = AuditActor.employee(UserId(UUID.randomUUID()), "Jan Kowalski"),
        entityId: String = visitId.value.toString(),
        entityDisplayName: String? = "Wizyta 2026/02/17",
        changes: List<FieldChange> = emptyList(),
        context: AuditContext = AuditContext.EMPTY,
        amount: AuditAmount? = null,
        metadata: Map<String, String> = emptyMap()
    ) = AuditLog(
        id = AuditLogId.random(),
        studioId = StudioId(UUID.randomUUID()),
        actor = actor,
        module = module,
        entityId = entityId,
        entityDisplayName = entityDisplayName,
        action = action,
        changes = changes,
        metadata = metadata,
        context = context,
        amount = amount,
        severity = action.severity,
        channel = AuditChannel.WEB_PANEL,
        ipAddress = null,
        correlationId = null,
        createdAt = Instant.parse("2026-02-17T09:00:00Z")
    )

    @Test
    fun `the title reads as a sentence about the subject`() {
        val item = renderer.render(log())

        assertEquals("Rozpoczęto wizytę - Wizyta 2026/02/17", item.title)
    }

    @Test
    fun `an entry with no subject name still gets a title`() {
        val item = renderer.render(log(entityDisplayName = null))

        assertEquals("Rozpoczęto wizytę", item.title)
    }

    @Test
    fun `a generic verb names the module's object`() {
        val created = renderer.render(
            log(module = AuditModule.SERVICE, action = AuditAction.CREATE, entityDisplayName = "Test")
        )
        val deleted = renderer.render(
            log(module = AuditModule.VEHICLE, action = AuditAction.DELETE, entityDisplayName = "Bmw Seria 8")
        )

        assertEquals("Utworzono usługę - Test", created.title)
        assertEquals("Usunięto pojazd - Bmw Seria 8", deleted.title)
    }

    @Test
    fun `a generic verb without a subject name still names the object`() {
        val item = renderer.render(
            log(module = AuditModule.APPOINTMENT, action = AuditAction.CREATE, entityDisplayName = null)
        )

        assertEquals("Utworzono rezerwację", item.title)
    }

    @Test
    fun `a specific action keeps its own noun untouched`() {
        val item = renderer.render(log(action = AuditAction.VISIT_CANCELLED))

        assertEquals("Anulowano wizytę - Wizyta 2026/02/17", item.title)
    }

    @Test
    fun `the summary names the actor`() {
        val item = renderer.render(log())

        assertTrue(item.summary.startsWith("Jan Kowalski"))
        assertEquals("JK", item.actor.initials)
    }

    @Test
    fun `the subject points at the module's resource`() {
        val item = renderer.render(log())

        val subject = item.subject
        assertNotNull(subject)
        assertEquals(AuditResource.VISIT.name, subject!!.resource)
        assertEquals(visitId.value.toString(), subject.id)
    }

    @Test
    fun `a module that acts on nothing addressable gets no subject`() {
        val item = renderer.render(log(module = AuditModule.APPOINTMENT_COLOR, action = AuditAction.UPDATE))

        assertNull(item.subject)
    }

    @Test
    fun `related objects become drill-down links and the subject is not repeated`() {
        val item = renderer.render(
            log(
                context = AuditContext(
                    customerId = customerId,
                    customerName = "Anna Nowak",
                    vehicleId = vehicleId,
                    vehicleName = "Audi A4 (WX 1234)",
                    visitId = visitId,
                    visitName = "Wizyta 2026/02/17"
                )
            )
        )

        val resources = item.related.map { it.resource }
        assertTrue(resources.contains(AuditResource.CUSTOMER.name))
        assertTrue(resources.contains(AuditResource.VEHICLE.name))
        // The visit is the subject of this entry — repeating it would give the reader two
        // identical chips.
        assertTrue(item.related.none { it.resource == AuditResource.VISIT.name && it.id == visitId.value.toString() })
        assertEquals("Klient: Anna Nowak · Pojazd: Audi A4 (WX 1234)", item.description)
    }

    @Test
    fun `the amount is returned both raw and formatted`() {
        val item = renderer.render(log(amount = AuditAmount(BigDecimal("1230.00"))))

        val amount = item.amount
        assertNotNull(amount)
        assertEquals("1230.00", amount!!.value)
        assertEquals("PLN", amount.currency)
        assertEquals("1${nbsp}230,00${nbsp}zł", amount.display)
        assertTrue(item.description!!.contains("1${nbsp}230,00${nbsp}zł"))
    }

    @Test
    fun `changes are labelled and formatted, and summarised for collapsed rows`() {
        val item = renderer.render(
            log(
                action = AuditAction.UPDATE,
                changes = listOf(
                    FieldChange("status", "DRAFT", "IN_PROGRESS"),
                    FieldChange("estimatedCompletionDate", null, "2026-02-18T14:00:00Z")
                )
            )
        )

        assertEquals("Status", item.changes[0].label)
        assertEquals("Szkic", item.changes[0].oldValueDisplay)
        assertEquals("W trakcie", item.changes[0].newValueDisplay)
        assertEquals("18.02.2026, 15:00", item.changes[1].newValueDisplay)
        assertEquals("Status, Planowane zakończenie", item.changeSummary)
    }

    @Test
    fun `a customer actor is rendered as a customer, not as an employee`() {
        val item = renderer.render(
            log(
                module = AuditModule.VISIT_CARD,
                action = AuditAction.VISIT_CARD_OPENED,
                actor = AuditActor.customer(customerId, "Anna Nowak")
            )
        )

        assertEquals("CUSTOMER", item.actor.type)
        assertEquals("Klient", item.actor.typeLabel)
        assertEquals(customerId.value.toString(), item.actor.id)
        assertEquals(AuditSeverity.LOW.name, item.severity.code)
    }

    @Test
    fun `an automated actor has no id and still renders`() {
        val item = renderer.render(log(actor = AuditActor.system()))

        assertEquals("SYSTEM", item.actor.type)
        assertNull(item.actor.id)
        assertEquals("S", item.actor.initials)
    }

    @Test
    fun `an actor with no captured name never renders blank`() {
        val item = renderer.render(log(actor = AuditActor.employee(UserId(UUID.randomUUID()), "  ")))

        assertEquals(AuditActor.UNKNOWN_EMPLOYEE, item.actor.displayName)
        assertTrue(item.actor.initials.isNotBlank())
    }

    @Test
    fun `no changes means no change summary`() {
        assertNull(renderer.render(log()).changeSummary)
    }

    @Test
    fun `a cash correction says which way the money went and why`() {
        val item = renderer.render(
            log(
                module = AuditModule.CASH_REGISTER,
                action = AuditAction.CASH_ADJUSTED,
                entityId = UUID.randomUUID().toString(),
                entityDisplayName = "Wypłata",
                changes = listOf(
                    FieldChange("cashAdjustmentType", null, "Wypłata z kasy"),
                    FieldChange("cashAdjustmentAmount", null, "250.00", AuditValueType.MONEY),
                    FieldChange("comment", null, "Wpłata do banku")
                ),
                amount = AuditAmount(BigDecimal("-250.00")),
                metadata = mapOf("comment" to "Wpłata do banku")
            )
        )

        assertEquals("Skorygowano stan kasy - Wypłata", item.title)
        assertTrue(item.description!!.contains("Opis: Wpłata do banku"), item.description!!)
        assertEquals("-250,00${nbsp}zł", item.amount?.display)
        assertEquals("Kwota korekty", item.changes.first { it.field == "cashAdjustmentAmount" }.label)
        assertEquals("250,00${nbsp}zł", item.changes.first { it.field == "cashAdjustmentAmount" }.newValueDisplay)
        assertEquals("Opis", item.changes.first { it.field == "comment" }.label)
    }

    @Test
    fun `a cash correction with no reason recorded still renders`() {
        val item = renderer.render(
            log(module = AuditModule.CASH_REGISTER, action = AuditAction.CASH_ADJUSTED, entityDisplayName = null)
        )

        assertEquals("Skorygowano stan kasy", item.title)
        assertNull(item.description)
    }

    @Test
    fun `a service price is stated in zloty, not in grosz`() {
        val item = renderer.render(
            log(
                module = AuditModule.SERVICE,
                action = AuditAction.UPDATE,
                entityDisplayName = "Korekta lakieru",
                changes = listOf(FieldChange("basePriceNet", "100.00", "123.45"))
            )
        )

        assertEquals("Zaktualizowano usługę - Korekta lakieru", item.title)
        assertEquals("123,45${nbsp}zł", item.changes.single().newValueDisplay)
    }
}
