package pl.detailing.crm.leads.similar.feedback

/**
 * Powód zdjęcia podpowiedzi „X-em" — trzy kody, nie sześć.
 *
 * Taksonomii powodów nie projektuje się przed rozkładem realnych przypadków:
 * start minimalny, rozbicie bucketa dopiero gdy dziennik pokaże, że przekracza
 * ~20% udziału. Powód jest opcjonalny (samo X działa jak dotąd) — wymuszony
 * formularz zabiłby jedyny sygnał, który dziś w ogóle spływa.
 */
enum class DismissReason {
    /** Inne rzemiosło albo inna robota — „to nie jest to, o co pyta klient". */
    WRONG_WORK,

    /** Ta robota, ale nieporównywalna skala — „taka kwota tu nie pomaga". */
    WRONG_SCALE,

    /** Wszystko inne, w tym stare „X" bez powodu. */
    OTHER;

    companion object {
        fun from(raw: String?): DismissReason =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OTHER
    }
}
