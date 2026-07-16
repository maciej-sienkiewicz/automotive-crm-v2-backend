package pl.detailing.crm.visitcard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public (unauthenticated) Visit Card endpoint.
 *
 * Registered as permitAll in SecurityConfig — the URL token is the credential.
 * Tokens are 256-bit random values, unique per visit, and never expire while
 * the visit exists, so the customer's link keeps working for the whole visit.
 */
@RestController
@RequestMapping("/api/public/visit-card")
class PublicVisitCardController(
    private val getVisitCardHandler: GetVisitCardHandler
) {

    @GetMapping("/{token}")
    fun getVisitCard(@PathVariable token: String): ResponseEntity<VisitCardResponse> = runBlocking {
        val card = withContext(Dispatchers.IO) { getVisitCardHandler.handle(token) }
        ResponseEntity.ok(card)
    }
}
