package pl.detailing.crm.livemetrics

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * Jedyne wejście do systemu metryk dla kodu biznesowego.
 *
 * Handler woła [publish] zaraz po utrwaleniu encji. Zdarzenie idzie przez Spring
 * `ApplicationEventPublisher`, więc nasłuchujący je `@TransactionalEventListener`
 * (patrz `ingest/BusinessEventIngestListener`) odbiera je dopiero po commicie —
 * a gdy wywołanie nie ma związanej transakcji (korutyny na `Dispatchers.IO`),
 * natychmiast, co po `repository.save()` w trybie autocommit jest równoważne.
 *
 * Kontrakt, którego kod biznesowy może być pewien: **ta metoda nigdy nie rzuca**.
 * Awaria metryk nie ma prawa cofnąć rezerwacji klienta; najgorszy skutek to
 * zgubione zdarzenie i wpis w logu.
 */
@Component
class BusinessEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(BusinessEventPublisher::class.java)

    fun publish(event: BusinessEvent) {
        try {
            applicationEventPublisher.publishEvent(event)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] Dropped {} for tenant {}: {}", event.type, event.tenantId.value, e.toString())
        }
    }

    fun publish(
        tenantId: StudioId,
        type: BusinessEventType,
        dimensionValue: String? = null,
        attributes: Map<String, String> = emptyMap(),
        occurredAt: Instant = Instant.now()
    ) {
        val event = try {
            BusinessEvent(
                tenantId = tenantId,
                type = type,
                occurredAt = occurredAt,
                dimensionValue = dimensionValue,
                attributes = attributes
            )
        } catch (e: IllegalArgumentException) {
            // Błąd programisty (zła wartość wymiaru) — logujemy głośno, ale nie psujemy żądania.
            log.error("[LIVE-METRICS] Invalid event {} for tenant {}: {}", type, tenantId.value, e.message)
            return
        }
        publish(event)
    }
}
