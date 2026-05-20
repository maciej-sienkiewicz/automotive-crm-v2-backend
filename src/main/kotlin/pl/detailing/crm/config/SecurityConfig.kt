package pl.detailing.crm.config

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
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 604800)
class SecurityConfig {

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
                // CloudFlare email webhook — public, token-validated by CloudflareWebhookTokenFilter
                auth.requestMatchers("/api/v1/inbound/email").permitAll()

                auth.requestMatchers(
                    "/api/auth/**",
                    "/api/v1/auth/**",
                    "/api/health",
                    "/api/v1/vehicle-metadata/**",
                    "/actuator/**"
                ).permitAll()
                // Mobile QR upload endpoints — authenticated via X-Upload-Token header (Redis),
                // no JSESSIONID required (used by phone browsers with no established session)
                auth.requestMatchers("/api/mobile/**").permitAll()
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
            "https://detailboost.pl/",
            "http://localhost:*",
            "http://192.168.*.*:*"
        )
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "PROPFIND", "REPORT")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        configuration.maxAge = 3600L
        configuration.exposedHeaders = listOf("Set-Cookie")

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}