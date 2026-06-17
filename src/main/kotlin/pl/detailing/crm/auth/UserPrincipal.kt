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
    val isOwner: Boolean,
    val email: String,
    val fullName: String,
    val phoneNumber: String,
) : Authentication, Serializable {

    override fun getName(): String = fullName

    override fun getAuthorities(): Collection<GrantedAuthority> {
        return listOf(SimpleGrantedAuthority(if (isOwner) "ROLE_OWNER" else "ROLE_USER"))
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
            ?: throw UnauthorizedException("Brak uwierzytelnienia")

        return when (authentication) {
            is UserPrincipal -> authentication
            else -> throw UnauthorizedException("Nieprawidłowy typ uwierzytelnienia")
        }
    }

    fun getCurrentUserId(): UserId = getCurrentUser().userId

    fun getCurrentStudioId(): StudioId = getCurrentUser().studioId

    fun isOwner(): Boolean = getCurrentUser().isOwner
}