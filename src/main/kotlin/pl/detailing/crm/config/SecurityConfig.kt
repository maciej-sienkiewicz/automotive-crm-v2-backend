package pl.detailing.crm.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Session lifetime, in one place because two things must agree on it: the server-side
 * record in Redis and the cookie the browser holds. If the cookie outlives the session,
 * the user comes back holding a key to a room that no longer exists.
 */
private const val SESSION_TTL_SECONDS = 604800 // 7 dni

@Configuration
@EnableWebSecurity
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = SESSION_TTL_SECONDS)
class SecurityConfig(
    /** Ustawiane w docker-compose; lokalnie nieustawione, stąd domyślne „local". */
    @Value("\${APP_ENV:local}") private val appEnv: String
) {

    /**
     * Trwałe cookie sesji.
     *
     * Domyślnie Spring Session wystawia cookie BEZ „Max-Age", czyli cookie sesyjne
     * przeglądarki: znikało przy zamknięciu okna i użytkownik logował się od nowa,
     * mimo że jego sesja po stronie serwera żyła jeszcze przez tydzień. Zamknięcie
     * przeglądarki nie jest wylogowaniem — wylogowaniem jest kliknięcie „Wyloguj".
     *
     * Max-Age równy TTL sesji: cookie ginie dokładnie wtedy, gdy przestaje mieć do
     * czego prowadzić. Dłuższe zostawiałoby w przeglądarce martwy klucz, krótsze
     * wylogowywałoby mimo żywej sesji.
     *
     * Flaga Secure poza środowiskiem lokalnym — na produkcji (HTTPS) cookie nie ma
     * prawa wyjechać po HTTP, a na localhoście (HTTP) taka flaga uniemożliwiłaby
     * zalogowanie się w ogóle.
     *
     * Nazwy cookie ani sposobu kodowania NIE zmieniamy: to unieważniłoby wszystkie
     * wydane cookies, czyli wylogowało wszystkich naraz — dokładnie to, co naprawiamy.
     */
    @Bean
    fun cookieSerializer(): CookieSerializer = DefaultCookieSerializer().apply {
        setCookieName("SESSION")
        setCookieMaxAge(SESSION_TTL_SECONDS)
        setUseHttpOnlyCookie(true)
        setSameSite("Lax")
        setUseSecureCookie(!appEnv.equals("local", ignoreCase = true))
    }

    // StrictHttpFirewall only allows standard HTTP methods by default (GET, POST, PUT, DELETE,
    // PATCH, HEAD, OPTIONS, TRACE). WebDAV methods PROPFIND and REPORT must be explicitly
    // added, otherwise the firewall rejects them with HTTP 400 before any security filter runs.
    @Bean
    fun webSecurityCustomizer(): WebSecurityCustomizer {
        val firewall = StrictHttpFirewall()
        firewall.setAllowedHttpMethods(
            listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "PROPFIND", "REPORT")
        )
        return WebSecurityCustomizer { web -> web.httpFirewall(firewall) }
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean
    fun securityContextRepository(): SecurityContextRepository {
        return HttpSessionSecurityContextRepository()
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        securityContextRepository: SecurityContextRepository
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .securityContext { context ->
                context.securityContextRepository(securityContextRepository)
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                auth.requestMatchers("/api/v1/inbound/calls").permitAll()
                // SMSAPI inbound-reply webhook — called server-to-server, no session
                auth.requestMatchers("/api/sms/inbound").permitAll()
                // Przelewy24 payment status webhook — server-to-server, authenticated
                // by SHA-384 CRC signature + verify call back to the P24 API
                auth.requestMatchers("/api/v1/payments/p24/status").permitAll()

                // Manifest PWA — czytany przez przeglądarkę także na ekranie logowania
                // i przy zimnym starcie. Odpowiedź 401 zamiast manifestu czyni
                // aplikację niemożliwą do zainstalowania, a na iOS bez instalacji
                // nie ma Web Pusha w ogóle. Bez sesji zwraca nazwę produktu.
                auth.requestMatchers("/api/v1/pwa/manifest").permitAll()

                auth.requestMatchers(
                    "/api/auth/**",
                    "/api/v1/auth/**",
                    "/api/v1/demo",
                    "/api/health",
                    "/api/v1/vehicle-metadata/**",
                    "/actuator/**"
                ).permitAll()
                // Mobile QR upload endpoints — authenticated via X-Upload-Token header (Redis),
                // no JSESSIONID required (used by phone browsers with no established session)
                auth.requestMatchers("/api/mobile/**").permitAll()
                // Signing tablet endpoints — authenticated via X-Tablet-Token header (Redis);
                // pairing requires a one-time code generated by an authenticated employee
                auth.requestMatchers("/api/tablet/**").permitAll()
                // Profil .mobileconfig do konfiguracji kontaktów na iPhonie — pobierany
                // spod jednorazowego, krótkotrwałego tokenu w adresie, często inną
                // przeglądarką niż zalogowany CRM (skan QR z komputera otwiera Safari)
                auth.requestMatchers("/api/public/carddav-profile/**").permitAll()
                // Customer Visit Card — authenticated by the unguessable card token in the URL
                auth.requestMatchers("/api/public/visit-card/**").permitAll()
                // Remote document signing from the customer's phone — authenticated by
                // the unguessable, TTL-bound link token delivered by SMS
                auth.requestMatchers("/api/public/signing/**").permitAll()
                // Personal signature drawing from the user's own phone — authenticated by
                // the unguessable, TTL-bound link token sent to the user's own number
                auth.requestMatchers("/api/public/user-signature/**").permitAll()
                // Formularze leadów ze stron studiów — wywoływane przez wtyczkę formularza
                // (WordPress, Tally, Make), która nie ma i nie będzie mieć sesji.
                // Uwierzytelnia nieodgadywalny token w adresie; każde zgłoszenie trafia
                // do dziennika doręczeń razem z surowym ładunkiem.
                auth.requestMatchers("/api/public/lead-forms/**").permitAll()
                // Platform operator console (live-metrics, studio admin) — cross-tenant by
                // design, so it deliberately does NOT use the studio session identity.
                // Authenticated by the X-Platform-Key shared secret in PlatformKeyInterceptor,
                // which fails closed when no key is configured. Expected to sit behind a
                // VPN / IP allow-list as well.
                auth.requestMatchers("/api/internal/**").permitAll()
                    .anyRequest().authenticated()
            }
            .sessionManagement { session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .maximumSessions(1)
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf(
            // No trailing slash — the Origin header never carries one, so
            // "https://detailboost.pl/" would never match.
            "https://detailboost.pl",
            "https://*.detailboost.pl",
            "http://localhost:*",
            "http://192.168.*.*:*"
        )
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "PROPFIND", "REPORT")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        configuration.maxAge = 3600L
        // X-Pii-Access: frontend reads it to render blur states for masked personal data
        configuration.exposedHeaders = listOf("Set-Cookie", "X-Pii-Access")

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}