package pl.detailing.crm.shared.pii

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.core.type.filter.TypeFilter
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Build-breaking guard: **no personal-data field may leave a controller without `@Pii`.**
 *
 * Two sweeps produce the set of serialized classes:
 * 1. the transitive return-type graph of every `@RestController` handler method,
 * 2. every class in the codebase named `*Response` / `*Dto` / `*Payload` (catches DTOs
 *    returned as `Any` or published over STOMP, which the return graph cannot see).
 *
 * Inside that set, every `String` field whose name matches [PII_FIELD_NAMES] must either
 * carry [Pii] or appear in [WHITELIST] with a justification. Adding a new endpoint or DTO
 * requires no test change — an unannotated personal-data field fails this test by default.
 */
class PiiResponseSurfaceScanTest {

    companion object {
        private const val BASE_PACKAGE = "pl.detailing.crm"

        /** Lower-cased field names that denote customer personal data. */
        private val PII_FIELD_NAMES = setOf(
            "firstname", "lastname", "phone", "phonenumber", "email",
            "customername", "customerfirstname", "customerlastname",
            "contactname", "callername", "contactidentifier", "signername",
            "nip", "recipientaddress"
        )

        /**
         * Fields that match the name heuristic but are NOT customer personal data.
         * Entries are `package-or-class prefix` — every whitelisted prefix must state why.
         */
        private val WHITELIST = listOf(
            // The authenticated user's/staff member's own account data:
            "pl.detailing.crm.auth.",
            "pl.detailing.crm.user.",
            "pl.detailing.crm.voice.VoiceContextResponse",
            // Employee data — governed by the EMPLOYEES permission family, not customer PII:
            "pl.detailing.crm.employee.",
            // The studio's own company details (its NIP/e-mail are the tenant's, not a customer's):
            "pl.detailing.crm.studio.",
            "pl.detailing.crm.ksef.",
            // Public business-registry lookups used to prefill company forms:
            "pl.detailing.crm.gus.",
            // Demo-account bootstrap (returns generated demo credentials):
            "pl.detailing.crm.demo."
        )

        private val MAPPING_ANNOTATIONS = listOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping"
        )
    }

    @Test
    fun `every personal-data field on the response surface is annotated with @Pii`() {
        val serialized = mutableSetOf<Class<*>>()

        controllerClasses().forEach { controller ->
            controller.declaredMethods
                .filter { it.isMappingMethod() }
                .forEach { collectTypes(it.genericReturnType, serialized) }
        }
        dtoNamedClasses().forEach { collectTypes(it, serialized) }

        assertTrue(serialized.size > 30) {
            "Response-surface scan found suspiciously few classes (${serialized.size}) — scan misconfigured?"
        }

        val violations = serialized
            .flatMap { cls -> cls.declaredFields.map { cls to it } }
            .filter { (_, field) -> field.isPiiCandidate() }
            .filterNot { (cls, field) -> field.isAnnotationPresent(Pii::class.java) || isWhitelisted(cls, field) }
            .map { (cls, field) -> "${cls.name}#${field.name}" }
            .sorted()

        assertTrue(violations.isEmpty()) {
            "Personal-data fields without @Pii on the response surface (annotate or whitelist with a reason):\n" +
                violations.joinToString("\n")
        }
    }

    // ── Class discovery ─────────────────────────────────────────────────────────

    private fun controllerClasses(): List<Class<*>> {
        val provider = ClassPathScanningCandidateComponentProvider(false)
        provider.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        return provider.findCandidateComponents(BASE_PACKAGE).mapNotNull { bd ->
            runCatching { Class.forName(bd.beanClassName) }.getOrNull()
        }
    }

    private fun dtoNamedClasses(): List<Class<*>> {
        val provider = ClassPathScanningCandidateComponentProvider(false)
        provider.addIncludeFilter(TypeFilter { reader, _ ->
            val name = reader.classMetadata.className.substringAfterLast('.')
            name.endsWith("Response") || name.endsWith("Dto") || name.endsWith("Payload")
        })
        return provider.findCandidateComponents(BASE_PACKAGE).mapNotNull { bd ->
            runCatching { Class.forName(bd.beanClassName) }.getOrNull()
        }
    }

    // ── Type graph traversal ────────────────────────────────────────────────────

    private fun collectTypes(type: Type, into: MutableSet<Class<*>>) {
        when (type) {
            is ParameterizedType -> {
                collectTypes(type.rawType, into)
                type.actualTypeArguments.forEach { collectTypes(it, into) }
            }
            is WildcardType -> (type.upperBounds + type.lowerBounds).forEach { collectTypes(it, into) }
            is GenericArrayType -> collectTypes(type.genericComponentType, into)
            is Class<*> -> {
                when {
                    type.isArray -> collectTypes(type.componentType, into)
                    type.name.startsWith(BASE_PACKAGE) && !type.isEnum && !type.isAnnotation -> {
                        if (into.add(type)) {
                            type.declaredFields
                                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                                .forEach { collectTypes(it.genericType, into) }
                        }
                    }
                }
            }
        }
    }

    // ── Predicates ──────────────────────────────────────────────────────────────

    private fun Method.isMappingMethod(): Boolean =
        annotations.any { it.annotationClass.java.name in MAPPING_ANNOTATIONS }

    private fun Field.isPiiCandidate(): Boolean =
        type == String::class.java &&
            !Modifier.isStatic(modifiers) &&
            !isSynthetic &&
            name.lowercase() in PII_FIELD_NAMES

    private fun isWhitelisted(cls: Class<*>, field: Field): Boolean =
        WHITELIST.any { prefix -> cls.name.startsWith(prefix) } ||
            WHITELIST.any { entry -> "${cls.name}#${field.name}" == entry }
}
