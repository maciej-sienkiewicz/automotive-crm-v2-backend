package pl.detailing.crm.push.pwa

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.util.concurrent.TimeUnit

/** Shown when nobody is logged in — the install would carry the product's own name. */
private const val FALLBACK_NAME = "DetailBoost CRM"
private const val FALLBACK_SHORT_NAME = "DetailBoost"

/** Chrome truncates the home-screen label aggressively; longer names help nobody. */
private const val MAX_SHORT_NAME_LENGTH = 30

/**
 * Per-studio PWA manifest.
 *
 * Every notification a browser shows carries an attribution line naming its
 * source — "from DetailBoost". That name is not ours to write per notification:
 * the browser takes it from the installed app's manifest, so with one static
 * manifest every studio's staff sees the product's name instead of their own
 * company's.
 *
 * Serving the manifest from an endpoint makes that name follow the session.
 * The page links it with `crossorigin="use-credentials"`, without which the
 * browser fetches the manifest with no cookies and this controller could never
 * tell one studio from another.
 *
 * Deliberately readable without a session: an anonymous fetch (login screen,
 * a cold start before the cookie is restored) must still yield a valid,
 * installable manifest rather than a 401 that silently makes the app
 * uninstallable — and on iOS, uninstallable means no Web Push at all.
 *
 * The name is captured by the browser AT INSTALL TIME. Renaming a studio does
 * not rewrite phones that already added the app; Chrome may pick it up on a
 * later manifest refresh, iOS never will.
 */
@RestController
@RequestMapping("/api/v1/pwa")
class PwaManifestController(
    private val studioRepository: StudioRepository
) {

    @GetMapping("/manifest", produces = ["application/manifest+json"])
    fun manifest(): ResponseEntity<Map<String, Any>> {
        val studioName = currentStudioName()

        val name = studioName ?: FALLBACK_NAME
        val shortName = studioName?.take(MAX_SHORT_NAME_LENGTH) ?: FALLBACK_SHORT_NAME

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/manifest+json"))
            // Per-session content, so it must never land in a shared cache. A short
            // private cache is fine and saves a round trip on every page load.
            .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
            .body(
                mapOf(
                    "name" to name,
                    "short_name" to shortName,
                    "description" to "CRM dla studiów detailingu — wizyty, klienci, połączenia z komputera.",
                    "id" to "/",
                    // Zainstalowana aplikacja otwiera się na Tablicy. "/" trafiałoby przez
                    // HomeRedirect do Klientów (historyczny domyślny widok właściciela), a na
                    // telefonie pierwszy ekran ma być tablicą dnia. Kto nie ma do niej
                    // uprawnień, zostanie przekierowany do swojego domyślnego widoku jak zwykle.
                    "start_url" to "/dashboard",
                    "scope" to "/",
                    "display" to "standalone",
                    "orientation" to "portrait",
                    "background_color" to "#0f172a",
                    "theme_color" to "#0f172a",
                    "lang" to "pl",
                    "icons" to listOf(
                        mapOf("src" to "/icons/icon-192.png", "sizes" to "192x192", "type" to "image/png", "purpose" to "any"),
                        mapOf("src" to "/icons/icon-512.png", "sizes" to "512x512", "type" to "image/png", "purpose" to "any"),
                        mapOf("src" to "/icons/icon-maskable-512.png", "sizes" to "512x512", "type" to "image/png", "purpose" to "maskable")
                    )
                )
            )
    }

    /**
     * The endpoint is permit-all, so an anonymous request reaches it with an empty
     * SecurityContext and the helper throws. That is the ordinary case here, not an
     * error worth logging or propagating — it simply means "no studio, use the
     * product name".
     */
    private fun currentStudioName(): String? = runCatching {
        val studioId = SecurityContextHelper.getCurrentStudioId()
        studioRepository.findByStudioId(studioId.value)?.name?.trim()?.ifBlank { null }
    }.getOrNull()
}
