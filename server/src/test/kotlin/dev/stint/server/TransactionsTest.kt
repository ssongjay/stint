package dev.stint.server

import java.lang.reflect.Proxy
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TransactionsTest {
    @Test
    fun `rollback failure is suppressed by the original transaction failure`() {
        val original = IllegalStateException("write failed")
        val rollback = IllegalStateException("rollback failed")
        val connection = connectionWhoseRollbackThrows(rollback)

        val thrown = assertFailsWith<IllegalStateException> {
            connection.rollbackAndRethrow(original)
        }

        assertSame(original, thrown)
        assertEquals(listOf(rollback), thrown.suppressed.toList())
    }

    private fun connectionWhoseRollbackThrows(failure: Exception): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            if (method.name == "rollback") throw failure
            error("Unexpected Connection method: ${method.name}")
        } as Connection
}
