package pl.detailing.crm.employee.leaverequest

import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** Wspólne dane testów wniosków urlopowych. */
object LeaveRequestFixtures {

    fun principal(studioId: StudioId, userId: UserId = UserId.random(), owner: Boolean = false, name: String = "Anna Nowak") =
        UserPrincipal(
            userId = userId,
            studioId = studioId,
            isOwner = owner,
            email = "anna@studio.pl",
            fullName = name,
            phoneNumber = "+48600100200"
        )

    fun employee(studioId: StudioId, userId: UserId?, id: UUID = UUID.randomUUID(), first: String = "Jan", last: String = "Kowalski") =
        EmployeeEntity(
            id = id,
            studioId = studioId.value,
            userId = userId?.value,
            firstName = first,
            lastName = last,
            phone = "+48600000000",
            email = "jan@studio.pl",
            createdBy = UUID.randomUUID(),
            updatedBy = UUID.randomUUID()
        )

    fun request(
        studioId: StudioId,
        employeeId: UUID,
        employeeUserId: UUID?,
        status: LeaveRequestStatus = LeaveRequestStatus.PENDING,
        id: UUID = UUID.randomUUID(),
        start: LocalDate = LocalDate.now().plusDays(30),
        end: LocalDate = start.plusDays(4),
        leaveType: LeaveType = LeaveType.ANNUAL,
        onDemand: Boolean = false,
        documentSha256: String = "a".repeat(64),
        employeeSignedSha256: String? = if (status == LeaveRequestStatus.DRAFT) null else "b".repeat(64),
        origin: LeaveRequestOrigin = LeaveRequestOrigin.SELF_SERVICE,
        createdBy: UUID = employeeUserId ?: UUID.randomUUID(),
        createdByName: String = "Jan Kowalski",
        employeeSignatureMethod: LeaveSignatureMethod = LeaveSignatureMethod.DEVICE_DRAWN
    ) = LeaveRequestEntity(
        id = id,
        studioId = studioId.value,
        number = "WU/2026/0012",
        employeeId = employeeId,
        employeeUserId = employeeUserId,
        leaveType = leaveType,
        onDemand = onDemand,
        startDate = start,
        endDate = end,
        workingDays = 5,
        reason = null,
        status = status,
        origin = origin,
        createdBy = createdBy,
        createdByName = createdByName,
        createdAt = Instant.now(),
        documentS3Key = "${studioId.value}/leave-requests/$id/draft.pdf",
        documentSha256 = documentSha256,
        employeeSignedAt = if (status == LeaveRequestStatus.DRAFT) null else Instant.now(),
        employeeSignatureMethod = if (status == LeaveRequestStatus.DRAFT) null else employeeSignatureMethod,
        employeeSignedPdfS3Key = if (status == LeaveRequestStatus.DRAFT) null else "${studioId.value}/leave-requests/$id/employee-signed-1.pdf",
        employeeSignedSha256 = employeeSignedSha256
    )

    /** Prawdziwy PNG z widocznym „podpisem" — SignatureImageProcessor odrzuca puste kanwy. */
    fun signaturePng(): ByteArray {
        val image = BufferedImage(300, 120, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.BLACK
        g.stroke = BasicStroke(4f)
        g.drawLine(20, 90, 120, 20)
        g.drawLine(120, 20, 200, 100)
        g.drawLine(200, 100, 280, 30)
        g.dispose()
        return ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }

    fun signatureBase64(): String = Base64.getEncoder().encodeToString(signaturePng())
}
