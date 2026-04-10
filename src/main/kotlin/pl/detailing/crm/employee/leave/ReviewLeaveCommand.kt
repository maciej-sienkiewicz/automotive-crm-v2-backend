package pl.detailing.crm.employee.leave

import pl.detailing.crm.shared.*

data class ReviewLeaveCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val leaveRequestId: LeaveRequestId,
    val approve: Boolean,
    val reviewNote: String?
)
