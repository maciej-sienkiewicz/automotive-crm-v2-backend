package pl.detailing.crm.appointmentcolor.update

data class UpdateAppointmentColorValidationContext(
    val name: String,
    val hexColor: String,
    val colorExists: Boolean,
    val nameAlreadyExistsInOtherColor: Boolean
)
