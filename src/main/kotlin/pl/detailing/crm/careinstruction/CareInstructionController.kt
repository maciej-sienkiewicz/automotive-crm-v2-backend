package pl.detailing.crm.careinstruction

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.util.UUID

/**
 * Słownik instrukcji pielęgnacyjnych (ustawienia) i ich przypisania do usług.
 *
 * Uprawnienia jak w cenniku usług, którego ten słownik jest przedłużeniem:
 * odczyt na VISITS_VIEW, zmiana na VISITS_CREATE.
 */
@RestController
@RequestMapping("/api/v1/care-instructions")
class CareInstructionController(
    private val service: CareInstructionService
) {
    @GetMapping
    @RequiresPermission(Permission.VISITS_VIEW)
    fun list(): ResponseEntity<List<CareInstructionDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(service.list(principal.studioId))
    }

    @PostMapping
    @RequiresPermission(Permission.VISITS_CREATE)
    fun create(@RequestBody request: SaveCareInstructionRequest): ResponseEntity<CareInstructionDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.studioId, request))
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun update(
        @PathVariable id: String,
        @RequestBody request: SaveCareInstructionRequest
    ): ResponseEntity<CareInstructionDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(service.update(principal.studioId, UUID.fromString(id), request))
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        service.delete(principal.studioId, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    /** Podmienia komplet instrukcji przypisanych do jednej usługi z cennika. */
    @PutMapping("/services/{serviceId}")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun setForService(
        @PathVariable serviceId: String,
        @RequestBody request: SetServiceCareInstructionsRequest
    ): ResponseEntity<List<CareInstructionDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        service.setForService(principal.studioId, UUID.fromString(serviceId), request.instructionIds)
        return ResponseEntity.ok(service.list(principal.studioId))
    }
}
