package pl.detailing.crm.appointmentcolor.create

data class CreateAppointmentColorValidationContext(
    val name: String,
    val hexColor: String,
    val nameAlreadyExists: Boolean
)
