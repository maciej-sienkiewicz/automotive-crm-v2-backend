package pl.detailing.crm.carddav

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// RFC 6764: /.well-known/carddav entry point for CardDAV discovery.
// iOS tries this URL first when the user enters just the server hostname.
// After HTTP Basic auth, we redirect to the tenant-specific CardDAV URL.
// iOS follows the 301 and continues the standard PROPFIND discovery flow.
@RestController
class WellKnownCardDavController {

    @RequestMapping("/.well-known/carddav")
    fun wellKnown(request: HttpServletRequest, response: HttpServletResponse) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? CardDavUserDetails
            ?: run { response.sendError(HttpServletResponse.SC_UNAUTHORIZED); return }

        response.status = HttpServletResponse.SC_MOVED_PERMANENTLY
        response.setHeader("Location", "/api/v1/carddav/${principal.studioId}/")
    }
}
