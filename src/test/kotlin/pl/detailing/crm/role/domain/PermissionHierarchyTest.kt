package pl.detailing.crm.role.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionHierarchyTest {

    @Test
    fun `parent chain always terminates and stays within one module`() {
        Permission.entries.forEach { permission ->
            val seen = mutableSetOf(permission)
            var current = permission.parent
            while (current != null) {
                assertTrue(seen.add(current)) {
                    "Cycle detected in parent chain of ${permission.name}"
                }
                assertEquals(permission.module, current.module) {
                    "${permission.name} has ancestor ${current!!.name} from another module — " +
                        "the tree must render inside a single module group"
                }
                current = current.parent
            }
        }
    }

    @Test
    fun `every module with permissions has at least one root`() {
        val modulesInUse = Permission.entries.map { it.module }.toSet()
        modulesInUse.forEach { module ->
            assertTrue(PermissionHierarchy.rootsOf(module).isNotEmpty()) {
                "Module ${module.name} has permissions but no root node"
            }
        }
    }

    @Test
    fun `close is idempotent`() {
        Permission.entries.forEach { permission ->
            val once = PermissionHierarchy.close(setOf(permission))
            val twice = PermissionHierarchy.close(once)
            assertEquals(once, twice) { "close not idempotent for ${permission.name}" }
        }
    }

    @Test
    fun `closed set contains the full ancestor path of every member`() {
        Permission.entries.forEach { permission ->
            val closed = PermissionHierarchy.close(setOf(permission))
            closed.forEach { member ->
                val missing = PermissionHierarchy.ancestorsOf(member) - closed
                assertTrue(missing.isEmpty()) {
                    "close(${permission.name}) is missing ${missing.map { it.name }} required by ${member.name}"
                }
            }
        }
    }

    @Test
    fun `editing service prices pulls in the whole visit path`() {
        val closed = PermissionHierarchy.close(setOf(Permission.VISITS_SERVICE_PRICES_EDIT))
        assertEquals(
            setOf(
                Permission.VISITS_SERVICE_PRICES_EDIT,
                Permission.VISITS_SERVICE_PRICES_VIEW,
                Permission.VISITS_SERVICES_VIEW,
                Permission.VISITS_VIEW_DETAILS,
                Permission.VISITS_VIEW
            ),
            closed
        )
    }

    @Test
    fun `editing a customer implies viewing personal data and the customer list`() {
        val closed = PermissionHierarchy.close(setOf(Permission.CUSTOMERS_EDIT))
        assertTrue(Permission.CUSTOMERS_VIEW_PERSONAL_DATA in closed)
        assertTrue(Permission.CUSTOMERS_VIEW in closed)
    }

    @Test
    fun `plain view permissions never require personal data access`() {
        // Personal-data visibility is handled by masking, not hierarchy.
        listOf(
            Permission.VISITS_VIEW,
            Permission.CALENDAR_VIEW,
            Permission.COMMUNICATION_VIEW_LOGS
        ).forEach { viewPermission ->
            val closed = PermissionHierarchy.close(setOf(viewPermission))
            assertTrue(Permission.CUSTOMERS_VIEW_PERSONAL_DATA !in closed) {
                "${viewPermission.name} must not auto-grant personal data access"
            }
        }
    }

    @Test
    fun `subtree of visit details covers every in-visit action`() {
        val subtree = PermissionHierarchy.subtreeOf(Permission.VISITS_VIEW_DETAILS)
        listOf(
            Permission.VISITS_DELETE,
            Permission.VISITS_EDIT,
            Permission.VISITS_SERVICES_MANAGE,
            Permission.VISITS_SERVICE_PRICES_EDIT,
            Permission.VISITS_MEDIA_UPLOAD,
            Permission.VISITS_COMMENTS_ADD,
            Permission.VISITS_NOTES_ADD,
            Permission.VISITS_DOCUMENTS_SIGN
        ).forEach { assertTrue(it in subtree) { "${it.name} should live under VISITS_VIEW_DETAILS" } }
        assertTrue(Permission.VISITS_CREATE !in subtree)
        assertTrue(Permission.VISITS_CHANGE_STATUS !in subtree)
    }

    @Test
    fun `legacy stored codes map onto the new tree`() {
        mapOf(
            "VISITS_VIEW_PRICES" to Permission.VISITS_SERVICE_PRICES_VIEW,
            "VISITS_VIEW_COMMENTS" to Permission.VISITS_COMMENTS_VIEW,
            "VISITS_ADD_COMMENT" to Permission.VISITS_COMMENTS_ADD,
            "GALLERY_UPLOAD" to Permission.VISITS_MEDIA_UPLOAD,
            "DOCUMENTS_SIGN" to Permission.VISITS_DOCUMENTS_SIGN,
            "VISITS_VIEW" to Permission.VISITS_VIEW
        ).forEach { (stored, expected) ->
            assertEquals(expected, Permission.fromStoredCode(stored))
        }
        assertEquals(null, Permission.fromStoredCode("NO_SUCH_PERMISSION"))
        // API lookups must stay strict — aliases are for persisted rows only.
        assertEquals(null, Permission.fromApiCode("GALLERY_UPLOAD"))
    }
}
