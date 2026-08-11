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
    fun `editing service prices pulls in the full chain to VISITS_VIEW`() {
        val closed = PermissionHierarchy.close(setOf(Permission.VISITS_SERVICE_PRICES_EDIT))
        // VISITS_SERVICE_PRICES_VIEW → CUSTOMERS_VIEW → VISITS_VIEW (parent chain)
        assertEquals(
            setOf(
                Permission.VISITS_SERVICE_PRICES_EDIT,
                Permission.VISITS_SERVICE_PRICES_VIEW,
                Permission.CUSTOMERS_VIEW,
                Permission.VISITS_VIEW
            ),
            closed
        )
    }

    @Test
    fun `closed set contains the implications of every member`() {
        Permission.entries.forEach { permission ->
            val closed = PermissionHierarchy.close(setOf(permission))
            closed.forEach { member ->
                val missing = PermissionHierarchy.impliesOf(member) - closed
                assertTrue(missing.isEmpty()) {
                    "close(${permission.name}) is missing ${missing.map { it.name }} implied by ${member.name}"
                }
            }
        }
    }

    @Test
    fun `creating visits carries the whole booking desk-flow but not the destructive permissions`() {
        val closed = PermissionHierarchy.close(setOf(Permission.VISITS_CREATE))
        // Parent chain: VISITS_CREATE → VISITS_SERVICE_PRICES_VIEW → CUSTOMERS_VIEW → VISITS_VIEW
        // Implication: VISITS_CREATE → VISITS_SERVICE_PRICES_EDIT (discount desk-flow)
        assertEquals(
            setOf(
                Permission.VISITS_CREATE,
                Permission.VISITS_SERVICE_PRICES_VIEW,
                Permission.CUSTOMERS_VIEW,
                Permission.VISITS_VIEW,
                Permission.VISITS_SERVICE_PRICES_EDIT
            ),
            closed
        )
        // Destructive actions are children of VISITS_CREATE — not auto-granted upward.
        assertTrue(Permission.VISITS_DELETE !in closed)
        assertTrue(Permission.VISITS_MEDIA_DELETE !in closed)
        assertTrue(Permission.CUSTOMERS_DELETE !in closed)
        // Status changes and documents are separate policies.
        assertTrue(Permission.VISITS_CHANGE_STATUS !in closed)
        assertTrue(Permission.VISITS_DOCUMENTS_MANAGE !in closed)
    }

    @Test
    fun `invoicing requires visit creation and carries the full booking chain`() {
        val closed = PermissionHierarchy.close(setOf(Permission.FINANCE_INVOICES))
        // FINANCE_INVOICES → VISITS_CREATE → (parent chain) VISITS_SERVICE_PRICES_VIEW,
        // CUSTOMERS_VIEW, VISITS_VIEW + VISITS_CREATE → VISITS_SERVICE_PRICES_EDIT
        assertTrue(Permission.VISITS_CREATE in closed)
        assertTrue(Permission.CUSTOMERS_VIEW in closed)
        assertTrue(Permission.VISITS_VIEW in closed)
        assertTrue(Permission.VISITS_SERVICE_PRICES_EDIT in closed)
    }

    @Test
    fun `sending messages requires visit creation`() {
        val closed = PermissionHierarchy.close(setOf(Permission.COMMUNICATION_SEND))
        assertTrue(Permission.VISITS_CREATE in closed)
        assertTrue(Permission.CUSTOMERS_VIEW in closed)
        assertTrue(Permission.VISITS_VIEW in closed)
    }

    @Test
    fun `every non-VISITS module root requires visit creation`() {
        val nonVisitsRoots = Permission.entries
            .filter { it.module != PermissionModule.VISITS && it.parent == null }
        assertTrue(nonVisitsRoots.isNotEmpty()) { "Expected non-VISITS roots to exist" }
        nonVisitsRoots.forEach { root ->
            val closed = PermissionHierarchy.close(setOf(root))
            assertTrue(Permission.VISITS_CREATE in closed) {
                "${root.name} (module=${root.module}) must imply VISITS_CREATE"
            }
        }
    }

    @Test
    fun `delete permissions require visit creation`() {
        listOf(Permission.VISITS_DELETE, Permission.CUSTOMERS_DELETE, Permission.VISITS_MEDIA_DELETE)
            .forEach { delete ->
                val closed = PermissionHierarchy.close(setOf(delete))
                assertTrue(Permission.VISITS_CREATE in closed) {
                    "${delete.name} must require VISITS_CREATE (cannot delete what you cannot create)"
                }
            }
    }

    @Test
    fun `shop-floor viewing works without access to the customer database`() {
        // A detailer sees the schedule (calendar included) and visit contents with the
        // customer masked at the serialization boundary — VISITS_VIEW must NOT
        // auto-grant CUSTOMERS_VIEW (the personal-data permission).
        val closed = PermissionHierarchy.close(setOf(Permission.VISITS_VIEW))
        assertTrue(Permission.CUSTOMERS_VIEW !in closed) {
            "VISITS_VIEW must not auto-grant customer data access"
        }
    }

    @Test
    fun `a detailer role is expressible with two checkboxes and carries no personal data`() {
        val detailer = PermissionHierarchy.close(
            setOf(Permission.VISITS_VIEW, Permission.VISITS_CHANGE_STATUS)
        )
        assertEquals(setOf(Permission.VISITS_VIEW, Permission.VISITS_CHANGE_STATUS), detailer)
    }

    @Test
    fun `legacy stored codes map onto the consolidated tree`() {
        mapOf(
            // flat-list era
            "VISITS_VIEW_PRICES" to Permission.VISITS_SERVICE_PRICES_VIEW,
            "VISITS_VIEW_COMMENTS" to Permission.VISITS_VIEW,
            "VISITS_ADD_COMMENT" to Permission.VISITS_VIEW,
            "GALLERY_UPLOAD" to Permission.VISITS_VIEW,
            "GALLERY_DELETE" to Permission.VISITS_MEDIA_DELETE,
            "DOCUMENTS_SIGN" to Permission.VISITS_DOCUMENTS_MANAGE,
            "CALENDAR_VIEW" to Permission.VISITS_VIEW,
            "CALENDAR_MANAGE" to Permission.VISITS_CREATE,
            "VEHICLES_VIEW" to Permission.VISITS_VIEW,
            "VEHICLES_CREATE" to Permission.VISITS_CREATE,
            "VEHICLES_DELETE" to Permission.CUSTOMERS_DELETE,
            "CUSTOMERS_VIEW_PERSONAL_DATA" to Permission.CUSTOMERS_VIEW,
            "CUSTOMERS_MANAGE" to Permission.VISITS_CREATE,
            "CUSTOMERS_CREATE" to Permission.VISITS_CREATE,
            "CUSTOMERS_EDIT" to Permission.VISITS_CREATE,
            "COMMUNICATION_VIEW_LOGS" to Permission.CUSTOMERS_VIEW,
            "COMMUNICATION_SEND_SMS" to Permission.COMMUNICATION_SEND,
            "FINANCE_VIEW_INVOICES" to Permission.FINANCE_INVOICES,
            "FINANCE_CREATE_INVOICE" to Permission.FINANCE_INVOICES,
            "EMPLOYEES_MANAGE_ACCOUNTS" to Permission.EMPLOYEES_MANAGE,
            "EMPLOYEES_VIEW_PAYROLL" to Permission.EMPLOYEES_PAYROLL,
            "TASKS_ASSIGN" to Permission.TASKS_MANAGE,
            "LEADS_VIEW" to Permission.LEADS_MANAGE,
            // pre-consolidation tree nodes
            "VISITS_VIEW_DETAILS" to Permission.VISITS_VIEW,
            "VISITS_EDIT" to Permission.VISITS_CREATE,
            "VISITS_SERVICES_VIEW" to Permission.VISITS_VIEW,
            "VISITS_SERVICES_MANAGE" to Permission.VISITS_CREATE,
            "VISITS_COMMENTS_ADD" to Permission.VISITS_VIEW,
            "VISITS_NOTES_ADD" to Permission.VISITS_VIEW,
            "VISITS_MEDIA_VIEW" to Permission.VISITS_VIEW,
            "VISITS_MEDIA_UPLOAD" to Permission.VISITS_VIEW,
            "VISITS_DOCUMENTS_VIEW" to Permission.VISITS_DOCUMENTS_MANAGE,
            // current codes resolve to themselves
            "VISITS_VIEW" to Permission.VISITS_VIEW
        ).forEach { (stored, expected) ->
            assertEquals(expected, Permission.fromStoredCode(stored)) { "for $stored" }
        }
        // Retired without a successor: the coworker directory is not permission-gated.
        assertEquals(null, Permission.fromStoredCode("EMPLOYEES_VIEW"))
        assertEquals(null, Permission.fromStoredCode("NO_SUCH_PERMISSION"))
        // API lookups must stay strict — aliases are for persisted rows only.
        assertEquals(null, Permission.fromApiCode("GALLERY_UPLOAD"))
        assertEquals(null, Permission.fromApiCode("CUSTOMERS_VIEW_PERSONAL_DATA"))
    }
}
