package dev.stint.server

import dev.stint.shared.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ApiTickerTest {
    @Test
    fun `ticker rethrows coroutine cancellation without reporting an operational failure`() = runBlocking {
        val cancellation = CancellationException("shutdown")
        var reported: Exception? = null

        val thrown = assertFailsWith<CancellationException> {
            runTimerSettlement(
                settle = { throw cancellation },
                publish = {},
                onFailure = { reported = it },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(null, reported)
    }

    @Test
    fun `ticker reports an operational failure and continues`() = runBlocking {
        val failure = IllegalStateException("database unavailable")
        var reported: Exception? = null

        runTimerSettlement(
            settle = { throw failure },
            publish = {},
            onFailure = { reported = it },
        )

        assertSame(failure, reported)
    }

    @Test
    fun `ticker publishes a settled snapshot`() = runBlocking {
        val snapshot = Snapshot(serverNowEpochMs = 10, revision = 2)
        var published: Snapshot? = null

        runTimerSettlement(
            settle = { MutationResult(snapshot, changed = true) },
            publish = { published = it },
            onFailure = {},
        )

        assertEquals(snapshot, published)
    }
}
