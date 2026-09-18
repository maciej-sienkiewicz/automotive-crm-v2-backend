package pl.detailing.crm.careinstruction

data class CareInstructionDto(
    val id: String,
    val title: String,
    val content: String,
    /** Zaznaczaj przy każdym certyfikacie. */
    val isDefaultSelected: Boolean,
    val sortOrder: Int,
    /** Usługi z cennika, które zaznaczają tę instrukcję automatycznie. */
    val serviceIds: List<String>
)

data class SaveCareInstructionRequest(
    val title: String,
    val content: String,
    val isDefaultSelected: Boolean = false
)

data class SetServiceCareInstructionsRequest(
    val instructionIds: List<String> = emptyList()
)
