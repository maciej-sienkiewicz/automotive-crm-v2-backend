package pl.detailing.crm.user.domain

import pl.detailing.crm.shared.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

// ==================== DOMAIN MODEL ====================

/**
 * User - Belongs to a Studio (multi-tenant)
 */
data class User(
    val id: UserId,
    val studioId: StudioId,
    val email: String,
    val phoneNumber: String,
    val passwordHash: String,
    val firstName: String,
    val lastName: String,
    val role: UserRole,
    val isActive: Boolean,
    val createdAt: Instant,
    val mobileToken: String? = null
)