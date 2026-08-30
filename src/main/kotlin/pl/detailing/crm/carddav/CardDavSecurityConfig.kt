package pl.detailing.crm.carddav

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.stereotype.Component
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.UUID

@Configuration
class CardDavSecurityConfig {

    @Bean
    @Order(1)
    fun cardDavFilterChain(
        http: HttpSecurity,
        cardDavAuthenticationProvider: CardDavAuthenticationProvider
    ): SecurityFilterChain {
        http
            // AntPathRequestMatcher used instead of default MvcRequestMatcher: MvcRequestMatcher
            // relies on HandlerMappingIntrospector which fails for non-standard HTTP methods
            // (PROPFIND, REPORT) and for UUID path variables, causing the CardDAV filter chain
            // to silently fall through to the main SecurityConfig.
            // /.well-known/carddav is included so iOS can discover the tenant URL via RFC 6764.
            .securityMatcher(
                OrRequestMatcher(
                    AntPathRequestMatcher("/api/v1/carddav/**"),
                    AntPathRequestMatcher("/.well-known/carddav")
                )
            )
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/v1/carddav/**", "/.well-known/carddav").authenticated()
            }
            .httpBasic { basic ->
                basic.realmName("CRM CardDAV")
            }
            .authenticationProvider(cardDavAuthenticationProvider)

        return http.build()
    }
}

/**
 * Basic auth serwera CardDAV: e-mail + hasło. Hasłem może być:
 *  - hasło aplikacyjne wygenerowane przy automatycznej konfiguracji telefonu
 *    (profil .mobileconfig) — sprawdzane najpierw, bo to jedyna droga, którą
 *    loguje się systemowa synchronizacja kontaktów,
 *  - hasło konta użytkownika — zostaje dla ręcznej konfiguracji.
 *
 * UserDetailsService nie udźwignie „wielu haseł na jednego użytkownika", stąd
 * własny AuthenticationProvider.
 */
@Component
class CardDavAuthenticationProvider(
    private val userRepository: UserRepository,
    private val appPasswordRepository: CardDavAppPasswordRepository,
    private val passwordEncoder: PasswordEncoder,
) : AuthenticationProvider {

    override fun authenticate(authentication: Authentication): Authentication {
        val email = (authentication.name ?: "").lowercase().trim()
        val rawPassword = authentication.credentials?.toString() ?: ""

        val entity = userRepository.findByEmail(email)
            ?: throw UsernameNotFoundException("User not found: $email")
        if (!entity.isActive) {
            throw UsernameNotFoundException("User account is disabled: $email")
        }

        val appPassword = appPasswordRepository.findActiveByUserId(entity.id)
            .firstOrNull { passwordEncoder.matches(rawPassword, it.secretHash) }

        if (appPassword != null) {
            touchLastUsed(appPassword)
        } else if (!passwordEncoder.matches(rawPassword, entity.passwordHash)) {
            throw BadCredentialsException("Invalid CardDAV credentials")
        }

        val details = CardDavUserDetails(
            studioId = entity.studioId,
            username = entity.email,
            passwordHash = entity.passwordHash,
            role = if (entity.isOwner) "OWNER" else "USER"
        )
        return UsernamePasswordAuthenticationToken(details, null, details.authorities)
    }

    override fun supports(authentication: Class<*>): Boolean =
        UsernamePasswordAuthenticationToken::class.java.isAssignableFrom(authentication)

    /**
     * last_used_at zasila kolumnę „Stan" w Ustawieniach. iOS potrafi odpytać
     * serwer kilka razy na jedną synchronizację, więc dławimy zapis do jednego
     * na kilka minut; best-effort — nieudany zapis nie może ubić synchronizacji.
     */
    private fun touchLastUsed(appPassword: CardDavAppPasswordEntity) {
        val now = Instant.now()
        val last = appPassword.lastUsedAt
        if (last != null && last.isAfter(now.minusSeconds(LAST_USED_THROTTLE_SECONDS))) return
        runCatching {
            appPassword.lastUsedAt = now
            appPasswordRepository.save(appPassword)
        }
    }

    companion object {
        private const val LAST_USED_THROTTLE_SECONDS = 300L
    }
}

data class CardDavUserDetails(
    val studioId: UUID,
    private val username: String,
    private val passwordHash: String,
    private val role: String
) : UserDetails {

    override fun getAuthorities() = listOf(SimpleGrantedAuthority("ROLE_$role"))
    override fun getPassword(): String = passwordHash
    override fun getUsername(): String = username
    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = true
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = true
}
