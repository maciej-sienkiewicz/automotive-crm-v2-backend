package pl.detailing.crm.role.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionDependenciesTest {

    @Test
    fun `dependency graph is acyclic`() {
        // White/grey/black DFS cycle detection over the full permission catalog.
        val visiting = mutableSetOf<Permission>()
        val done = mutableSetOf<Permission>()

        fun dfs(node: Permission, path: List<Permission>) {
            if (node in done) return
            assertTrue(node !in visiting) {
                "Cycle detected: ${(path + node).joinToString(" -> ") { it.name }}"
            }
            visiting.add(node)
            PermissionDependencies.directDependenciesOf(node).forEach { dfs(it, path + node) }
            visiting.remove(node)
            done.add(node)
        }

        Permission.entries.forEach { dfs(it, emptyList()) }
    }

    @Test
    fun `close is idempotent`() {
        Permission.entries.forEach { permission ->
            val once = PermissionDependencies.close(setOf(permission))
            val twice = PermissionDependencies.close(once)
            assertEquals(once, twice) { "close not idempotent for ${permission.name}" }
        }
    }

    @Test
    fun `closed set contains every transitive prerequisite`() {
        Permission.entries.forEach { permission ->
            val closed = PermissionDependencies.close(setOf(permission))
            // Every member's direct dependencies must also be present.
            closed.forEach { member ->
                val missing = PermissionDependencies.directDependenciesOf(member) - closed
                assertTrue(missing.isEmpty()) {
                    "close(${permission.name}) is missing ${missing.map { it.name }} required by ${member.name}"
                }
            }
        }
    }

    @Test
    fun `editing a customer implies viewing personal data and the customer list`() {
        val closed = PermissionDependencies.close(setOf(Permission.CUSTOMERS_EDIT))
        assertTrue(Permission.CUSTOMERS_VIEW_PERSONAL_DATA in closed)
        assertTrue(Permission.CUSTOMERS_VIEW in closed)
    }

    @Test
    fun `creating a visit pulls in customer and vehicle creation`() {
        val closed = PermissionDependencies.close(setOf(Permission.VISITS_CREATE))
        assertTrue(Permission.VISITS_VIEW in closed)
        assertTrue(Permission.CUSTOMERS_CREATE in closed)
        assertTrue(Permission.CUSTOMERS_VIEW_PERSONAL_DATA in closed)
        assertTrue(Permission.VEHICLES_CREATE in closed)
    }

    @Test
    fun `plain view permissions never require personal data access`() {
        // Personal-data visibility is handled by masking, not dependencies.
        listOf(
            Permission.VISITS_VIEW,
            Permission.CALENDAR_VIEW,
            Permission.COMMUNICATION_VIEW_LOGS
        ).forEach { viewPermission ->
            val closed = PermissionDependencies.close(setOf(viewPermission))
            assertTrue(Permission.CUSTOMERS_VIEW_PERSONAL_DATA !in closed) {
                "${viewPermission.name} must not auto-grant personal data access"
            }
        }
    }
}
