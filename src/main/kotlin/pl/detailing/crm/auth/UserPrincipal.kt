package pl.detailing.crm.auth

import pl.detailing.crm.shared.*
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.io.Serializable

data class UserPrincipal(
    val userId: UserId,
    val studioId: StudioId,
    val role: UserRole,
    val email: String
) : Authentication, Serializable {

    override fun getName(): String = email

    override fun getAuthorities(): Collection<GrantedAuthority> {
        return listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
    }

    override fun getCredentials(): Any? = null

    override fun getDetails(): Any? = null

    override fun getPrincipal(): Any = this

    override fun isAuthenticated(): Boolean = true

    override fun setAuthenticated(isAuthenticated: Boolean) {
        throw UnsupportedOperationException("Cannot change authentication state")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

object SecurityContextHelper {

    fun getCurrentUser(): UserPrincipal {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw UnauthorizedException("No authentication found")

        return when (authentication) {
            is UserPrincipal -> authentication
            else -> throw UnauthorizedException("Invalid authentication type")
        }
    }

    fun getCurrentUserId(): UserId = getCurrentUser().userId

    fun getCurrentStudioId(): StudioId = getCurrentUser().studioId

    fun getCurrentUserRole(): UserRole = getCurrentUser().role

    fun isOwner(): Boolean = getCurrentUserRole() == UserRole.OWNER

    fun isManagerOrOwner(): Boolean {
        val role = getCurrentUserRole()
        return role == UserRole.OWNER || role == UserRole.MANAGER
    }
}