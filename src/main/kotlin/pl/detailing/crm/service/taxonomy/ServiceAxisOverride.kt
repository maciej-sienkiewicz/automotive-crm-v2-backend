package pl.detailing.crm.service.taxonomy

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.ResponseEntity
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException

/**
 * Ręczna poprawka klasyfikacji nazwy usługi — furtka `source=MANUAL`.
 *
 * Mechanizm PIERWSZEŃSTWA wiersza studia nad globalnym istnieje w
 * [ServiceFamilyClassifier.classify] od V112 i jest przybity testem — ale do tej pory
 * ŻADEN kod produkcyjny nie tworzył wiersza MANUAL. Osie podnoszą stawkę błędnej
 * klasyfikacji (błędna operacja to złe kotwice cenowe na każdym leadzie tej nazwy),
 * więc ekran poprawki wchodzi w tym samym wydaniu co osie: właściciel widzi, jak
 * system rozumie jego cennik, i poprawia jednym zapisem — natychmiast, bez modelu.
 *
 * Poprawka jest DANĄ STUDIA (nadpisuje wyłącznie u niego), automat nigdy jej nie
 * reklasyfikuje ([ServiceFamilyClassifier.SOURCE_MANUAL]), a StudioDataPurger kasuje
 * ją razem z resztą danych studia — wiersz globalny zostaje, bo jest wiedzą o świecie.
 */
@Service
class ServiceAxisOverrideService(
    private val familyRepository: ServiceFamilyRepository,
    private val serviceRepository: ServiceRepository,
    private val classifier: ServiceFamilyClassifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Skuteczna klasyfikacja każdej pozycji cennika studia — to, co naprawdę czyta
     * dopasowanie (wiersz studia wygrywa z globalnym). Nazwy jeszcze niesklasyfikowane
     * przechodzą przez classify(), więc pierwsze otwarcie ekranu domyka zaległości.
     */
    @Transactional
    fun listFor(studioId: StudioId): List<ServiceAxisRow> {
        val names = serviceRepository.findByStudioId(studioId.value)
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { serviceNameKey(it) }
            .sorted()
        if (names.isEmpty()) return emptyList()

        val classified = classifier.classify(studioId.value, names)
        val overrides = familyRepository
            .findByStudioIdInAndNameKeyIn(listOf(studioId.value), names.map { serviceNameKey(it) })
            .associateBy { it.nameKey }

        return names.map { name ->
            val key = serviceNameKey(name)
            val c = classified[key]
            ServiceAxisRow(
                name = name,
                nameKey = key,
                family = (c?.family ?: ServiceFamily.UNKNOWN).name,
                scope = (c?.scope ?: ServiceScope.UNKNOWN).name,
                operation = (c?.operation ?: ServiceOperation.UNKNOWN).name,
                part = (c?.part ?: ServicePart.UNKNOWN).name,
                source = overrides[key]?.source ?: "LLM"
            )
        }
    }

    /**
     * Zapis poprawki: wiersz per studio z source=MANUAL. Nadpisuje istniejące
     * nadpisanie, nigdy wiersz globalny — cudze studia widzą dalej werdykt modelu.
     */
    @Transactional
    fun override(studioId: StudioId, request: ServiceAxisOverrideRequest): ServiceAxisRow {
        val name = request.name.trim()
        if (name.isEmpty()) throw ValidationException("Podaj nazwę usługi")
        val key = serviceNameKey(name)

        val family = ServiceFamily.from(request.family)
        val scope = ServiceScope.from(request.scope)
        val operation = ServiceOperation.from(request.operation)
        val part = ServicePart.from(request.part)

        val existing = familyRepository
            .findByStudioIdInAndNameKeyIn(listOf(studioId.value), listOf(key))
            .firstOrNull()

        val saved = if (existing != null) {
            existing.family = family.name
            existing.scope = scope.name
            existing.operation = operation.name
            existing.part = part.name
            existing.source = ServiceFamilyClassifier.SOURCE_MANUAL
            existing.axesVersion = ServiceFamilyClassifier.CURRENT_AXES_VERSION
            familyRepository.save(existing)
        } else {
            familyRepository.save(
                ServiceFamilyEntity(
                    studioId = studioId.value,
                    nameKey = key,
                    nameSample = name.take(220),
                    family = family.name,
                    scope = scope.name,
                    operation = operation.name,
                    part = part.name,
                    source = ServiceFamilyClassifier.SOURCE_MANUAL
                )
            )
        }

        log.info("[SERVICE_AXES] Studio {} poprawiło klasyfikację „{}”: {}/{}/{}/{}", studioId.value, name, family, operation, part, scope)
        return ServiceAxisRow(
            name = name, nameKey = key,
            family = saved.family, scope = saved.scope,
            operation = saved.operation, part = saved.part,
            source = saved.source
        )
    }
}

data class ServiceAxisRow(
    val name: String,
    val nameKey: String,
    val family: String,
    val scope: String,
    val operation: String,
    val part: String,
    val source: String
)

data class ServiceAxisOverrideRequest(
    val name: String,
    val family: String,
    val scope: String,
    val operation: String,
    val part: String
)

/**
 * Ekran „popraw klasyfikację usługi". Uprawnienie jak edycja cennika: poprawka osi
 * zmienia to, jakie ceny system podpowie przy każdym leadzie tej nazwy.
 */
@RestController
@RequestMapping("/api/v1/services/classification")
@RequiresPermission(Permission.VISITS_CREATE)
class ServiceAxisOverrideController(
    private val overrideService: ServiceAxisOverrideService
) {

    @GetMapping
    fun list(): ResponseEntity<List<ServiceAxisRow>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(overrideService.listFor(principal.studioId))
    }

    @PutMapping
    fun override(@RequestBody request: ServiceAxisOverrideRequest): ResponseEntity<ServiceAxisRow> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(overrideService.override(principal.studioId, request))
    }
}
