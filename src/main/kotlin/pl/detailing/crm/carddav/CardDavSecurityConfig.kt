package pl.detailing.crm.carddav

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.stereotype.Component
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

@Configuration
class CardDavSecurityConfig {

    @Bean
    @Order(1)
    fun cardDavFilterChain(
        http: HttpSecurity,
        cardDavUserDetailsService: CardDavUserDetailsService
    ): SecurityFilterChain {
        http
            // AntPathRequestMatcher used instead of default MvcRequestMatcher: MvcRequestMatcher
            // relies on HandlerMappingIntrospector which fails for non-standard HTTP methods
            // (PROPFIND, REPORT) and for UUID path variables, causing the CardDAV filter chain
            // to silently fall through to the main SecurityConfig.
            .securityMatcher(AntPathRequestMatcher("/api/v1/carddav/**"))
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/v1/carddav/**").authenticated()
            }
            .httpBasic { basic ->
                basic.realmName("CRM CardDAV")
            }
            .userDetailsService(cardDavUserDetailsService)

        return http.build()
    }
}

@Component
class CardDavUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val entity = userRepository.findByEmail(username.lowercase().trim())
            ?: throw UsernameNotFoundException("User not found: $username")

        if (!entity.isActive) {
            throw UsernameNotFoundException("User account is disabled: $username")
        }

        return CardDavUserDetails(
            studioId = entity.studioId,
            username = entity.email,
            passwordHash = entity.passwordHash,
            role = entity.role.name
        )
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
