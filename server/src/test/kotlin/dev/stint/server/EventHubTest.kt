package dev.stint.server

import dev.stint.shared.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class EventHubTest {
    @Test
    fun `a late snapshot cannot move websocket state to an older revision`() {
        val events = EventHub(Snapshot(serverNowEpochMs = 1, revision = 9))

        events.publish(Snapshot(serverNowEpochMs = 3, revision = 11))
        events.publish(Snapshot(serverNowEpochMs = 2, revision = 10))

        assertEquals(11, events.current.revision)
        assertEquals(3, events.current.serverNowEpochMs)
    }

    @Test
    fun `a newer snapshot and a refreshed snapshot at the same revision are accepted`() {
        val events = EventHub(Snapshot(serverNowEpochMs = 1, revision = 10))

        events.publish(Snapshot(serverNowEpochMs = 2, revision = 10))
        assertEquals(2, events.current.serverNowEpochMs)

        events.publish(Snapshot(serverNowEpochMs = 3, revision = 11))
        assertEquals(11, events.current.revision)
    }
}
