package pl.detailing.crm.metrics.apiaudit

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.infrastructure.ApiEndpointEntity
import pl.detailing.crm.metrics.infrastructure.ApiEndpointRepository
import java.time.Instant
import java.util.UUID

/**
 * Seeds the endpoint catalog from Spring's own routing table at every boot.
 *
 * ## Why this class is the heart of the dead-endpoint feature
 *
 * A report built only from observed traffic cannot name an endpoint that receives none —
 * and those are exactly the endpoints worth deleting. Asking Spring what routes exist and
 * then LEFT JOINing traffic onto that list inverts the question from "what was called?"
 * to "what exists and was never called?", which is the one that answers "can we delete it".
 *
 * It also closes the loop in the other direction: an endpoint deleted from the source no
 * longer appears in the handler mapping, so its catalog row is marked
 * `is_active_in_code = false` and drops out of the report instead of haunting it forever.
 *
 * Runs on [ApplicationReadyEvent], after the mapping is fully populated.
 */
@Component
class EndpointCatalogRegistrar(
    private val handlerMapping: RequestMappingHandlerMapping,
    private val repository: ApiEndpointRepository,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun registerEndpoints() {
        if (!properties.enabled) return

        val bootTime = Instant.now()
        var discovered = 0
        var created = 0

        try {
            handlerMapping.handlerMethods.forEach { (info, method) ->
                expand(info, method).forEach { signature ->
                    discovered++
                    if (upsert(signature, bootTime)) created++
                }
            }

            // Anything not touched during this boot no longer exists in code.
            val retired = repository.markMissingFromCode(bootTime)

            log.info(
                "Katalog endpointów: {} tras w kodzie, {} nowych wpisów, {} oznaczonych jako usunięte z kodu",
                discovered, created, retired
            )
        } catch (ex: Exception) {
            // A failure here degrades the dead-endpoint report to "traffic we happened to
            // observe" — bad, but never a reason to stop the application from starting.
            log.error("Nie udało się zbudować katalogu endpointów: {}", ex.message, ex)
        }
    }

    private fun expand(info: RequestMappingInfo, method: HandlerMethod): List<EndpointSignature> {
        val patterns: Set<String> = info.pathPatternsCondition?.patternValues
            ?: info.patternsCondition?.patterns
            ?: emptySet()

        // A mapping with no declared method answers all of them; recording it once as
        // "ANY" keeps the catalog readable instead of exploding it into seven rows.
        val methods = info.methodsCondition.methods
            .map { it.name }
            .ifEmpty { listOf("ANY") }

        val controller = method.beanType.simpleName
        val module = moduleOf(method.beanType.packageName)

        return patterns.flatMap { pattern ->
            methods.map { httpMethod ->
                EndpointSignature(
                    httpMethod = httpMethod,
                    pathTemplate = pattern,
                    controller = controller,
                    handler = method.method.name,
                    module = module,
                    requiresAuth = requiresAuth(pattern)
                )
            }
        }
    }

    /** Vertical slice name from the package, e.g. `...crm.visit.create` → `visit`. */
    private fun moduleOf(packageName: String): String =
        packageName.removePrefix("${properties.errors.applicationPackage}.")
            .substringBefore('.')
            .ifBlank { "unknown" }

    /**
     * Mirrors the permit-all list in `SecurityConfig`. Kept as a prefix list rather than
     * introspecting the security chain: the value is a report column ("this one is public,
     * of course it has no tenant attribution"), and a wrong entry costs a label, not access.
     */
    private fun requiresAuth(pattern: String): Boolean = PUBLIC_PREFIXES.none { pattern.startsWith(it) }

    /** @return true when a new catalog row was created. */
    private fun upsert(signature: EndpointSignature, bootTime: Instant): Boolean {
        val existing = repository.findBySignature(signature.httpMethod, signature.pathTemplate)

        if (existing != null) {
            existing.controller = signature.controller
            existing.handler = signature.handler
            existing.module = signature.module
            existing.requiresAuth = signature.requiresAuth
            existing.isActiveInCode = true
            existing.lastSeenInCodeAt = bootTime
            repository.save(existing)
            return false
        }

        repository.save(
            ApiEndpointEntity(
                id = UUID.randomUUID(),
                httpMethod = signature.httpMethod,
                pathTemplate = signature.pathTemplate.take(300),
                controller = signature.controller.take(150),
                handler = signature.handler.take(150),
                module = signature.module.take(60),
                requiresAuth = signature.requiresAuth,
                firstSeenAt = bootTime,
                lastSeenInCodeAt = bootTime
            )
        )
        return true
    }

    data class EndpointSignature(
        val httpMethod: String,
        val pathTemplate: String,
        val controller: String,
        val handler: String,
        val module: String,
        val requiresAuth: Boolean
    )

    companion object {
        private val PUBLIC_PREFIXES = listOf(
            "/api/auth", "/api/v1/auth", "/api/health", "/api/v1/demo",
            "/api/v1/vehicle-metadata", "/api/mobile", "/api/tablet",
            "/api/public", "/api/sms/inbound", "/api/v1/inbound",
            "/api/v1/payments/p24/status", "/actuator"
        )
    }
}
