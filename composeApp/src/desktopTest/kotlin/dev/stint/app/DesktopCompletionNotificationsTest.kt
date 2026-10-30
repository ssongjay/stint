package dev.stint.app

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class DesktopCompletionNotificationsTest {
    @Test
    fun `running focus schedules one stable future notification`() = runTest {
        val bridge = RecordingBridge()
        val notifications = DesktopCompletionNotifications(bridge)
        val state = state(now = 12_000, timer = running(targetSeconds = 5))

        notifications.update(state)
        notifications.update(state.copy(serverNowEpochMs = 13_000))

        assertEquals(1, bridge.syncs.size)
        val plan = bridge.syncs.single().first
        assertEquals("stint.timer.timer_FOCUS_10000", plan?.identifier)
        assertEquals(15_000, plan?.deadlineEpochMs)
        assertEquals(3_000, plan?.delayMillis)
        assertEquals("집중을 마쳤어요", plan?.title)
        assertEquals("개발 공부 · 잠시 쉬어가세요.", plan?.body)
    }

    @Test
    fun `pause duration mutation and offline state cancel a stale schedule`() = runTest {
        val bridge = RecordingBridge()
        val notifications = DesktopCompletionNotifications(bridge)
        val running = running(targetSeconds = 5)
        notifications.update(state(10_000, running))

        notifications.update(state(12_000, running.copy(
            status = TimerStatus.PAUSED,
            accumulatedMillis = 2_000,
            runningSinceEpochMs = null,
        )))
        notifications.update(state(12_100, running, ConnectionStatus.OFFLINE))

        assertEquals(2, bridge.syncs.size)
        assertNull(bridge.syncs.last().first)
        assertNull(bridge.syncs.last().second)
    }

    @Test
    fun `break schedule uses break completion copy`() {
        val plan = desktopCompletionPlan(state(
            now = 10_000,
            timer = running(TimerPhase.LONG_BREAK, targetSeconds = 3),
        ))

        assertEquals("휴식이 끝났어요", plan?.title)
        assertEquals("개발 공부 · 다음 집중을 시작할 준비가 됐어요.", plan?.body)
    }

    @Test
    fun `startup overdue paused and stopwatch states do not schedule`() {
        assertNull(desktopCompletionPlan(state(20_000, running(targetSeconds = 5))))
        assertNull(desktopCompletionPlan(state(10_000, running(targetSeconds = 5).copy(
            status = TimerStatus.PAUSED,
            runningSinceEpochMs = null,
        ))))
        assertNull(desktopCompletionPlan(state(10_000, running(targetSeconds = 5).copy(
            mode = TimerMode.STOPWATCH,
        ))))
    }

    @Test
    fun `test notification reports authorization and send results in Korean`() = runTest {
        val bridge = RecordingBridge()
        val notifications = DesktopCompletionNotifications(bridge)

        bridge.testResponse = "{\"ok\":true,\"authorization\":\"authorized\"}"
        assertEquals("테스트 알림을 보냈어요.", notifications.testNotification())
        bridge.testResponse = "{\"ok\":false,\"authorization\":\"denied\"}"
        assertEquals("시스템 설정에서 Stint 알림을 허용해 주세요.", notifications.testNotification())
    }

    @Test
    fun `failed native schedule retries after bounded delay and then caches success`() = runTest {
        var now = 1_000L
        val bridge = RecordingBridge().apply {
            responses.addLast("{\"ok\":false,\"error\":\"temporary\"}")
            responses.addLast("{\"ok\":true}")
        }
        val notifications = DesktopCompletionNotifications(bridge) { now }
        val active = state(10_000, running(targetSeconds = 10))

        notifications.update(active)
        now = 5_999
        notifications.update(active)
        assertEquals(1, bridge.syncs.size)
        now = 6_000
        notifications.update(active)
        notifications.update(active)

        assertEquals(2, bridge.syncs.size)
    }

    @Test
    fun `terminal close wins over an in-flight schedule and prevents later updates`() = runTest {
        val bridge = RecordingBridge()
        val notifications = DesktopCompletionNotifications(bridge)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        bridge.blockNext = gate
        bridge.syncStarted = started
        val active = state(10_000, running(targetSeconds = 10))

        val update = async { notifications.update(active) }
        started.await()
        val close = async { notifications.close() }
        gate.complete(Unit)
        update.await()
        close.await()
        notifications.update(active.copy(serverNowEpochMs = 11_000))

        assertEquals(2, bridge.syncs.size)
        assertNull(bridge.syncs.last().first)
        assertNull(bridge.syncs.last().second)
    }

    @Test
    fun `blocking close on the ui thread cannot deadlock behind an in-flight update`() {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "completion-notification-ui").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val bridge = RecordingBridge()
            val notifications = DesktopCompletionNotifications(bridge)
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            bridge.blockNext = gate
            bridge.syncStarted = started
            val active = state(10_000, running(targetSeconds = 10))
            val update = scope.launch { notifications.update(active) }
            runBlocking { withTimeout(2_000) { started.await() } }

            val closeStarted = CountDownLatch(1)
            val close = executor.submit {
                closeStarted.countDown()
                runBlocking { notifications.close() }
            }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            gate.complete(Unit)

            close.get(2, TimeUnit.SECONDS)
            runBlocking { withTimeout(2_000) { update.join() } }
            assertNull(bridge.syncs.last().first)
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private class RecordingBridge : DesktopNotificationBridge {
        val syncs = mutableListOf<Pair<DesktopCompletionPlan?, dev.stint.app.client.TimerCompletionEvent?>>()
        var testResponse: String? = null
        val responses = ArrayDeque<String>()
        var blockNext: CompletableDeferred<Unit>? = null
        var syncStarted: CompletableDeferred<Unit>? = null
        override suspend fun authorize(): String = "authorized"
        override suspend fun sync(
            scheduled: DesktopCompletionPlan?,
            immediate: dev.stint.app.client.TimerCompletionEvent?,
        ): String {
            syncs += scheduled to immediate
            syncStarted?.complete(Unit)
            val blocker = blockNext
            blockNext = null
            blocker?.await()
            return responses.removeFirstOrNull() ?: "{\"ok\":true}"
        }
        override suspend fun test(): String? = testResponse
    }

    private fun state(
        now: Long,
        timer: TimerState,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
    ) = ClientState(
        settings = ConnectionSettings(baseUrl = "https://stint.example", token = "token"),
        snapshot = Snapshot(
            serverNowEpochMs = now,
            revision = 1,
            subjects = listOf(Subject("subject", "개발 공부")),
            timer = timer,
        ),
        serverNowEpochMs = now,
        connection = connection,
    )

    private fun running(
        phase: TimerPhase = TimerPhase.FOCUS,
        targetSeconds: Int,
    ) = TimerState(
        id = "timer",
        subjectId = "subject",
        status = TimerStatus.RUNNING,
        phase = phase,
        mode = TimerMode.COUNTDOWN,
        targetSeconds = targetSeconds,
        accumulatedMillis = 0,
        runningSinceEpochMs = 10_000,
        startedAtEpochMs = 10_000,
    )
}
