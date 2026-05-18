package pl.detailing.crm.task.archive

import pl.detailing.crm.shared.StudioId

data class ListArchivedTasksQuery(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20,
    val search: String? = null
)
