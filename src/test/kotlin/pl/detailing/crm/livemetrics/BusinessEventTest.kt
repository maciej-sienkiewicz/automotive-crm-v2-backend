package pl.detailing.crm.livemetrics

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.domain.PhotoTarget
import pl.detailing.crm.livemetrics.domain.VisitOrigin
import pl.detailing.crm.shared.StudioId

class BusinessEventTest {

    private val tenant = StudioId.random()

    @Test
    fun `event without dimension increments only the base series`() {
        val e = BusinessEvent(tenant, BusinessEventType.RESERVATION_CREATED)
        assertEquals(listOf("RESERVATION_CREATED"), e.series())
    }

    @Test
    fun `event with dimension increments base and sub series`() {
        val e = BusinessEvent(tenant, BusinessEventType.VISIT_CREATED, dimensionValue = VisitOrigin.FROM_RESERVATION.name)
        assertEquals(listOf("VISIT_CREATED", "VISIT_CREATED:FROM_RESERVATION"), e.series())
    }

    @Test
    fun `dimension value outside the closed set is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BusinessEvent(tenant, BusinessEventType.PHOTO_UPLOADED, dimensionValue = "customer-42")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BusinessEvent(tenant, BusinessEventType.PHOTO_UPLOADED) // dimension required
        }
        assertThrows(IllegalArgumentException::class.java) {
            BusinessEvent(tenant, BusinessEventType.ACTIVITY_LOGGED, dimensionValue = "x") // no dimension allowed
        }
    }

    @Test
    fun `all known series covers every type and every dimension value`() {
        val all = BusinessEventType.allKnownSeries()
        assertEquals(all.size, all.toSet().size)
        assert("PHOTO_UPLOADED:${PhotoTarget.CHECKIN}" in all)
        assert("VISIT_CREATED:${VisitOrigin.DIRECT}" in all)
        assert("ACTIVITY_LOGGED" in all)
    }

    @Test
    fun `publisher forwards a valid event and never throws`() {
        val spring = mockk<ApplicationEventPublisher>()
        val captured = slot<Any>()
        every { spring.publishEvent(capture(captured)) } answers { }
        val publisher = BusinessEventPublisher(spring)

        publisher.publish(tenant, BusinessEventType.SERVICE_CREATED, dimensionValue = "SERVICE", attributes = mapOf("name" to "Wosk"))
        val event = captured.captured as BusinessEvent
        assertEquals(BusinessEventType.SERVICE_CREATED, event.type)
        assertEquals(tenant, event.tenantId)
        assertEquals("Wosk", event.attributes["name"])
    }

    @Test
    fun `publisher swallows invalid events and downstream failures`() {
        val spring = mockk<ApplicationEventPublisher>()
        every { spring.publishEvent(any<Any>()) } throws IllegalStateException("broker down")
        val publisher = BusinessEventPublisher(spring)

        publisher.publish(tenant, BusinessEventType.VISIT_CREATED, dimensionValue = "NOPE") // invalid: not forwarded
        verify(exactly = 0) { spring.publishEvent(any<Any>()) }

        publisher.publish(tenant, BusinessEventType.RESERVATION_CREATED) // downstream throws: swallowed
        verify(exactly = 1) { spring.publishEvent(any<Any>()) }
    }
}
